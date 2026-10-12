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


def compare(reference, prediction, min_overlap_ms=80, duration_ms=None):
    if min_overlap_ms <= 0:
        raise ValueError("min_overlap_ms must be positive")
    # Chronological sequence alignment. Maximize the number of temporal pairs,
    # then total IoU. Text does not select its own favorable ground-truth event.
    reference = sorted(reference, key=lambda s: (s['start'], s['end']))
    prediction = sorted(prediction, key=lambda s: (s['start'], s['end']))
    n, m = len(reference), len(prediction)
    scores = [[(0, 0.0)] * (m + 1) for _ in range(n + 1)]
    actions = {}
    for i, ref in enumerate(reference):
        for j, pred in enumerate(prediction):
            choices = [(scores[i][j + 1], 'skip_ref'), (scores[i + 1][j], 'skip_pred')]
            inter = overlap(ref, pred)
            if inter >= min_overlap_ms:
                union = max(ref['end'], pred['end']) - min(ref['start'], pred['start'])
                previous = scores[i][j]
                choices.insert(0, ((previous[0] + 1, previous[1] + inter / union), 'pair'))
            score, action = max(choices, key=lambda item: item[0])
            scores[i + 1][j + 1] = score; actions[i + 1, j + 1] = action
    pairs = []; i, j = n, m
    while i and j:
        action = actions[i, j]
        if action == 'pair': pairs.append((i - 1, j - 1)); i -= 1; j -= 1
        elif action == 'skip_ref': i -= 1
        else: j -= 1
    pairs.reverse()
    paired_ref, paired_pred = {i for i, _ in pairs}, {j for _, j in pairs}
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
    exact = [(i, j) for i, j in pairs if norm(reference[i]['text']) == norm(prediction[j]['text'])]
    raw_norm = lambda s: unicodedata.normalize('NFC', s).replace('\r\n', '\n')
    raw_chars = sum(len(raw_norm(r['text'])) for r in reference)
    raw_edits = sum(edits(raw_norm(reference[i]['text']), raw_norm(prediction[j]['text'])) for i, j in pairs)
    raw_edits += sum(len(raw_norm(r['text'])) for i, r in enumerate(reference) if i not in paired_ref)
    raw_edits += sum(len(raw_norm(p['text'])) for j, p in enumerate(prediction) if j not in paired_pred)
    pair_details = [{'reference_index': i, 'prediction_index': j,
                     'reference_text': reference[i]['text'], 'prediction_text': prediction[j]['text'],
                     'normalized_exact': (i, j) in exact,
                     'start_error_ms': prediction[j]['start'] - reference[i]['start'],
                     'end_error_ms': prediction[j]['end'] - reference[i]['end']} for i, j in pairs]
    multiline = {}
    for lines in (2, 3):
        indices = {i for i, r in enumerate(reference) if len(r['text'].splitlines()) == lines}
        multiline[str(lines)] = {'reference_events': len(indices),
                                'exact_text_events': sum(i in indices for i, _ in exact),
                                'line_structure_exact_events': sum(i in indices and reference[i]['text'].splitlines() == prediction[j]['text'].splitlines() for i, j in pairs)}
    coverage = None
    if duration_ms is not None:
        if duration_ms <= 0: raise ValueError('duration_ms must be positive')
        def intervals(items):
            merged = []
            for item in items:
                start, end = max(0, item['start']), min(duration_ms, item['end'])
                if end <= start: continue
                if merged and start <= merged[-1][1]: merged[-1][1] = max(end, merged[-1][1])
                else: merged.append([start, end])
            return merged
        ri, pi = intervals(reference), intervals(prediction)
        common = sum(max(0, min(b, d) - max(a, c)) for a, b in ri for c, d in pi)
        ref_ms = sum(b - a for a, b in ri); pred_ms = sum(b - a for a, b in pi)
        blank_ms = duration_ms - ref_ms; false_ms = pred_ms - common
        coverage = {'duration_ms': duration_ms, 'reference_caption_ms': ref_ms,
                    'reference_blank_ms': blank_ms, 'predicted_blank_ms': false_ms,
                    'blank_false_positive_time_rate': false_ms / blank_ms if blank_ms else None,
                    'missed_caption_ms': ref_ms - common,
                    'caption_time_recall': common / ref_ms if ref_ms else None,
                    'caveat': 'Time occupancy only; wrong text while a caption is present is scored by CER, not this rate'}
    return {
        "reference_events": len(reference),
        "predicted_events": len(prediction),
        "matched_events": len(pairs),
        "missed_events": len(reference) - len(pairs),
        "extra_events": len(prediction) - len(pairs),
        "possible_duplicate_events": dupes,
        "event_recall": round(len(pairs) / len(reference), 4) if reference else None,
        "event_precision": round(len(pairs) / len(prediction), 4) if prediction else None,
        "normalized_exact_events": len(exact),
        "correct_event_recall": round(len(exact) / len(reference), 4) if reference else None,
        "unmatched_prediction_fraction": round((len(prediction) - len(pairs)) / len(prediction), 4) if prediction else None,
        "raw_nfc_event_cer": round(raw_edits / raw_chars, 4) if raw_chars else None,
        "multiline": multiline,
        "time_coverage": coverage,
        "possible_merged_predictions": sum(sum(overlap(r, p) >= min_overlap_ms for r in reference) > 1 for p in prediction),
        "possible_split_references": sum(sum(overlap(r, p) >= min_overlap_ms for p in prediction) > 1 for r in reference),
        "pairs": pair_details,
        "unmatched_reference_indices": [i for i in range(n) if i not in paired_ref],
        "unmatched_prediction_indices": [j for j in range(m) if j not in paired_pred],
        "reference_chars": ref_chars,
        "character_edits_including_missed_extra": wrong_chars,
        "event_aligned_cer": round(wrong_chars / ref_chars, 4) if ref_chars else None,
        "median_start_error_ms": median(start_errors),
        "median_end_error_ms": median(end_errors),
        "matching_method": f"monotonic DP; temporal overlap >={min_overlap_ms}ms; maximize cardinality then IoU; text-independent",
        "metric_caveats": "event_recall counts temporal matches, not correct text; unmatched outputs are not necessarily background false positives; split/merge flags require review",
        "text_normalization": "NFKC, remove whitespace and Unicode punctuation (NOT Android comparisonKey)",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_srt", type=Path)
    parser.add_argument("predictions", nargs="+", type=Path, help="One or more candidate SRT files")
    parser.add_argument("--out", type=Path, help="Write machine-readable JSON")
    parser.add_argument("--duration-ms", type=int, help="Known video duration; score no-caption time occupancy")
    args = parser.parse_args()
    ref = read_srt(args.reference_srt)
    out = {"reference": str(args.reference_srt), "results": {}}
    for path in args.predictions:
        out["results"][path.stem] = compare(ref, read_srt(path), duration_ms=args.duration_ms)
    body = json.dumps(out, ensure_ascii=False, indent=2)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(body + "\n", encoding="utf-8")
    print(body)


if __name__ == "__main__":
    main()
