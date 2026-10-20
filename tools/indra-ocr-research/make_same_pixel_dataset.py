#!/usr/bin/env python3
"""Freeze precisely the same synthetic PNGs used by Indra into an E replay manifest.

This is a harness control, not an external-video benchmark. Image bytes and
font are recorded so every comparison can be audited.
"""
import argparse, hashlib, json
from pathlib import Path
from synthetic_japanese_smoke import make_cases

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--font",required=True,type=Path)
    parser.add_argument("--out",required=True,type=Path)
    a=parser.parse_args()
    a.out.mkdir(parents=True,exist_ok=False)
    images=a.out/"images"
    images.mkdir()
    prepared=make_cases(a.font,images)
    frames=[]
    for n,(case_id,path,caption,all_visible) in enumerate(prepared):
        frames.append({
          "id":case_id,
          "mono_ms": n*2500,
          "image":"images/"+path.name,
          "expected_text":caption,
          "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
          "synthetic_all_visible_text":all_visible,
        })
    manifest={
      "id":"indra_e_synthetic_same_pixel_20261020",
      "source":"synthetic",
      "ground_truth_source":"synthetic_render_spec",
      "human_verified":False,
      "split":"harness_control",
      "reference_notes":"Exact Indra smoke make_cases 1100x618 Noto CJK; captions only",
      "font_sha256":hashlib.sha256(a.font.read_bytes()).hexdigest(),
      "frames":frames
    }
    (a.out/"dataset.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("FROZEN_DATASET_SHA256",hashlib.sha256((a.out/"dataset.json").read_bytes()).hexdigest())
    print("FROZEN_IMAGE_SHA256",json.dumps({r["id"]:r["sha256"] for r in frames},ensure_ascii=False))

if __name__=="__main__":
    main()
