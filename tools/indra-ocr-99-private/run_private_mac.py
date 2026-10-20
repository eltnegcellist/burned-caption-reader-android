#!/usr/bin/env python3
"""Reproducible private-Mac comparison of Indra OCR 1.1.2 and archived E replay.

Intended for the exact PR #55 99 retained video frames. This is a research-only
full-image Indra-vs-E-produced-captions comparison, not a new app OCR engine.
Never writes images or their transcripts to the public GitHub repository.
"""
import argparse
import collections
import hashlib
import importlib.metadata
import json
import os
import re
import statistics
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

EXPECTED = {
    "dev-cat": ("c927d62f79814a626c347c1ab74a07a3372f75e3591926bbb6b07d56856469d4", 28, 29),
    "dev-watch": ("15bcd55d7105a95ac14f97f324d810c4eb76242b821b64fd45af3e2b7b7e7d53", 28, 48),
    "heldout": ("b6d1a41f1b6f0f418314febd4db6d05cb0c2e41314fab23de28dfb748c771c17", 43, 103),
}
NAMES = list(EXPECTED)
MODES = ["indra_fixed_upright", "indra_auto_orientation"]
REMOVE_MARKS = re.compile(r"[\u200b-\u200d\ufeff]")
REMOVE_PUNCT = re.compile(r"[、。,.，．・…⋯‥︙:：;；!?！？「」『』（）()\[\]【】]")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for piece in iter(lambda: f.read(1024 * 1024), b""):
            h.update(piece)
    return h.hexdigest()


def comparison_key(value):
    """Port the exact SubtitleNormalizer.comparisonKey normalization sequence."""
    if value is None or not str(value).strip():
        return ""
    text = unicodedata.normalize("NFKC", str(value))
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    for original in ("⋯", "‥", "︙", "⋮"):
        text = text.replace(original, "…")
    text = REMOVE_MARKS.sub("", text)
    kept = []
    for line in text.split("\n"):
        line = re.sub(r"[\t \u3000]+", "", line)
        line = re.sub(r"[~～]+", "〜", line)
        line = re.sub(r"^[|｜]+|[|｜]+$", "", line)
        line = re.sub(r"[.．・･·•‧∙⋅◦…]{2,}", "…", line)
        line = re.sub(r"…{2,}", "…", line)
        line = re.sub(r"。{2,}", "。", line)
        line = re.sub(r"、{2,}", "、", line)
        line = line.strip()
        if line:
            kept.append(line)
    text = "\n".join(kept)
    text = re.sub(r"[。．.]$", "。", text)
    return REMOVE_PUNCT.sub("", text.replace("\n", "")).lower()


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, c in enumerate(a, 1):
        curr = [i]
        for j, d in enumerate(b, 1):
            curr.append(min(curr[-1] + 1, prev[j] + 1, prev[j - 1] + (c != d)))
        prev = curr
    return prev[-1]


def frame_score(truth, prediction):
    a, b = comparison_key(truth), comparison_key(prediction)
    return {"edits": edit_distance(a, b), "chars": len(a),
            "exact": a == b, "false_positive_blank": not a and bool(b),
            "complete_miss": bool(a) and not b}


def assert_our_normalizer():
    checks = [
        (" ＡＢＣ １２３。\nテスト⋯", "abc123テスト"),
        ("普通です\n次の行です。", "普通です次の行です"),
        ("...、。", ""),
        ("〜 １２：３０", "〜1230"),
    ]
    for given, expected in checks:
        got = comparison_key(given)
        if got != expected:
            raise AssertionError("Normalizer self check: {!r} => {!r} != {!r}".format(
                given, got, expected))


def refuse_inside_repo(output_dir):
    repo_root = Path(__file__).resolve().parents[2]
    try:
        output_dir.resolve().relative_to(repo_root)
    except ValueError:
        return
    raise ValueError("PRIVATE OUTPUT REQUIRED outside repo: " + str(output_dir))


