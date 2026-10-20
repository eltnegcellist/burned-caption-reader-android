import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from private99_compare import key, edit_distance, percentile

class BenchmarkMetricsTests(unittest.TestCase):
    def test_caption_normalization(self):
        self.assertEqual(key("ＨＥＬＬＯ。\n あ い!!"), "helloあい")
        self.assertEqual(key("きょうは・・・\n雨 です。"), "きょうは雨です")

    def test_exact_three_lines(self):
        value = "字幕の読み取りを試しています\n三行でも順番が正しいか\n最後の行まで読めるでしょうか"
        self.assertEqual(edit_distance(value, value), (0, len(key(value))))

    def test_mismatch_is_counted(self):
        errors, length = edit_distance("字幕", "文字")
        self.assertEqual((errors, length), (2, 2))

    def test_percentile(self):
        self.assertEqual(percentile([1, 2, 3, 4], 95), 4)

if __name__ == "__main__":
    unittest.main()
