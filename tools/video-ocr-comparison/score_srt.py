#!/usr/bin/env python3
"""Offline, dependency-free SRT comparison for a frozen subtitle benchmark.

This scores *timed subtitle events*, not raw frame OCR or TTS audio quality.
"""
import argparse
import json
import re
import unicodedata
from pathlib import Path

TIMESTAMP = re.compile(r"^\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*$")


def parse_time(value):
    m = TIMESTAMP.match(value)
    if not m:
        raise ValueError(f"invalid timestamp: {value!r}")
    h, minute, sec, fraction = m.groups()
    if int(minute) >= 60 or int(sec) >= 60:
        raise ValueError(f"invalid timestamp: {value!r}")
    return ((int(h) * 60 + int(minute)) * 60 + int(sec)) * 1000 + int(fraction.ljust(3, "0"))


def read_srt(path):
    text = Path(path).read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    items = []
    for block in re.split(r"\n\s*\n", text):
        lines = [s.strip() for s in block.splitlines() if s.strip()]
        if not lines:
            continue
        indices = [i for i, s in enumerate(lines) if "-->" in s]
        if len(indices) != 1:
            raise ValueError(f"{path}: subtitle block lacks exactly one timing: {block[:80]!r}")
        i = indices[0]
        left, right = lines[i].split("-->", 1)
        start, end = parse_time(left), parse_time(right.split()[0])
        if end <= start:
            raise ValueError(f"{path}: non-positive subtitle duration")
        value = "\n".join(lines[i + 1:])
        if not value:
            continue
        items.append({"start": start, "end": end, "text": value})
    return sorted(items, key=lambda s: (s["start"], s["end"]))


def norm(s):
    """Declared *approximation* for cross-tool Japanese CER, not Android comparisonKey."""
    s = unicodedata.normalize("NFKC", s)
    return "".join(c for c in s if not c.isspace() and not unicodedata.category(c).startswith("P"))


def edits(a, b):
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        curr = [i]
        for j, y in enumerate(b, 1):
            curr.append(min(prev[j] + 1, curr[-1] + 1, prev[j - 1] + (x != y)))
        prev = curr
    return prev[-1]


def overlap(a, b):
    return max(0, min(a["end"], b["end"]) - max(a["start"], b["start"]))


def compare(reference, prediction, min_overlap_ms=80):
    # One-to-one matching by temporal intersection and text likeness. Unmatched
    # prediction overlaps with a matched reference are counted as duplicate candidates.
    possibilities = []
    for i, ref in enumerate(reference):
        for j, pred in enumerate(prediction):
            inter = overlap(ref, pred)
            if inter < min_overlap_ms:
                continue
            union = max(ref["end"], pred["end"]) - min(ref["start"], pred["start"])
            iou = inter / union
            t1, t2 = norm(ref["text"]), norm(pred["text"])
            sim = 1 - edits(t1, t2) / max(len(t1), len(t2), 1)
            possibilities.append((iou + 0.3 * sim, iou, i, j))
    paired_ref, paired_pred, pairs = set(), set(), []
    for _, _, i, j in sorted(possibilities, reverse=True):
        if i not in paired_ref and j not in paired_pred:
            paired_ref.add(i)
            paired_pred.add(j)
            pairs.append((i, j))
    ref_chars = sum(len(norm(r["text"])) for r in reference)
    wrong_chars = sum(edits(norm(reference[i]["text"]), norm(prediction[j]["text"])) for i, j in pairs)
    wrong_chars += sum(len(norm(r["text"])) for i, r in enumerate(reference) if i not in paired_ref)
    wrong_chars += sum(len(norm(p["text"])) for j, p in enumerate(prediction) if j not in paired_pred)
    dupes = 0
    for j, pred in enumerate(prediction):
        if j in paired_pred:
            continue
        if any(overlap(pred, reference[i]) >= min_overlap_ms and
               edits(norm(pred["text"]), norm(reference[i]["text"])) /
               max(len(norm(pred["text"])), len(norm(reference[i]["text"])), 1) <= 0.2
               for i in paired_ref):
            dupes += 1
    start_errors = sorted(abs(reference[i]["start"] - prediction[j]["start"]) for i, j in pairs)
    end_errors = sorted(abs(reference[i]["end"] - prediction[j]["end"]) for i, j in pairs)
    def median(values):
        n = len(values)
        return (values[(n - 1) // 2] + values[n // 2]) / 2 if n else None
    return {
        "reference_events": len(reference),
        "predicted_events": len(prediction),
        "matched_events": len(pairs),
        "missed_events": len(reference) - len(pairs),
        "extra_events": len(prediction) - len(pairs),
        "possible_duplicate_events": dupes,
        "event_recall": round(len(pairs) / len(reference), 4) if reference else None,
        "event_precision": round(len(pairs) / len(prediction), 4) if prediction else None,
        "reference_chars": ref_chars,
        "character_edits_including_missed_extra": wrong_chars,
        "event_aligned_cer": round(wrong_chars / ref_chars, 4) if ref_chars else None,
        "median_start_error_ms": median(start_errors),
        "median_end_error_ms": median(end_errors),
        "matching_method": "greedy temporal overlap >=80ms; IoU + 0.3*text-similarity",
        "text_normalization": "NFKC, remove whitespace and Unicode punctuation (NOT Android comparisonKey)",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_srt", type=Path)
    parser.add_argument("predictions", nargs="+", type=Path, help="One or more candidate SRT files")
    parser.add_argument("--out", type=Path, help="Write machine-readable JSON")
    args = parser.parse_args()
    ref = read_srt(args.reference_srt)
    out = {"reference": str(args.reference_srt), "results": {}}
    for path in args.predictions:
        out["results"][path.stem] = compare(ref, read_srt(path))
    body = json.dumps(out, ensure_ascii=False, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(body + "\n", encoding="utf-8")
    print(body)


if __name__ == "__main__":
    main()
