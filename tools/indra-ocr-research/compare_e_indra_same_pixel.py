#!/usr/bin/env python3
"""Compare E's actual Android-caption-selector output to Indra on same PNG bytes.

Both use the same fixed caption-only ground truth and same Unicode normalization.
Indra's script may also report rotation-disabled results. CPU execution
environment and per-image pipeline definitions differ; latency is NOT an A/B speedup.
"""
import argparse, json, hashlib
from pathlib import Path
from synthetic_japanese_smoke import distance, normalize

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--dataset",required=True,type=Path)
    p.add_argument("--indra",required=True,type=Path)
    p.add_argument("--e",required=True,type=Path)
    p.add_argument("--out",required=True,type=Path)
    a=p.parse_args()
    m=json.loads((a.dataset/"dataset.json").read_text())
    x=json.loads(a.indra.read_text())
    y=json.loads(a.e.read_text())
    reference={c["case"]:c for c in x["cases"]}
    ef={f["id"]:f for f in y["frames"]}
    if len(ef)!=len(reference) or len(ef)!=len(m["frames"]):
        raise RuntimeError("Missing or duplicate cases")
    totals={key:{"edits":0,"chars":0,"exact":0,"blank_false_positives":0} for key in ["E","Indra","Indra_no_rotation"]}
    records=[]
    for row in m["frames"]:
        case=row["id"]; i=reference[case]; e=ef[case]
        if row["sha256"] != i["image_sha256"]:
            raise RuntimeError("Indra/E original PNG SHA mismatch for "+case)
        if row["sha256"] != hashlib.sha256((a.dataset/row["image"]).read_bytes()).hexdigest():
            raise RuntimeError("Local image mutated for "+case)
        gt=row["expected_text"]
        if e["expected_text"]!=gt:
            raise RuntimeError("E ground truth differed for "+case)
        preds={
          "E":e["ocr_text"],
          "Indra":"".join(i["detected_lines"]),
          "Indra_no_rotation":"".join(i.get("known_upright_detected_lines",i["detected_lines"]))
        }
        scores={}
        for key,txt in preds.items():
            edits,chars=distance(gt,txt)
            score=None if chars==0 else edits/chars
            scores[key]={"edits":edits,"reference_characters":chars,"cer":score,"exact":normalize(gt)==normalize(txt),"text":txt}
            totals[key]["edits"]+=edits;totals[key]["chars"]+=chars
            totals[key]["exact"]+=int(normalize(gt)==normalize(txt))
            totals[key]["blank_false_positives"]+=int(not normalize(gt) and bool(normalize(txt)))
        records.append({"id":case,"sha256":row["sha256"],"expected":gt,"results":scores,
            "e_android_ocr_processing_ms":e["ocr_processing_ms"],
            "indra_x86_warm_median_ms":i["warm_median_ms"],
            "indra_no_rotation_x86_warm_median_ms":i.get("known_upright_warm_median_ms")})
    for t in totals.values():t["cer"]=t["edits"]/t["chars"] if t["chars"] else None
    report={"scope":"ONLY FIVE synthetic images; no 99-video-frame comparison and no phone test",
        "same_pixel_input_sha256_checked":True,
        "model_indra":x["provenance"],
        "e_build_sha256":y.get("apk_sha256"),
        "e_device":y.get("device_serial"),
        "e_scope":y.get("ocr_mode"),
        "not_comparable_wall_time":"Indra is hosted Linux x86 CPU full page; E is Android emulator app pipeline",
        "totals":totals,"records":records}
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print("SAME_IMAGE_COMPARE "+json.dumps(totals,ensure_ascii=False))
    for r in records:
        print("CASE "+r["id"]+" "+json.dumps({k:{"cer":v["cer"],"text":v["text"]} for k,v in r["results"].items()},ensure_ascii=False))

if __name__=="__main__":
    main()
