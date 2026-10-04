"""Diagnostic preflight guards; a stub proves rejection occurs before installation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class DiagnosticPreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "image.png").write_bytes(b"fixture: preflight only")
        self.dataset = {
            "source": "synthetic", "ground_truth_source": "synthetic_render_spec",
            "human_verified": False, "manual_caption_regions": True,
            "frames": [{"id": "1", "image": "image.png", "mono_ms": 1,
                        "expected_text": "", "caption_regions": [],
                        "sha256": hashlib.sha256((self.root / "image.png").read_bytes()).hexdigest()}],
        }
        self.marker = self.root / "adb-called"
        self.stub = self.root / "adb-stub"
        self.stub.write_text("#!" + sys.executable + "\nfrom pathlib import Path\n"
                             + "Path(" + repr(str(self.marker)) + ").touch()\nraise SystemExit(23)\n")
        self.stub.chmod(0o700)

    def run_tool(self, *options):
        (self.root / "dataset.json").write_text(json.dumps(self.dataset))
        return subprocess.run([
            sys.executable, str(Path(__file__).with_name("run.py")), str(self.root),
            str(self.root / "result.json"), "--adb", str(self.stub), "--device", "emulator-preflight",
            "--label", "preflight", *options], capture_output=True, text=True)

    def rejected(self, message, *options):
        result = self.run_tool(*options)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn(message, result.stderr)
        self.assertFalse(self.marker.exists(), "Invalid input reached installation")
        self.assertFalse((self.root / "result.json").exists())

    def reaches_stub(self, *options):
        result = self.run_tool(*options)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(self.marker.exists(), result.stderr)
        self.assertFalse((self.root / "result.json").exists())

    def test_coarse_override_cannot_be_labelled_raw(self):
        self.rejected("Coarse width override requires", "--coarse-width", "1100")

    def test_crop_preparation_cannot_be_labelled_player(self):
        self.rejected("Crop preparation requires", "--mode", "player-replay", "--crop-preparation", "white-core")

    def test_manual_flag_required(self):
        self.dataset.pop("manual_caption_regions")
        self.rejected("manually reviewed caption regions", "--mode", "caption-crops")

    def test_regions_required_even_on_blank_frame(self):
        self.dataset["frames"][0].pop("caption_regions")
        self.rejected("Each diagnostic frame requires", "--mode", "caption-crops")

    def test_invalid_rectangles_never_reach_device(self):
        for box in [[0, 0, 1], [0, 0, 0, 1], [0, 1, 1, 0], [-.1, 0, 1, 1],
                    [0, 0, 1.1, 1], [0, 0, float("nan"), 1], [0, 0, float("inf"), 1],
                    ["0", 0, 1, 1], [False, 0, 1, 1], None]:
            with self.subTest(box=box):
                self.dataset["frames"][0]["caption_regions"] = [box]
                self.rejected("Invalid normalized manual caption rectangle", "--mode", "caption-crops")

    def test_explicit_blank_region_list_is_allowed(self):
        self.reaches_stub("--mode", "caption-crops", "--crop-preparation", "white-core")

    def test_full_frame_boundary_crop_is_allowed(self):
        self.dataset["frames"][0]["caption_regions"] = [[0, 0, 1, 1]]
        self.reaches_stub("--mode", "caption-crops")

    def test_legacy_raw_does_not_require_manual_regions(self):
        self.dataset.pop("manual_caption_regions")
        self.dataset["frames"][0].pop("caption_regions")
        self.reaches_stub()

    def test_player_override_is_allowed(self):
        self.reaches_stub("--mode", "player-replay", "--coarse-width", "1100")


if __name__ == "__main__":
    unittest.main()
