#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("trial_analyzer", HERE / "compare_trials.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def trial(mode, ms, video="abcdefghijk", device="Samsung S25", change=False):
    events = [
        {"type": "session_start", "device": device},
        {"type": "video_frame", "video_id": video},
        {"type": "frame_done", "ocr_mode": mode, "processing_ms": ms * 2},
        {"type": "frame_done", "ocr_mode": mode, "processing_ms": ms},
        {"type": "frame_done", "ocr_mode": mode, "processing_ms": ms + 1},
        {"type": "ocr_call", "stage": "coarse"},
        {"type": "ocr_stage_done", "stage": "coarse", "elapsed_ms": ms},
        {"type": "skip_shadow_outcome", "would_skip": True,
         "selected_text_changed": change, "text": "PRIVATE_OCR"},
        {"type": "selection_summary", "raw_rows": 2, "selected_bands": 0},
    ]
    return mod.measure(events, {"dropped_records_or_images": 0}, "sample.zip")


class TrialComparisonTest(unittest.TestCase):
    def test_run_level_aggregates_do_not_leak_text(self):
        a = [trial("hybrid", ms) for ms in (100, 110, 120)]
        b = [trial("v6_both", ms) for ms in (70, 80, 90)]
        report = mod.compare(a, b)
        self.assertTrue(report["same_video_verified"])
        self.assertTrue(report["same_device_verified"])
        self.assertTrue(report["three_or_more_runs_each"])
        self.assertEqual(report["baseline"]["aggregate"]["warm_median_ms"]["median_of_run_values"], 110.5)
        self.assertEqual(report["candidate"]["aggregate"]["warm_median_ms"]["median_of_run_values"], 80.5)
        self.assertEqual(report["candidate"]["aggregate"]["shadow_hides_selected_change"]["median_of_run_values"], 0)
        self.assertNotIn("PRIVATE_OCR", json.dumps(report))
        self.assertEqual(report["decision"], "insufficient_for_adoption_without_ground_truth")

    def test_rejects_nonmatching_device_and_video(self):
        with self.assertRaises(ValueError):
            mod.compare([trial("hybrid", 100)], [trial("v6_both", 80, video="other")])
        with self.assertRaises(ValueError):
            mod.compare([trial("hybrid", 100)], [trial("v6_both", 80, device="other")])

    def test_rejects_mixed_modes(self):
        with self.assertRaises(ValueError):
            mod.measure([
                {"type": "frame_done", "ocr_mode": "hybrid", "processing_ms": 100},
                {"type": "frame_done", "ocr_mode": "v6_both", "processing_ms": 200},
            ], {}, "mixed")

    def test_reads_diagnostic_zip(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "test.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("events.jsonl", "\n".join(json.dumps(e) for e in [
                    {"type": "session_start", "device": "S25"},
                    {"type": "frame_done", "ocr_mode": "v6_both", "processing_ms": 10,
                     "text": "PRIVATE"},
                    {"type": "frame_done", "ocr_mode": "v6_both", "processing_ms": 11},
                ]))
            self.assertEqual(mod.load(path)["ocr_mode"], "v6_both")
            self.assertNotIn("PRIVATE", json.dumps(mod.load(path)))


if __name__ == "__main__":
    unittest.main()
