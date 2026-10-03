#!/usr/bin/env python3
"""Extract original video frames locally. Ground truth must be entered by a human."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("diagnostics", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "images").mkdir()
frames = []
with zipfile.ZipFile(args.diagnostics) as archive:
    for line in archive.read("events.jsonl").decode("utf-8").splitlines():
        row = json.loads(line)
        if row.get("type") != "video_frame" or not row.get("image"):
            continue
        image = archive.read(row["image"])
        name = str(len(frames)) + ".png"
        (args.output / "images" / name).write_bytes(image)
        frames.append({"id": str(row["frame_id"]), "mono_ms": row["mono_ms"],
                       "image": "images/" + name, "sha256": hashlib.sha256(image).hexdigest(),
                       "expected_text": None})
if not frames:
    raise SystemExit("No saved original video_frame images. Old-path ZIPs are unsupported.")
frames.sort(key=lambda row: row["mono_ms"])
(args.output / "dataset.json").write_text(json.dumps({
    "id": args.diagnostics.stem, "source": "diagnostic_original_video_frames",
    "human_verified": False, "split": "unassigned", "frames": frames
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Extracted {len(frames)} original frames. Manually enter expected_text before evaluation.")
