#!/usr/bin/env python3
"""Summarize opt-in diagnostic ZIP without copying recognized text or frames."""
import argparse
import json
import math
import statistics
import zipfile
from collections import Counter

MODES = {"hybrid": "A", "v6_both": "D", "v6_rec": "B", "v6_det": "C"}

def stats(numbers):
    if not numbers:
        return {"count": 0, "median_ms": None, "p95_ms": None}
    numbers = sorted(numbers)
    return {"count": len(numbers), "median_ms": statistics.median(numbers),
            "p95_ms": numbers[math.ceil(len(numbers) * .95) - 1]}

def analyze(events, manifest):
    modes = sorted({e.get("ocr_mode") for e in events
                    if e.get("ocr_mode") in MODES})
    per_mode = {}
    for mode in modes:
        done = [e for e in events if e.get("type") == "frame_done"
                and e.get("ocr_mode") == mode
                and type(e.get("processing_ms")) is int and e["processing_ms"] >= 0]
        done.sort(key=lambda e: e.get("mono_ms", 0))
        durations = [e["processing_ms"] for e in done]
        polls = [e.get("state") for e in events if
                 e.get("type") == "capture_poll" and e.get("ocr_mode") == mode]
        per_mode[mode] = {
            "letter": MODES[mode],
            "capture_requested_polls": polls.count("requested") if polls else None,
            "busy_skip_polls": polls.count("busy_skip") if polls else None,
            "frames_captured": sum(e.get("type") == "video_frame" and
                                   e.get("ocr_mode") == mode for e in events),
            "first_completed_ms": durations[0] if durations else None,
            "all_completed": stats(durations),
            "excluding_first_completed": stats(durations[1:]),
        }
    times = {}
    start_delays = []
    counts = Counter(e.get("type") for e in events)
    for e in events:
        uid, at = e.get("utterance_id"), e.get("mono_ms")
        if not isinstance(uid, str) or type(at) is not int:
            continue
        if e.get("type") == "tts_request":
            times.setdefault(uid, at)
        elif e.get("type") == "tts_start" and uid in times and at >= times[uid]:
            start_delays.append(at - times.pop(uid))
    return {"schema": 1, "modes": per_mode,
            "record_count": len(events),
            "dropped_records_or_images": manifest.get("dropped_records_or_images", "unknown"),
            "tts": {"attributable_to_mode": len(modes) == 1,
                    "request": counts["tts_request"], "on_start": counts["tts_start"],
                    "done": counts["tts_done"], "stop": counts["tts_stop"],
                    "error": counts["tts_error"], "discard": counts["tts_discard"],
                    "request_to_os_on_start": stats(start_delays),
                    "audible_onset": "not_measured"},
            "limitations": [
                "Cold: first completed frame only; not proven engine cold initialization.",
                "Warm: first completed frame excluded; lazy initialization may extend further.",
                "busy_skip is a polling tick, not a dropped decoded video frame.",
                "Screen-visible subtitle time and real audible onset require external observation.",
                "Completed-frame times omit failed/cancelled operations.",
                "TTS totals cannot be attributed to one mode if modes were mixed.",
            ]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diagnostic_zip")
    parser.add_argument("--expected-mode", choices=sorted(MODES))
    parser.add_argument("--output")
    args = parser.parse_args()
    with zipfile.ZipFile(args.diagnostic_zip) as z:
        events = [json.loads(line) for line in
                  z.read("events.jsonl").decode("utf-8").splitlines() if line.strip()]
        manifest = json.loads(z.read("manifest.json")) if "manifest.json" in z.namelist() else {}
    report = analyze(events, manifest)
    if args.expected_mode and list(report["modes"]) != [args.expected_mode]:
        parser.error("Expected a single mode %s; record separate ZIPs" % args.expected_mode)
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
    else:
        print(output, end="")

if __name__ == "__main__":
    main()
