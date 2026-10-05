#!/usr/bin/env python3
"""Synthetic harness control, never a real-video quality benchmark (requires Pillow)."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
parser.add_argument("--font", required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "images").mkdir()
font = ImageFont.truetype(args.font, 44)
frames = []
for index, text in enumerate([
    "本日の価格は100円です\nこのサービスを利用できます\n字幕は上から読みます",
    "本日の価格は200円です\nこのサービスを利用できません\n字幕は上から読みます",
]):
    image = Image.new("RGB", (1100, 620), (32, 44, 60))
    draw = ImageDraw.Draw(image)
    for row, value in enumerate(text.splitlines()):
        draw.text((100, 350 + row * 64), value, font=font, fill="white", stroke_width=2, stroke_fill="black")
    name = f"images/{index}.png"
    image.save(args.output / name)
    frames.append({"id": f"synthetic-{index}", "mono_ms": index * 480,
                   "image": name, "expected_text": text,
                   "sha256": hashlib.sha256((args.output / name).read_bytes()).hexdigest()})
(args.output / "dataset.json").write_text(json.dumps({
    "id": "synthetic-three-lines-number-negation", "source": "synthetic",
    "ground_truth_source": "synthetic_render_spec", "human_verified": False,
    "split": "harness_control", "frames": frames,
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
