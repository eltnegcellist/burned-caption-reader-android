import unittest
from analyze import cold_indices, deltas, metric, timing, clustered_ci
class AnalysisTests(unittest.TestCase):
    def frame(self, det=0, rec=0, calls=0):
        return {'stages':{'engine_metrics':{'detector_init_ns':det,'recognizer_init_ns':rec,'recognizer_calls':calls}}}
    def test_lazy_init_after_blank_frame(self):
        run={'frames':[self.frame(),self.frame(rec=70,calls=1),self.frame(rec=70,calls=4)]}
        self.assertEqual(cold_indices(run),{0,1})
        self.assertEqual([x['recognizer_calls'] for x in deltas(run)],[0,1,3])
    def test_blanks_are_not_complete_misses(self):
        truth={'expected_text':'','text_boxes':[{'role':'chapter','text':'背景'}]}
        m=metric([(truth,{'ocr_text':'背景'})])
        self.assertIsNone(m['cer']);self.assertEqual(m['blank_false_positives'],1)
        self.assertEqual(m['complete_misses'],0);self.assertEqual(m['known_noncaption_contamination_frames'],1)
    def test_absent_background_annotation_is_not_audited(self):
        m=metric([({'expected_text':'字幕'},{'ocr_text':'字幕名札'})])
        self.assertEqual(m['contamination_audited_frames'],0)
    def test_exact_row_permutation(self):
        m=metric([({'expected_text':'上行\n下行'},{'ocr_text':'下行\n上行'})])
        self.assertEqual(m['exact_row_permutation_frames'],1)
    def test_nearest_rank_tail(self):
        self.assertEqual(timing(list(range(1,101)))['p99_ms'],99)
    def test_single_event_has_no_independent_frame_ci(self):
        t={'expected_text':'字幕'};b={'ocr_text':'字幕'};d={'ocr_text':'字'}
        self.assertIsNone(clustered_ci([(t,b,d)]*3,[{'event_id':'one'}]*3))
if __name__=='__main__':unittest.main()
