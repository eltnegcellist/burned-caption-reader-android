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

    def test_global_alignment_avoids_greedy_loss(self):
        r=[{'start':0,'end':1000,'text':'前'}, {'start':1000,'end':2000,'text':'後'}]
        p=[{'start':0,'end':100,'text':'前'}, {'start':100,'end':1500,'text':'後'}]
        # A greedy IoU matcher steals the long prediction for the first reference.
        self.assertEqual(compare(r,p)['matched_events'],2)

    def test_text_does_not_choose_temporal_pair(self):
        r=[{'start':0,'end':1000,'text':'100円'}, {'start':1000,'end':2000,'text':'200円'}]
        p=[{'start':0,'end':2000,'text':'100円'}]
        out=compare(r,p)
        self.assertEqual(out['possible_merged_predictions'],1)
        self.assertEqual(out['matched_events'],1)
        self.assertEqual(out['missed_events'],1)

    def test_wrong_text_is_not_correct_event(self):
        r=[{'start':0,'end':1000,'text':'利用できます'}]
        p=[{'start':0,'end':1000,'text':'利用できません'}]
        out=compare(r,p)
        self.assertEqual(out['event_recall'],1)
        self.assertEqual(out['correct_event_recall'],0)

    def test_merge_across_blank_is_false_positive_time(self):
        r=[{'start':1000,'end':2000,'text':'同じ'}, {'start':3000,'end':4000,'text':'同じ'}]
        p=[{'start':1000,'end':4000,'text':'同じ'}]
        out=compare(r,p,duration_ms=5000)
        self.assertEqual(out['extra_events'],0)
        self.assertEqual(out['time_coverage']['predicted_blank_ms'],1000)
        self.assertAlmostEqual(out['time_coverage']['blank_false_positive_time_rate'],1/3)

    def test_runtime_threshold_empty_and_multiline(self):
        r=[{'start':0,'end':1000,'text':'一行\n二行\n三行'}]
        self.assertEqual(compare(r,r)['multiline']['3']['line_structure_exact_events'],1)
        self.assertEqual(compare([],[])['event_aligned_cer'],None)
        self.assertIn('>=100ms',compare(r,r,100)['matching_method'])
        with self.assertRaises(ValueError): compare(r,r,0)


if __name__ == "__main__":
    unittest.main()
