#!/usr/bin/env python3
"""P0/P1: explain OCR raw->selection loss without publishing subtitle text.

This report is diagnostic evidence, NOT a human-verified correctness label:
the 9 previously observed raw-correct/selected-empty events need their
original Mac image and independent ground truth to establish their cause.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import statistics
import zipfile


def quantiles_ms(values):
    values = sorted(x for x in values if type(x) in (int, float) and x >= 0)
    if not values:
        return {"count": 0, "median": None, "p95": None}
    return {"count": len(values), "median": statistics.median(values),
            "p95": values[math.ceil(len(values) * .95) - 1]}


def analyze(events, max_examples=100):
    by_frame = defaultdict(list)
    global_counts = Counter()
    for e in events:
        if not isinstance(e, dict):
            continue
        kind = e.get("type")
        if isinstance(kind, str):
            global_counts[kind] += 1
        frame = e.get("frame_id")
        if type(frame) is int and frame >= 0:
            by_frame[frame].append(e)

    examples = []
    tally = Counter()
    stage_time = defaultdict(list)
    trace_reasons = Counter()
    mode = Counter()
    for frame, rows in sorted(by_frame.items()):
        summaries = [e for e in rows if e.get("type") == "selection_summary"]
        raw = next((e for e in rows if e.get("type") == "ocr_raw"), None)
        if raw is not None and isinstance(raw.get("backend"), str):
            mode[raw["backend"]] += 1
        for e in rows:
            if e.get("type") == "ocr_stage_done":
                stage_time[str(e.get("stage", "unknown"))].append(e.get("elapsed_ms"))
        if not summaries:
            tally["frames_without_selection_summary"] += 1
            continue
        summary = summaries[-1]
        raw_n = summary.get("raw_rows")
        selected_n = summary.get("selected_bands")
        if type(raw_n) is not int or type(selected_n) is not int:
            tally["frames_with_invalid_summary"] += 1
            continue
        tally["frames_with_selection_summary"] += 1
        if raw_n == 0:
            tally["raw_empty"] += 1
        if selected_n:
            tally["has_selection"] += 1
        if raw_n > 0 and selected_n == 0:
            tally["raw_positive_selected_empty"] += 1
            reasons = Counter(str(e.get("stage", "")) + "/" + str(e.get("reason", ""))
                              for e in rows if e.get("type") == "selection_trace")
            trace_reasons.update(reasons)
            if len(examples) < max_examples:
                examples.append({
                    "frame_id": frame,
                    "raw_rows": raw_n,
                    "selected_bands": selected_n,
                    "trace_reason_counts": dict(sorted(reasons.items())),
                    "has_refinement": any(e.get("type") == "ocr_refined" for e in rows),
                    "has_strip": any(e.get("type") == "ocr_strip_recovery" for e in rows),
                })
        elif selected_n > 0 and not any(e.get("type") == "speech_decision" for e in rows):
            tally["selected_no_speech_decision_this_frame"] += 1

    return {
        "schema": 1,
        "frame_summary": dict(sorted(tally.items())),
        "raw_positive_selected_empty_examples": examples,
        "reason_counts_on_raw_positive_selected_empty": dict(sorted(trace_reasons.items())),
        "all_event_counts": dict(sorted(global_counts.items())),
        "raw_backend_frames": dict(sorted(mode.items())),
        "ocr_elapsed_ms": {k: quantiles_ms(v) for k, v in sorted(stage_time.items())},
        "p3_shadow_candidates": sum(e.get("type") == "rescue_shadow"
                                    and e.get("would_rescue") is True for e in events),
        "p4_shadow_skip_candidates": sum(e.get("type") == "skip_shadow"
                                         and e.get("would_skip") is True for e in events),
        "p4_candidate_hides_selected_change": sum(
            e.get("type") == "skip_shadow_outcome"
            and e.get("would_skip") is True
            and e.get("selected_text_changed") is True for e in events),
        "p4_comparison_cost_ms": quantiles_ms([
            e.get("elapsed_ms") for e in events if e.get("type") == "skip_shadow"]),
        "p3_probe_cost_ms": quantiles_ms([
            e.get("elapsed_ms") for e in events if e.get("type") == "rescue_shadow"]),
        "limitations": [
            "Raw-positive/selected-empty is an observation, not proof that raw OCR was correct.",
            "Each reason is a traced decision, not an established root cause.",
            "A frame with a valid selected band may still be delayed by stabilizer or TTS.",
            "A hypothetical skip/rescue does not equal an actually saved OCR call or recovered subtitle.",
            "Recording is opt-in; unrecorded, pruned or dropped events are not measured.",
            "Compare independently labeled events and Galaxy S25 metrics before production changes.",
        ],
    }


def read_zip(path):
    with zipfile.ZipFile(path) as archive:
        events = [json.loads(line) for line in
                  archive.read("events.jsonl").decode("utf-8").splitlines() if line.strip()]
    return events


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diagnostic_zip", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = analyze(read_zip(args.diagnostic_zip))
    serialized = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(serialized, encoding="utf-8")
    else:
        print(serialized, end="")


if __name__ == "__main__":
    main()
