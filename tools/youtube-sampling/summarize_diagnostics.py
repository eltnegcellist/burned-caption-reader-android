#!/usr/bin/env python3
"""Read opt-in on-device diagnostic ZIP; publish numeric summaries only.

Never serializes frame images, YouTube URLs, video IDs or OCR/TTS text.
TTS onStart != audible sound onset. No video-file or private stream access.
"""
import argparse
import json
import math
import statistics
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

MEASURES = {
    "trigger_to_pixelcopy_ready_ms": ("sampling_captured", "trigger_to_frame_ms"),
    "pixelcopy_ms": ("sampling_captured", "pixelcopy_ms"),
    "ocr_ms": ("sampling_ocr_completed", "ocr_elapsed_ms"),
    "ocr_to_tts_request_ms": ("sampling_tts_requested", "since_ocr_start_ms"),
    "ocr_to_tts_onstart_ms": ("sampling_tts_onstart", "since_ocr_start_ms"),
}

def percentile(data, quantile):
    data = sorted(data)
    return data[max(0, math.ceil(len(data) * quantile)-1)] if data else None

def stats(data):
    return {"n": len(data), "median_ms": round(statistics.median(data), 2) if data else None,
            "p95_ms": percentile(data, .95)}

def analyze(records):
    groups = defaultdict(list)
    counts = Counter()
    mode = None
    tts_requested = {}
    for item in records:
        kind = item.get("type")
        counts[kind] += 1
        if kind == "sampling_experiment":
            mode = item.get("mode")
        for name,(etype,key) in MEASURES.items():
            if kind == etype:
                v = item.get(key)
                if isinstance(v, (int,float)) and v >= 0:
                    groups[name].append(v)
        if kind == "tts_request" and item.get("utterance_id"):
            tts_requested[item["utterance_id"]] = item.get("mono_ms")
        if kind == "tts_start" and item.get("utterance_id") in tts_requested:
            start=tts_requested[item["utterance_id"]]
            end=item.get("mono_ms")
            if isinstance(start,(int,float)) and isinstance(end,(int,float)) and end>=start:
                groups["tts_request_to_onstart_ms"].append(end-start)
    order_releases=[r for r in records if r.get("type") == "sampling_order_release"]
    order_held_ms=[r["held_ms"] for r in order_releases
                   if isinstance(r.get("held_ms"), (int,float)) and r["held_ms"]>=0]
    blocked_held_ms=[r["held_ms"] for r in order_releases
                     if r.get("upper_blocked") is True
                     and isinstance(r.get("held_ms"),(int,float)) and r["held_ms"]>=0]
    order_wait_ms=next((r.get("order_wait_ms") for r in records
                        if r.get("type") == "sampling_experiment"), None)
    busy = next((item.get("frame_busy_skips") for item in reversed(records)
                 if item.get("type") == "sampling_stats"), None)
    throttle = next((item.get("frame_throttle_skips") for item in reversed(records)
                 if item.get("type") == "sampling_stats"), None)
    return {
       "mode":mode,
       "order_max_wait_ms":order_wait_ms,
       "raw_events":len(records),
       "captured_frames":counts["sampling_captured"],
       "completed_ocr_frames":counts["sampling_ocr_completed"],
       "tts_requests":counts["tts_request"],
       "tts_starts":counts["tts_start"],
       "periodic_busy_skip_polls":sum(1 for r in records
          if r.get("type") == "capture_poll" and r.get("state") == "busy_skip"),
       "video_frame_signals_accepted":counts["sampling_video_frame"],
       "frame_signals_busy_skipped":busy,
       "frame_signals_throttled":throttle,
       "order_released_total":len(order_releases),
       "order_released_after_deadline":sum(r.get("reason")=="deadline" for r in order_releases),
       "order_released_on_upper_cleared":sum(r.get("reason")=="upper_cleared" for r in order_releases),
       "order_released_without_upper_wait":sum(r.get("reason")=="no_upper" for r in order_releases),
       "order_pending_snapshots":counts["sampling_order_pending"],
       "metrics":{**{key:stats(groups[key]) for key in [*MEASURES,"tts_request_to_onstart_ms"]},
                  "order_buffer_held_ms":stats(order_held_ms),
                  "order_buffer_blocked_held_ms":stats(blocked_held_ms)},
       "not_measured":["true_on_screen_subtitle_onset","audible_TTS_onset",
                       "Galaxy_S25_latency_if_not_run_on_device"],
       "caution":"TTS onStart is an engine callback, not microphone-confirmed audible onset."
    }

def main():
    p=argparse.ArgumentParser()
    p.add_argument("diagnostic_zip",type=Path)
    p.add_argument("--out",type=Path,help="Optional numeric JSON output (no text/frames)")
    a=p.parse_args()
    with zipfile.ZipFile(a.diagnostic_zip) as z:
        events=[json.loads(l) for l in z.read("events.jsonl").splitlines() if l.strip()]
    result=analyze(events)
    serialized=json.dumps(result,ensure_ascii=False,indent=2)
    if a.out:
        a.out.parent.mkdir(parents=True,exist_ok=True)
        a.out.write_text(serialized+"\n",encoding="utf-8")
    print(serialized)

if __name__=="__main__":
    main()
