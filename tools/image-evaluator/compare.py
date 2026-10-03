#!/usr/bin/env python3
"""Compare raw OCR results only when input and reference are identical."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("baseline", type=Path)
parser.add_argument("candidate", type=Path)
args = parser.parse_args()
baseline, candidate = [json.loads(path.read_text(encoding="utf-8")) for path in (args.baseline, args.candidate)]
for field in ("input_manifest_sha256", "dataset", "source", "scope", "reference_characters", "device_serial"):
    if baseline[field] != candidate[field]:
        parser.error("Cannot compare different " + field)
if len(baseline["frames"]) != len(candidate["frames"]):
    parser.error("Different frame count")
for before, after in zip(baseline["frames"], candidate["frames"]):
    if any(before[key] != after[key] for key in ("id", "mono_ms", "expected_text")):
        parser.error("Frame/reference mismatch")
print(json.dumps({
    "scope": baseline["scope"], "source": baseline["source"],
    "baseline_label": baseline["label"], "candidate_label": candidate["label"],
    "baseline_cer": baseline["cer"], "candidate_cer": candidate["cer"],
    "edit_distance_delta": candidate["edit_distance"] - baseline["edit_distance"],
    "changed_frames": [{"id": after["id"], "baseline_text": before["ocr_text"],
                        "candidate_text": after["ocr_text"],
                        "edit_distance_delta": after["edit_distance"] - before["edit_distance"]}
                       for before, after in zip(baseline["frames"], candidate["frames"])
                       if before["ocr_text"] != after["ocr_text"]],
}, ensure_ascii=False, indent=2))
