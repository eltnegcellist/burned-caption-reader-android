#!/usr/bin/env python3
"""A/B compare repeated Android diagnostic ZIPs without exporting subtitle text.

This is an OCR workload analyzer, not a substitute for CER or human-verified
audible onset, and does not make a release/adoption decision.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import statistics
import zipfile


def percentile(values, proportion):
    data = sorted(values)
    return data[max(0, math.ceil(proportion * len(data)) - 1)] if data else None


def measure(events, manifest, name):
    frames = [e for e in events if e.get("type") == "frame_done"
              and type(e.get("processing_ms")) in (int, float)
              and e["processing_ms"] >= 0]
    modes = {e.get("ocr_mode") for e in frames if e.get("ocr_mode")}
    if len(modes) != 1:
        raise ValueError(name + ": each ZIP must have one mode and completed frames")
    devices = {e.get("device") for e in events
               if e.get("type") == "session_start" and e.get("device")}
    if len(devices) > 1:
        raise ValueError(name + ": multiple device labels")
    videos = {e.get("video_id") for e in events
              if e.get("type") == "video_frame" and e.get("video_id")}
    if len(videos) > 1:
        raise ValueError(name + ": multiple video IDs")
    configurations = {}
    for key in ("recheck_enabled", "debug_region_rescue"):
        found = {e[key] for e in frames if type(e.get(key)) is bool}
        if len(found) > 1:
            raise ValueError(name + ": setting " + key + " changed within one ZIP")
        configurations[key] = next(iter(found), None)
    durations = [e["processing_ms"] for e in frames]
    warm = durations[1:]
    costs = [e["elapsed_ms"] for e in events if e.get("type") == "ocr_stage_done"
             and type(e.get("elapsed_ms")) in (int, float)
             and e["elapsed_ms"] >= 0 and not e.get("error")]
    calls = Counter(e.get("stage") for e in events if e.get("type") == "ocr_call")
    missing = sum(e.get("type") == "selection_summary"
                  and type(e.get("raw_rows")) is int and e["raw_rows"] > 0
                  and e.get("selected_bands") == 0 for e in events)
    shadow = [e for e in events if e.get("type") == "skip_shadow_outcome"
              and e.get("would_skip") is True]
    return {
        "source": name, "ocr_mode": next(iter(modes)),
        "recheck_enabled": configurations["recheck_enabled"],
        "debug_region_rescue": configurations["debug_region_rescue"],
        "device": next(iter(devices), None),
        "video_id": next(iter(videos), None),
        "frames_completed": len(frames),
        "first_completed_ms": durations[0],
        "warm_median_ms": statistics.median(warm) if warm else None,
        "warm_p95_ms": percentile(warm, .95),
        "ocr_stage_elapsed_total_ms": sum(costs) if costs else None,
        "ocr_stage_elapsed_records": len(costs),
        "ocr_call_counts": dict(sorted(calls.items())),
        "raw_positive_selected_empty_frames": missing,
        "shadow_would_skip": len(shadow),
        "shadow_hides_selected_change": sum(e.get("selected_text_changed") is True for e in shadow),
        "dropped_records_or_images": manifest.get("dropped_records_or_images"),
    }


def summarize(runs):
    fields = ("warm_median_ms", "warm_p95_ms", "ocr_stage_elapsed_total_ms",
              "frames_completed", "raw_positive_selected_empty_frames",
              "shadow_would_skip", "shadow_hides_selected_change")
    report = {}
    for field in fields:
        values = [r[field] for r in runs if type(r[field]) in (int, float)]
        report[field] = {
            "run_count": len(values),
            "median_of_run_values": statistics.median(values) if values else None,
            "min": min(values) if values else None,
            "max": max(values) if values else None,
        }
    return report


def compare(baseline, candidate):
    if not baseline or not candidate:
        raise ValueError("Need at least one ZIP per group")
    devices = {r["device"] for r in baseline + candidate if r["device"]}
    if len(devices) > 1:
        raise ValueError("A/B ZIPs must use one physical device")
    video_ids = {r["video_id"] for r in baseline + candidate if r["video_id"]}
    if len(video_ids) > 1:
        raise ValueError("A/B ZIPs must use one video ID")
    def setting_signature(row):
        return (row["ocr_mode"], row["recheck_enabled"], row["debug_region_rescue"])

    if len({setting_signature(row) for row in baseline}) != 1:
        raise ValueError("Baseline ZIPs have inconsistent OCR/recheck/rescue settings")
    if len({setting_signature(row) for row in candidate}) != 1:
        raise ValueError("Candidate ZIPs have inconsistent OCR/recheck/rescue settings")
    baseline_summary = summarize(baseline)
    candidate_summary = summarize(candidate)
    differences = {}
    for field in ("warm_median_ms", "warm_p95_ms", "ocr_stage_elapsed_total_ms",
                  "frames_completed", "raw_positive_selected_empty_frames"):
        a = baseline_summary[field]["median_of_run_values"]
        b = candidate_summary[field]["median_of_run_values"]
        differences[field + "_relative_change"] = b / a - 1 if a and b is not None else None
    repeated = len(baseline) >= 3 and len(candidate) >= 3
    complete = all(type(r["dropped_records_or_images"]) is int
                   and r["dropped_records_or_images"] == 0
                   for r in baseline + candidate)
    return {
        "schema": 1,
        "baseline_modes": sorted({x["ocr_mode"] for x in baseline}),
        "candidate_modes": sorted({x["ocr_mode"] for x in candidate}),
        "same_device_verified": all(x["device"] for x in baseline + candidate) and len(devices) == 1,
        "same_video_verified": all(x["video_id"] for x in baseline + candidate) and len(video_ids) == 1,
        "three_or_more_runs_each": repeated,
        "no_reported_drops": complete,
        "settings_verified": all(type(r["recheck_enabled"]) is bool
                                 and type(r["debug_region_rescue"]) is bool
                                 for r in baseline + candidate),
        "baseline": {"runs": baseline, "aggregate": baseline_summary},
        "candidate": {"runs": candidate, "aggregate": candidate_summary},
        "relative_changes": differences,
        "decision": "insufficient_for_adoption_without_ground_truth",
        "limitations": [
            "Median of run-level summaries, NOT frame-weighted or paired subtitle accuracy.",
            "Cold initialization can continue beyond the first completed frame.",
            "Missing/failed OCR callbacks are not counted in completed-time totals.",
            "Hypothetical skip and rescue events are NOT performance savings.",
            "Raw-positive is not proof that a caption is correct.",
            "Missing video/device/setting labels are not independently verified.",
            "Image/ZIP content may contain private text; this JSON intentionally omits it.",
            "External PSS, CER, short-caption audit and audible onset are still needed.",
        ],
    }


def load(path):
    with zipfile.ZipFile(path) as z:
        events = [json.loads(line) for line in
                  z.read("events.jsonl").decode("utf-8").splitlines() if line.strip()]
        manifest = (json.loads(z.read("manifest.json"))
                    if "manifest.json" in z.namelist() else {})
    return measure(events, manifest, str(path.name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", nargs="+", required=True, type=Path)
    parser.add_argument("--candidate", nargs="+", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = compare([load(path) for path in args.baseline],
                     [load(path) for path in args.candidate])
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
