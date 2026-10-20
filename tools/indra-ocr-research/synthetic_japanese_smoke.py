#!/usr/bin/env python3
"""Research-only Indra OCR v1.1 synthetic Japanese subtitle smoke.
No model data, original video, or private caption imagery enters Git.
This test is NOT a product E-vs-Indra benchmark (different hardware/inputs).
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
import statistics
import tempfile
import time
import unicodedata
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def normalize(x):
    return "".join(
        c.lower() for c in unicodedata.normalize("NFKC", str(x))
        if c not in " \n\r\t　、。，．・…⋯‥︙:：;；!?！？「」『』（）()[]【】"
    )


def distance(a, b):
    a, b = normalize(a), normalize(b)
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        new = [i]
        for j, y in enumerate(b, 1):
            new.append(min(new[-1] + 1, row[j] + 1, row[j - 1] + (x != y)))
        row = new
    return row[-1], len(a)


def make_cases(font_path, folder):
    cases = [
        ("one_line", ["今日はいい天気ですね"], [], 42),
        ("three_lines", ["字幕の読み取りを試しています", "三行でも順番が正しいか", "最後の行まで読めるでしょうか"], [], 37),
        ("nameplate", ["明日は学校に行きません"], ["山田 太郎", "解説者"], 41),
        ("small_caption", ["読み取り結果を確認します"], [], 25),
        ("blank", [], [], 40),
    ]
    prepared = []
    for case_id, captions, other, size in cases:
        frame = Image.new("L", (1100, 618), 90)
        draw = ImageDraw.Draw(frame)
        # A dark-to-mid-gray background approximates video imagery without source footage.
        for y in range(618):
            if y % 37 < 3:
                draw.line((0, y, 1099, y), fill=97, width=2)
        if captions:
            height = len(captions) * (size + 17) + 15
            top = max(200, 618 - height - 35)
            draw.rounded_rectangle((40, top, 1060, 594), 14, fill=17)
            font = ImageFont.truetype(str(font_path), size)
            for j, line in enumerate(captions):
                bbox = draw.textbbox((0, 0), line, font=font)
                w = bbox[2] - bbox[0]
                draw.text(((1100 - w) // 2, top + 10 + j * (size + 17)),
                          line, font=font, fill=255, stroke_width=1, stroke_fill=220)
        if other:
            font_other = ImageFont.truetype(str(font_path), 35)
            draw.rounded_rectangle((40, 32, 310, 132), 10, fill=12)
            for j, line in enumerate(other):
                draw.text((54, 42 + j * 45), line, font=font_other, fill=243)
        path = folder / (case_id + ".png")
        frame.save(path)
        prepared.append((case_id, path, "".join(captions), "".join(other) + "".join(captions)))
    return prepared


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--font", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--repeat", type=int, default=3)
    p.add_argument("--threads", type=int, default=4)
    a = p.parse_args()
    assert a.font.exists(), f"font missing: {a.font}"
    assert a.repeat >= 2
    import indra_ocr
    version = importlib.metadata.version("indra-ocr")
    print("Indra OCR version:", version, flush=True)
    print("Indra CPU threads:", a.threads, flush=True)
    print("Hardware:", os.cpu_count(), os.uname().sysname, os.uname().machine, flush=True)
    tic = time.perf_counter()
    conv = indra_ocr.load(threads=a.threads)
    load_ms = (time.perf_counter() - tic) * 1000
    print("Model load_ms:", round(load_ms, 1), flush=True)
    results = {"provenance": {"indra_version": version, "threads": a.threads,
             "os": os.uname().sysname, "arch": os.uname().machine,
             "cpu_count": os.cpu_count(), "model_load_ms": round(load_ms, 1),
             "data_origin": "synthetic_generated_2026_10_20",
             "not_same_hardware_as_app_e": True,
             "not_a_production_caption_replay": True,
             "not_original_99_frames": True}, "cases": []}
    with tempfile.TemporaryDirectory() as tmp:
        prepared = make_cases(a.font, Path(tmp))
        for case_id, path, caption_gt, all_visible_gt in prepared:
            gray = np.asarray(Image.open(path).convert("L"))
            image_hash = hashlib.sha256(path.read_bytes()).hexdigest()
            outputs, times, stage_timings = [], [], []
            for n in range(a.repeat):
                start = time.perf_counter()
                page = conv.ocr(gray)
                ms = (time.perf_counter() - start) * 1000
                lines = [str(getattr(line, "text", "")) for line in page.lines]
                outputs.append(lines)
                orientation = {"rotation": getattr(page, "rotation", None), "skew": getattr(page, "skew", None), "vertical": getattr(page, "vertical", None), "boxes": [getattr(line, "box", None) for line in page.lines]}
                times.append(ms)
                stage_timings.append(dict(getattr(page, "timings", {}) or {}))
                print(json.dumps({"case": case_id, "iteration": n+1, "ms": round(ms, 1),
                      "detected": lines, "orientation": orientation, "stages": stage_timings[-1]}, ensure_ascii=False),
                      flush=True)
            text = "".join(outputs[-1])
            edits, chars = distance(caption_gt, text)
            edits_all, chars_all = distance(all_visible_gt, text)
            results["cases"].append({"case": case_id, "caption_ground_truth": caption_gt,
                   "all_visible_ground_truth": all_visible_gt,
                   "image_sha256": image_hash,
                   "detected_lines": outputs[-1],
                   "times_ms": [round(t, 2) for t in times],
                   "warm_median_ms": round(statistics.median(times[1:]), 2),
                   "caption_cer": (edits / chars if chars else None),
                   "all_visible_cer": (edits_all / chars_all if chars_all else None),
                   "extra_output_on_blank": case_id == "blank" and bool(normalize(text)),
                   "recognizer_name": "full_indra_ocr_1_1",
                   "stage_timings_last": stage_timings[-1]})
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print("RESULT_FILE", str(a.out), flush=True)
    print("SUMMARY", json.dumps([{"case": x["case"], "caption_cer": x["caption_cer"],
          "all_visible_cer": x["all_visible_cer"], "warm_median_ms": x["warm_median_ms"]}
          for x in results["cases"]], ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
