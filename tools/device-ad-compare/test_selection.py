#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("selection_analyzer", HERE / "analyze_selection.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class SelectionAnalyzerTest(unittest.TestCase):
    def test_classifies_loss_without_leaking_text(self):
        events = [
            {"type": "ocr_raw", "frame_id": 1, "backend": "mlkit",
             "rows": [{"text": "PRIVATE_123"}]},
            {"type": "selection_trace", "frame_id": 1, "stage": "scene_filter",
             "reason": "filtered_scene_text", "text": "PRIVATE_123"},
            {"type": "selection_summary", "frame_id": 1, "raw_rows": 1,
             "selected_bands": 0},
            {"type": "ocr_stage_done", "frame_id": 1, "stage": "coarse", "elapsed_ms": 90},
            {"type": "selection_summary", "frame_id": 2, "raw_rows": 0,
             "selected_bands": 0},
            {"type": "selection_summary", "frame_id": 3, "raw_rows": 2,
             "selected_bands": 1},
            {"type": "rescue_shadow", "frame_id": 2, "would_rescue": True},
            {"type": "skip_shadow", "frame_id": 3, "would_skip": True},
        ]
        result = tool.analyze(events)
        stats = result["frame_summary"]
        self.assertEqual(stats["raw_positive_selected_empty"], 1)
        self.assertEqual(stats["raw_empty"], 1)
        self.assertEqual(stats["has_selection"], 1)
        self.assertEqual(result["p3_shadow_candidates"], 1)
        self.assertEqual(result["p4_shadow_skip_candidates"], 1)
        self.assertEqual(result["ocr_elapsed_ms"]["coarse"]["median"], 90)
        self.assertEqual(result["reason_counts_on_raw_positive_selected_empty"]["scene_filter/filtered_scene_text"], 1)
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_reads_zip_and_handles_missing_incomplete_frames(self):
        rows = [{"type": "ocr_raw", "frame_id": 7, "rows": [], "text": "SECRET"},
                {"type": "selection_summary", "frame_id": 8, "raw_rows": 0, "selected_bands": 0}]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "diagnostics.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("events.jsonl", "\n".join(map(json.dumps, rows)))
            report = tool.analyze(tool.read_zip(path))
        self.assertEqual(report["frame_summary"]["frames_without_selection_summary"], 1)
        self.assertNotIn("SECRET", json.dumps(report))

    def test_empty_has_no_guessed_cause(self):
        result = tool.analyze([])
        self.assertEqual(result["raw_positive_selected_empty_examples"], [])
        self.assertEqual(result["reason_counts_on_raw_positive_selected_empty"], {})


if __name__ == "__main__":
    unittest.main()
