#!/usr/bin/env python3
"""Offline smoke tests for diagnostic ZIP A/D summarizer."""
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ad_analyzer", HERE / "analyze.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def event(kind, t, **fields):
    return {"type": kind, "mono_ms": t, **fields}


class AnalyzerTest(unittest.TestCase):
    def test_single_mode_and_start_delay(self):
        rows = [
            event("session_start", 1),
            event("capture_poll", 10, ocr_mode="v6_both", state="requested"),
            event("video_frame", 11, ocr_mode="v6_both"),
            event("frame_done", 20, ocr_mode="v6_both", processing_ms=320),
            event("capture_poll", 21, ocr_mode="v6_both", state="busy_skip"),
            event("frame_done", 30, ocr_mode="v6_both", processing_ms=150),
            event("frame_done", 40, ocr_mode="v6_both", processing_ms=200),
            event("tts_request", 50, utterance_id="one", text="PRIVATE"),
            event("tts_start", 125, utterance_id="one"),
            event("tts_done", 150, utterance_id="one"),
            event("session_stop", 160),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "sample.zip"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr("events.jsonl", "\n".join(json.dumps(r) for r in rows))
                z.writestr("manifest.json", '{"dropped_records_or_images": 0}')
            events, manifest = mod.read_zip(p) if hasattr(mod, "read_zip") else (
                [json.loads(line) for line in zipfile.ZipFile(p).read("events.jsonl").decode().splitlines()],
                json.loads(zipfile.ZipFile(p).read("manifest.json")))
            result = mod.analyze(events, manifest)
        d = result["modes"]["v6_both"]
        self.assertEqual(d["capture_requested_polls"], 1)
        self.assertEqual(d["busy_skip_polls"], 1)
        self.assertEqual(d["first_completed_ms"], 320)
        self.assertEqual(d["excluding_first_completed"]["median_ms"], 175)
        self.assertEqual(d["excluding_first_completed"]["p95_ms"], 200)
        self.assertEqual(result["tts"]["request_to_os_on_start"]["median_ms"], 75)
        self.assertEqual(result["tts"]["audible_onset"], "not_measured")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_mixed_modes_not_attributable(self):
        rows = [
            event("frame_done", 100, ocr_mode="hybrid", processing_ms=30),
            event("frame_done", 200, ocr_mode="v6_both", processing_ms=90),
            event("tts_request", 210, utterance_id="x"),
            event("tts_start", 220, utterance_id="x"),
        ]
        result = mod.analyze(rows, {})
        self.assertEqual(set(result["modes"]), {"hybrid", "v6_both"})
        self.assertFalse(result["tts"]["attributable_to_mode"])
        self.assertEqual(result["modes"]["hybrid"]["excluding_first_completed"]["count"], 0)

    def test_empty(self):
        report = mod.analyze([], {})
        self.assertEqual(report["modes"], {})
        self.assertEqual(report["tts"]["request_to_os_on_start"]["count"], 0)


if __name__ == "__main__":
    unittest.main()
