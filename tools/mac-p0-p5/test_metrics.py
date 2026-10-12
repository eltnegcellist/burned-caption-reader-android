import unittest
from evaluate import distance, normalize, historical_key, score

class MetricsTest(unittest.TestCase):
    def test_numeric_and_negation_changes_are_not_removed(self):
        self.assertEqual(distance(normalize('価格１００円'),normalize('価格200円')),1)
        self.assertGreater(distance(normalize('利用できます'),normalize('利用できません')),0)
    def test_nfc_keeps_original_width_and_linebreaks(self):
        self.assertEqual(normalize('１\n２','NFC',True),'１\n２')
        self.assertEqual(normalize('１\n２'),'12')
    def test_blank_insertions_are_separate_from_positive_only(self):
        frames=[{'mono_ms':10},{'mono_ms':110}];ref=[{'start':0,'end':100,'text':'字幕'}]
        result=score(frames,ref,{0:{'s':'字幕'},1:{'s':'英字'}},'s')['NFKC']
        self.assertEqual(result['positive_only_cer'],0)
        self.assertEqual(result['cer_including_blank'],1)
        self.assertEqual(result['blank_nonempty_frames'],1)
    def test_multiline_collapse_cannot_pass_raw_nfc_structure(self):
        result=score([{'mono_ms':1}],[{'start':0,'end':10,'text':'一行\n二行'}],{0:{'s':'一行二行'}},'s')
        self.assertEqual(result['NFKC']['cer_including_blank'],0)
        self.assertGreater(result['NFC']['cer_including_blank'],0)
        self.assertEqual(result['NFKC']['multiline_structure_exact_frames'],0)
    def test_historical_key_is_not_primary_punctuation_metric(self):
        self.assertEqual(historical_key('誰？'),historical_key('誰'))
        self.assertNotEqual(normalize('誰？'),normalize('誰'))
if __name__=='__main__':unittest.main()
