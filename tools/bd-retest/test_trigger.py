import unittest
from trigger import suspect_three_rows
class TriggerTests(unittest.TestCase):
    def row(self, top, text='字幕行', left=.2, right=.8):
        return dict(top=top,bottom=top+.06,left=left,right=right,text=text)
    def test_two_rows_does_not_claim_missing_third(self):
        self.assertFalse(suspect_three_rows([self.row(.10),self.row(.18)]))
    def test_three_rows_and_unrelated_latin_nameplate(self):
        self.assertTrue(suspect_three_rows([self.row(.10),self.row(.18),self.row(.26),self.row(.86,'NAME')]))
    def test_nameplates_do_not_bridge_distant_rows(self):
        self.assertFalse(suspect_three_rows([self.row(.10),self.row(.18),self.row(.86)]))
    def test_bottom_caption(self):
        self.assertTrue(suspect_three_rows([self.row(.70),self.row(.78),self.row(.86)]))
if __name__=='__main__': unittest.main()