def load_dataset_and_e(base, e_results):
    datasets = {}
    records = []
    total_edits = total_chars = 0
    e_builds = set()
    for name in NAMES:
        root = base / name
        manifest_path = root / "dataset.json"
        if not manifest_path.is_file():
            raise FileNotFoundError("Missing original private manifest: " + str(manifest_path))
        expected_hash, expected_n, expected_edits = EXPECTED[name]
        manifest_hash = sha256(manifest_path)
        if manifest_hash != expected_hash:
            raise ValueError("Manifest drift in {}: {} != {}".format(
                name, manifest_hash, expected_hash))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        frames = manifest["frames"]
        if len(frames) != expected_n:
            raise ValueError("Unexpected {} frame count {}".format(name, len(frames)))
        e_path = e_results / "{}-e_mlkit_v6-1.json".format(name)
        if not e_path.is_file():
            raise FileNotFoundError("Archived E pass1 not present: " + str(e_path))
        e = json.loads(e_path.read_text(encoding="utf-8"))
        if e.get("ocr_mode") != "e_mlkit_v6":
            raise ValueError("Not E mode: " + str(e_path))
        if e.get("input_manifest_sha256") != manifest_hash:
            raise ValueError("E result input manifest mismatch: " + name)
        if len(e["frames"]) != expected_n:
            raise ValueError("E result frame count mismatch: " + name)
        e_builds.add(e.get("apk_sha256"))
        edits_for_dataset = 0
        for original, erow in zip(frames, e["frames"]):
            for field in ("id", "mono_ms", "expected_text"):
                if original[field] != erow[field]:
                    raise ValueError("{} mismatch for {} at {}".format(field, name, original["id"]))
            img_path = (root / original["image"]).resolve()
            if not img_path.is_relative_to(root.resolve()):
                raise ValueError("Escaping dataset path: " + str(img_path))
            if not img_path.is_file() or sha256(img_path) != original["sha256"]:
                raise ValueError("Image missing or changed: " + str(img_path))
            e_score = frame_score(original["expected_text"], erow["ocr_text"])
            if e_score["edits"] != erow["edit_distance"] or e_score["chars"] != erow["reference_characters"]:
                raise ValueError("Normalizer/E-replay scoring discrepancy in {}:{}".format(name, original["id"]))
            record = {
                "dataset": name, "id": original["id"], "image_path": img_path,
                "sha256": original["sha256"], "expected_text": original["expected_text"],
                "mono_ms": original["mono_ms"], "e_text": erow["ocr_text"],
                "e_score": e_score, "e_android_emulator_ms": erow.get("ocr_processing_ms"),
            }
            records.append(record)
            edits_for_dataset += e_score["edits"]
            total_edits += e_score["edits"]
            total_chars += e_score["chars"]
        if edits_for_dataset != expected_edits:
            raise ValueError("E score drift for {}: {} != {}".format(
                name, edits_for_dataset, expected_edits))
        datasets[name] = {"manifest_sha256": manifest_hash, "E_result_sha256": sha256(e_path),
                          "frames": len(frames), "E_edits": edits_for_dataset,
                          "E_apk_sha256": e.get("apk_sha256")}
    if len(records) != 99 or total_edits != 180 or total_chars != 2545:
        raise ValueError("E baseline must reproduce 180/2545 on 99 frames, got {}/{} on {}".format(
            total_edits, total_chars, len(records)))
    if len(e_builds) != 1:
        raise ValueError("Different E APK hashes among datasets; reject mixed E runs")
    return datasets, records


