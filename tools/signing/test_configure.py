import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("configure", Path(__file__).with_name("configure.py"))
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)


class SigningTest(unittest.TestCase):
    def test_missing_required_key_fails(self):
        with self.assertRaises(ValueError):
            configure.restore({"CAPTION_REQUIRE_PERSISTENT_SIGNING": "true"})

    def test_test_build_without_key_does_not_claim_continuity(self):
        self.assertIsNone(configure.restore({}))

    def test_restored_bytes_private_and_path_exported(self):
        with tempfile.TemporaryDirectory() as directory:
            envfile = Path(directory) / "env"
            path = configure.restore({"CAPTION_SIGNING_KEYSTORE_BASE64": base64.b64encode(b"fixture-not-a-real-key").decode(),
                                      "RUNNER_TEMP": directory, "GITHUB_ENV": str(envfile)})
            self.assertEqual(path.read_bytes(), b"fixture-not-a-real-key")
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
            self.assertIn(str(path), envfile.read_text())

    def test_corrupted_base64_fails(self):
        with self.assertRaises(ValueError):
            configure.restore({"CAPTION_SIGNING_KEYSTORE_BASE64": "!invalid!"})


if __name__ == "__main__":
    unittest.main()
