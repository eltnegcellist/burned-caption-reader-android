import tempfile
import unittest
from pathlib import Path
from score_srt import compare, read_srt, parse_time, norm


class ScoreTests(unittest.TestCase):
    def test_time(self):
        self.assertEqual(parse_time("00:01:02,003"), 62003)
        self.assertEqual(parse_time("00:00:01.5"), 1500)
        with self.assertRaises(ValueError):
            parse_time("00:60:00,000")

    def test_text(self):
        self.assertEqual(norm(" こんにちは、世界！"), "こんにちは世界")

    def test_srt(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "sample.srt"
            path.write_text("1\n00:00:01,000 --> 00:00:02,000\nこんにちは\n\n2\n00:00:03,000 --> 00:00:04,000\nさようなら\n", encoding="utf-8")
            self.assertEqual(len(read_srt(path)), 2)

    def test_exact(self):
        r = [{"start": 1000, "end": 2000, "text": "できたね"}]
        result = compare(r, r)
        self.assertEqual(result["event_aligned_cer"], 0)
        self.assertEqual(result["event_recall"], 1)

    def test_omit_duplicate(self):
        r = [{"start": 1000, "end": 2000, "text": "できたね"}, {"start": 3000, "end": 4000, "text": "だめだよ"}]
        p = [{"start": 1000, "end": 2000, "text": "できたね"}, {"start": 1200, "end": 1900, "text": "できたね"}]
        result = compare(r, p)
        self.assertEqual(result["missed_events"], 1)
        self.assertEqual(result["extra_events"], 1)
        self.assertEqual(result["possible_duplicate_events"], 1)


if __name__ == "__main__":
    unittest.main()