def load_labels(labels_path):
    if labels_path is None:
        return {}
    data = json.loads(labels_path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("Expected labels JSON list")
    out = {}
    for entry in data:
        key = (entry["dataset"], entry["id"])
        if key in out:
            raise ValueError("Duplicate label: " + str(key))
        out[key] = entry
    return out


def measure_indra(records, threads, repeat):
    import numpy as np
    from PIL import Image
    import indra_ocr
    version = importlib.metadata.version("indra-ocr")
    if version != "1.1.2":
        raise RuntimeError("Pinned Indra version required: 1.1.2, found " + version)
    start = time.perf_counter()
    conv = indra_ocr.load(threads=threads)
    loaded_ms = round(1000 * (time.perf_counter() - start), 2)
    by_key = {r["dataset"] + "/" + r["id"]: {m: [] for m in MODES} for r in records}
    for iteration in range(repeat):
        order = records if iteration % 2 == 0 else list(reversed(records))
        for row in order:
            with Image.open(row["image_path"]) as img:
                gray = np.asarray(img.convert("L"), dtype=np.uint8)
            # Use one exact image for both approaches; change only orientation handling.
            modes = MODES if iteration % 2 == 0 else list(reversed(MODES))
            for mode in modes:
                start = time.perf_counter()
                page = conv.ocr(gray, turn=False) if mode == "indra_fixed_upright" else conv.ocr(gray)
                ms = (time.perf_counter() - start) * 1000
                line_texts = [str(getattr(line, "text", "")) for line in page.lines]
                pred = "".join(line_texts)
                by_key[row["dataset"] + "/" + row["id"]][mode].append({
                    "iteration": iteration + 1,
                    "processing_ms": round(ms, 2),
                    "rotation": getattr(page, "rotation", None),
                    "score": frame_score(row["expected_text"], pred),
                    "text": pred,
                    "lines": line_texts,
                })
        print("Completed Indra iteration {} / {}".format(iteration+1, repeat), flush=True)
    return version, loaded_ms, by_key


def quantile_rank(xs, q):
    if not xs:
        return None
    xs = sorted(xs)
    import math
    return xs[max(0, math.ceil(q * len(xs)) - 1)]


def summarize(records, answers, labels):
    groups = collections.defaultdict(list)
    for row in records:
        for group in ("ALL", row["dataset"]):
            groups[(group, "E")].append((row["e_score"], None))
        key = row["dataset"] + "/" + row["id"]
        for mode in MODES:
            runs = answers[key][mode]
            groups[("ALL", mode)].append((runs[0]["score"], runs))
            groups[(row["dataset"], mode)].append((runs[0]["score"], runs))
        label = labels.get((row["dataset"], row["id"]))
        if label:
            nrows = label.get("gt_caption_row_count")
            if isinstance(nrows, int):
                category = "rows=" + str(min(nrows, 4))
                for g in ("E", *MODES):
                    scored = (row["e_score"], None) if g == "E" else (answers[key][g][0]["score"], answers[key][g])
                    groups[(category, g)].append(scored)
            has_bg = label.get("noncaption_text_visible", "unknown")
            for g in ("E", *MODES):
                scored = (row["e_score"], None) if g == "E" else (answers[key][g][0]["score"], answers[key][g])
                groups[("noncaption=" + str(has_bg), g)].append(scored)
    tables = []
    for (group, mode), rows in groups.items():
        edits = sum(score["edits"] for score, _ in rows)
        chars = sum(score["chars"] for score, _ in rows)
        samples = [run["processing_ms"] for _, runs in rows if runs for run in runs[1:]]
        if not samples:  # one sample per frame, no warm interpretation if repeats<2
            samples = [run["processing_ms"] for _, runs in rows if runs for run in runs]
        tables.append({
            "group": group, "mode": mode, "frames": len(rows), "chars": chars,
            "edits": edits, "cer": round(edits/chars, 8) if chars else None,
            "exact_frames": sum(s["exact"] for s, _ in rows),
            "complete_misses": sum(s["complete_miss"] for s, _ in rows),
            "false_positives_on_blank": sum(s["false_positive_blank"] for s, _ in rows),
            "mac_native_indra_warm_median_ms": round(statistics.median(samples),2) if samples else None,
            "mac_native_indra_warm_p95_ms": round(quantile_rank(samples,.95),2) if samples else None,
        })
    return tables


def build_report(inputs, records, by_key, tables, version, load_ms, args, labels):
    private_rows = []
    for row in records:
        k = row["dataset"] + "/" + row["id"]
        modes = {}
        for mode, results in by_key[k].items():
            modes[mode] = results
        private_rows.append({
            "dataset":row["dataset"],"id":row["id"],"image_sha256":row["sha256"],
            "gt":row["expected_text"],"e_text":row["e_text"],"e_score":row["e_score"],
            "e_android_emulator_ms":row["e_android_emulator_ms"],"indra":modes,
            "labels": labels.get((row["dataset"],row["id"])),
        })
    status = {"Indra_version":version,"model_load_ms":load_ms,"threads":args.threads,
        "repeat":args.repeat,"platform":sys.platform,"cpu_count":os.cpu_count(),
        "data_origin":"PR33 99 original video frames locally retained; sha256 verified",
        "E_provenance":"archived PR55 pass1 Android emulator replay; sha256 and CER verified",
        "reason_latency_not_comparable":"Indra native Mac full-page OCR and E prior Android emulator multi-stage caption selection",
        "image_upload_policy":"No frames, transcripts, GT, or model binaries in public repository"}
    report={"provenance":status,"datasets":inputs,"table":tables,"frames_PRIVATE":private_rows}
    return report


def report_md(report):
    lines = [
        "# Indra OCR 1.1.2 vs 現行E：99枚の同一実動画フレーム比較",
        "",
        "**注意：これはEアプリの字幕選別済み結果と、Indraの全画面OCRテキストの比較であり、Indraを搭載したアプリの精度ではありません。**",
        "Eの速度はAndroidエミュレーターで保存された過去計測、Indraの速度はMacネイティブでの新計測のため倍率を比較できません。",
        "",
        "|対象|方式|フレーム|正解文字|編集距離|CER|完全一致|字幕全欠落|空画面誤出力|Indra Mac中央値/p95 ms|",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in report["table"]:
        if r["group"] not in ("ALL", "dev-cat", "dev-watch", "heldout"):
            continue
        lat = ("{:.1f}/{:.1f}".format(r["mac_native_indra_warm_median_ms"],r["mac_native_indra_warm_p95_ms"])
            if r["mac_native_indra_warm_median_ms"] is not None else "比較対象外")
        cer = ("{:.2%}".format(r["cer"]) if r["cer"] is not None else "N/A")
        lines.append("|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|".format(
            r["group"],r["mode"],r["frames"],r["chars"],r["edits"],cer,r["exact_frames"],
            r["complete_misses"],r["false_positives_on_blank"],lat))
    lines += ["","## 安全性と限界","","- GTは過去のCodex画像転記であり人手監査未完了。",
        "- 画像の同一性と既存E=180/2545を実行前に厳密検証した。",
        "- Indra標準は180°誤回転のリスクがあるため、正立既知の turn=False を別測定。",
        "- 字幕の読み上げ適性は名札除外、字幕表示時間、TTS遅延の評価が別途必要。",
        "- 今回の既知99枚は検証開発に使用済み。独立した未使用動画で再確認が必要。",
        "- 本レポートは私的作業フォルダにのみ保存。公開PRに全文・画像・パス・モデルはアップロードしない。"]
    return "\n".join(lines)+"\n"


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--datasets",type=Path,required=True,
                        help="Parent containing dev-cat, dev-watch, heldout")
    parser.add_argument("--e-results",type=Path,required=True,
                        help="Existing private PR55 result folder; files are <dataset>-e_mlkit_v6-1.json")
    parser.add_argument("--out",type=Path,required=True,
                        help="Private output folder outside Git working tree")
    parser.add_argument("--labels",type=Path,help="Optional private/strata-labels.json")
    parser.add_argument("--threads",type=int,default=4)
    parser.add_argument("--repeat",type=int,default=3)
    parser.add_argument("--validate-only",action="store_true",
                        help="Verify existing E and 99 images without loading Indra")
    args=parser.parse_args()
    if args.repeat<2 or args.threads<1 or args.threads>16:
        parser.error("Need >=2 repeats and 1..16 threads")
    assert_our_normalizer()
    refuse_inside_repo(args.out)
    data,frames=load_dataset_and_e(args.datasets.expanduser().resolve(),args.e_results.expanduser().resolve())
    print("INPUT GATE PASS: {} frames; frozen E edit distance 180/2545 (7.07%).".format(len(frames)),flush=True)
    if args.validate_only:
        print("Validation only: no inference was run.",flush=True)
        return
    labels=load_labels(args.labels) if args.labels else {}
    version,ms,answers=measure_indra(frames,args.threads,args.repeat)
    table=summarize(frames,answers,labels)
    report=build_report(data,frames,answers,table,version,ms,args,labels)
    args.out.mkdir(parents=True,exist_ok=True)
    (args.out/"indra-e-99-PRIVATE.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    (args.out/"indra-e-99-PRIVATE.md").write_text(report_md(report),encoding="utf-8")
    print(report_md(report),flush=True)
    print("PRIVATE REPORT:",str((args.out/"indra-e-99-PRIVATE.md").resolve()),flush=True)


if __name__=="__main__":
    main()
