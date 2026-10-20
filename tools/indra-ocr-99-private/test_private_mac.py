#!/usr/bin/env python3
"""No-model unit tests for the private 99-frame evaluator."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

TARGET=Path(__file__).with_name("run_private_mac.py")
spec=importlib.util.spec_from_file_location("private_mac",TARGET)
e=importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)


class ComparisonKeyTests(unittest.TestCase):
    def test_equivalences(self):
        e.assert_our_normalizer()
        self.assertEqual(e.comparison_key("私 は\n学生です。"),"私は学生です")
        self.assertEqual(e.comparison_key("１２３円…"),"123円")
        self.assertEqual(e.comparison_key("「 はい！ 」"),"はい")
        self.assertEqual(e.comparison_key("ＡＢＣ／Ｄ"),"abc/d")

    def test_edit_distance_and_blanks(self):
        s=e.frame_score("3行あります","3行ありす")
        self.assertEqual(s["edits"],1)
        self.assertEqual(s["chars"],6)
        self.assertFalse(s["exact"])
        self.assertTrue(e.frame_score("", "name")["false_positive_blank"])
        self.assertTrue(e.frame_score("日本語", "")["complete_miss"])

    def test_gate_rejects_drift_or_missing_images(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)/"ds";out=Path(td)/"e"
            out.mkdir()
            for i,(name,(manifest_sha,count,edits)) in enumerate(e.EXPECTED.items()):
                folder=root/name; folder.mkdir(parents=True)
                data=b"not a real image"+bytes([i])
                (folder/"frame.png").write_bytes(data)
                manifest={"frames":[{"id":"i","mono_ms":0,"image":"frame.png",
                         "expected_text":"日本語","sha256":e.sha256(folder/"frame.png")}]}
                (folder/"dataset.json").write_text(json.dumps(manifest))
                ev={"ocr_mode":"e_mlkit_v6","input_manifest_sha256":manifest_sha,
                    "frames":[{"id":"i","mono_ms":0,"expected_text":"日本語",
                               "ocr_text":"日本語","edit_distance":0,"reference_characters":3}]}
                (out/(name+"-e_mlkit_v6-1.json")).write_text(json.dumps(ev))
            with self.assertRaisesRegex(ValueError,"Manifest drift"):
                e.load_dataset_and_e(root,out)

    def test_summary(self):
        fake=[{"dataset":"dev-cat","id":"a","expected_text":"犬","e_score":e.frame_score("犬","犬")}]
        answers={"dev-cat/a":{
            "indra_fixed_upright":[{"score":e.frame_score("犬","猫"),"processing_ms":20},
                                  {"score":e.frame_score("犬","犬"),"processing_ms":15}],
            "indra_auto_orientation":[{"score":e.frame_score("犬","犬"),"processing_ms":30},
                                       {"score":e.frame_score("犬","犬"),"processing_ms":18}],
        }}
        rows=e.summarize(fake,answers,{})
        found={(r["group"],r["mode"]):r for r in rows}
        self.assertEqual(found[("ALL","E")]["edits"],0)
        self.assertEqual(found[("ALL","indra_fixed_upright")]["edits"],1)
        self.assertEqual(found[("ALL","indra_fixed_upright")]["mac_native_indra_warm_median_ms"],15)


if __name__=="__main__":
    unittest.main()
