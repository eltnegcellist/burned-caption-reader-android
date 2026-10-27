import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec=importlib.util.spec_from_file_location("sampling",Path(__file__).with_name("summarize_diagnostics.py"))
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class SamplingReportTest(unittest.TestCase):
    def test_numeric_metrics_do_not_disclose_caption_text(self):
        items=[
          {"type":"sampling_experiment","mode":"frame","order_wait_ms":300,"mono_ms":1},
          {"type":"sampling_captured","frame_id":1,"pixelcopy_ms":22,"trigger_to_frame_ms":27,"ocr_text":"SECRET"},
          {"type":"sampling_ocr_completed","frame_id":1,"ocr_elapsed_ms":150},
          {"type":"sampling_tts_requested","frame_id":1,"since_ocr_start_ms":160},
          {"type":"tts_request","utterance_id":"u1","mono_ms":200,"text":"SECRET"},
          {"type":"tts_start","utterance_id":"u1","mono_ms":240},
          {"type":"sampling_tts_onstart","frame_id":1,"since_ocr_start_ms":202},
          {"type":"sampling_order_release","held_ms":85,"upper_blocked":True,"reason":"upper_cleared"},
          {"type":"sampling_order_release","held_ms":130,"upper_blocked":True,"reason":"deadline"},
          {"type":"sampling_order_release","held_ms":0,"upper_blocked":False,"reason":"no_upper"},
          {"type":"sampling_stats","frame_busy_skips":3,"frame_throttle_skips":2}
        ]
        report=mod.analyze(items)
        self.assertEqual(report["mode"],"frame")
        self.assertEqual(report["order_max_wait_ms"],300)
        self.assertEqual(report["order_released_total"],3)
        self.assertEqual(report["order_released_after_deadline"],1)
        self.assertEqual(report["order_released_on_upper_cleared"],1)
        self.assertEqual(report["order_released_without_upper_wait"],1)
        self.assertEqual(report["metrics"]["order_buffer_blocked_held_ms"]["median_ms"],107.5)
        self.assertEqual(report["metrics"]["tts_request_to_onstart_ms"]["median_ms"],40)
        self.assertEqual(report["metrics"]["ocr_ms"]["p95_ms"],150)
        self.assertEqual(report["frame_signals_busy_skipped"],3)
        self.assertNotIn("SECRET",json.dumps(report))
        self.assertNotIn("u1",json.dumps(report))

    def test_empty_and_unsupported_records(self):
        data=mod.analyze([{"type":"tts_request","mono_ms":2},{"type":"sampling_ocr_completed","ocr_elapsed_ms":-1}])
        self.assertEqual(data["metrics"]["ocr_ms"]["n"],0)
        self.assertIsNone(data["metrics"]["tts_request_to_onstart_ms"]["median_ms"])

if __name__=="__main__":unittest.main()
