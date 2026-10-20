#!/usr/bin/env python3
"""Private Mac-only 99-frame comparison: Indra OCR 1.1.2 vs saved production E.

Requires frozen private PNG + GT and saved Android E replay JSON from the
previous 99-frame experiment. This script NEVER uploads data or changes app.
Full text / raw OCR outputs remain under --private-output. Only aggregate
numeric data without GT, image paths or transcripts goes to --public-summary.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import re
import statistics
import time
import unicodedata
from pathlib import Path

DATASETS=("dev-cat","dev-watch","heldout")
MANIFEST_SHA={
    "dev-cat":"c927d62f79814a626c347c1ab74a07a3372f75e3591926bbb6b07d56856469d4",
    "dev-watch":"15bcd55d7105a95ac14f97f324d810c4eb76242b821b64fd45af3e2b7b7e7d53",
    "heldout":"b6d1a41f1b6f0f418314febd4db6d05cb0c2e41314fab23de28dfb748c771c17",
}
E_BASELINE_EDITS=180
E_BASELINE_CHARS=2545
# Mirrors SubtitleNormalizer.normalize/comparisonKey in main at 2026-10-20.
# Fail closed when this port does not reproduce *every* E frame metric.
def key(value):
    if not value or not value.strip():
        return ""
    s=unicodedata.normalize("NFKC",value).replace("\r\n","\n").replace("\r","\n")
    for char in ("⋯","‥","︙","⋮"):
        s=s.replace(char,"…")
    s=re.sub(r"[\u200b-\u200d\ufeff]","",s)
    lines=[]
    for line in s.split("\n"):
        t=re.sub(r"[\t \u3000]+","",line)
        t=re.sub(r"[~～]+","〜",t)
        t=re.sub(r"^[|｜]+|[|｜]+$","",t)
        t=re.sub(r"[.．・･·•‧∙⋅◦…]{2,}","…",t)
        t=re.sub(r"…{2,}","…",t)
        t=re.sub(r"。{2,}","。",t)
        t=re.sub(r"、{2,}","、",t)
        t=t.strip()
        if t: lines.append(t)
    s=re.sub(r"[。．.]$","。","\n".join(lines))
    s=re.sub(r"[、。,.，．・…⋯‥︙:：;；!?！？「」『』（）()\[\]【】]","",s.replace("\n",""))
    return s.lower()

def edit_distance(a,b):
    a,b=key(a),key(b)
    row=list(range(len(b)+1))
    for i,ch in enumerate(a,1):
        nxt=[i]
        for j,c in enumerate(b,1):
            nxt.append(min(nxt[-1]+1,row[j]+1,row[j-1]+(ch!=c)))
        row=nxt
    return row[-1],len(a)

def sha_file(path):
    h=hashlib.sha256()
    with open(path,"rb") as f:
        for data in iter(lambda:f.read(1024*1024),b""):
            h.update(data)
    return h.hexdigest()

def pct(n,d):
    return 100*n/d if d else None

def percentile(values,p):
    if not values: return None
    ordered=sorted(values)
    return round(ordered[max(0,min(len(ordered)-1,((len(ordered)*p+99)//100)-1))],2)

def summarize(records,mode):
    recs=[r for r in records if r["mode"]==mode]
    edits=sum(x["edits"] for x in recs)
    chars=sum(x["characters"] for x in recs)
    times=[x["warm_median_ms"] for x in recs if x["warm_median_ms"] is not None]
    return {
      "frames":len(recs),"reference_characters":chars,"edits":edits,"cer_pct":pct(edits,chars),
      "exact_frames":sum(x["edits"]==0 for x in recs),
      "false_positive_blanks":sum(x["characters"]==0 and x["edits"]>0 for x in recs),
      "complete_misses":sum(x["characters"]>0 and x["prediction_empty"] for x in recs),
      "frame_median_latency_ms":round(statistics.median(times),2) if times else None,
      "frame_p95_latency_ms":percentile(times,95),
      "latency_samples":len(times),
    }

def validate_and_load(datasets,e_results):
    out=[]
    for name in DATASETS:
        d=datasets/name
        manifest_file=d/"dataset.json"
        if sha_file(manifest_file)!=MANIFEST_SHA[name]:
            raise ValueError("Original manifest SHA mismatch: "+name)
        ds=json.loads(manifest_file.read_text(encoding="utf-8"))
        result_file=e_results/(name+"-e_mlkit_v6-1.json")
        if not result_file.exists():
            raise FileNotFoundError("Saved first-pass E replay missing: "+str(result_file))
        e=json.loads(result_file.read_text(encoding="utf-8"))
        if e.get("input_manifest_sha256")!=MANIFEST_SHA[name]:
            raise ValueError("E replay not generated from frozen original manifest: "+name)
        if e.get("ocr_mode")!="e_mlkit_v6":
            raise ValueError("Expected production E mode: "+name)
        if len(e["frames"])!=len(ds["frames"]):
            raise ValueError("E replay frame count mismatch: "+name)
        for pos,(f,ef) in enumerate(zip(ds["frames"],e["frames"])):
            filename=(d/f["image"]).resolve()
            if not filename.is_relative_to(d.resolve()) or not filename.is_file():
                raise ValueError("Original frame missing or path escape "+name+" pos "+str(pos))
            if sha_file(filename)!=f["sha256"]:
                raise ValueError("Original PNG byte SHA mismatch "+name+" id "+f["id"])
            if f["id"]!=ef["id"] or f["mono_ms"]!=ef["mono_ms"] or f["expected_text"]!=ef["expected_text"]:
                raise ValueError("E reference/id/time mismatched "+name+" pos "+str(pos))
            calculated,chars=edit_distance(f["expected_text"],ef["ocr_text"])
            if calculated!=ef["edit_distance"] or chars!=ef["reference_characters"]:
                raise ValueError("Python normalization diverges from Android E for "+name+" id "+f["id"]+
                                 ": got "+str((calculated,chars))+" expected "+str((ef["edit_distance"],ef["reference_characters"])))
            out.append((name,f,filename,ef))
    if len(out)!=99:
        raise ValueError("Expected 99 frozen image frames, got "+str(len(out)))
    e_edits=sum(ef["edit_distance"] for _,_,_,ef in out)
    e_chars=sum(ef["reference_characters"] for _,_,_,ef in out)
    if (e_edits,e_chars)!=(E_BASELINE_EDITS,E_BASELINE_CHARS):
        raise ValueError("E no longer matches validated 7.07% benchmark: "+str((e_edits,e_chars)))
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--datasets",type=Path,required=True,help="private Mac folder with dev-cat/dev-watch/heldout")
    p.add_argument("--e-results",type=Path,required=True,help="private/e-experiment/results containing *-e_mlkit_v6-1.json")
    p.add_argument("--private-output",type=Path,required=True,help="outside source repository; full Indra outputs and references")
    p.add_argument("--public-summary",type=Path,required=True,help="aggregate numbers only; no original images or caption strings")
    p.add_argument("--repeat",type=int,default=3)
    p.add_argument("--threads",type=int,default=4)
    a=p.parse_args()
    if a.repeat<2 or not (1<=a.threads<=8):
        p.error("repeat >=2, threads 1..8 required")
    # Guard against accidental exposure of PNG/text/GT in a public Git checkout.
    repository=Path(__file__).resolve().parents[2]
    output=a.private_output.resolve()
    if output.is_relative_to(repository):
        p.error("--private-output must be outside Git repository")
    data=validate_and_load(a.datasets,a.e_results)
    import numpy as np
    from PIL import Image
    import indra_ocr
    version=importlib.metadata.version("indra-ocr")
    if version!="1.1.2":
        raise RuntimeError("Pinned Indra OCR 1.1.2 required; got "+version)
    started=time.perf_counter()
    conv=indra_ocr.load(threads=a.threads,mode="ocr")
    model_load_ms=round(1000*(time.perf_counter()-started),2)
    rows=[]
    private_frames=[]
    for idx,(ds,f,path,ef) in enumerate(data,1):
        gray=np.asarray(Image.open(path).convert("L"))
        reference=f["expected_text"]
        frame={"dataset":ds,"id":f["id"],"image_sha256":f["sha256"],
               "reference":reference,"e":ef["ocr_text"],"runs":{}}
        for mode in ("default","known_upright"):
            times=[]; predictions=[]; metadata=[]
            for iteration in range(a.repeat):
                tic=time.perf_counter()
                page=conv.ocr(gray,turn=False) if mode=="known_upright" else conv.ocr(gray)
                delta_ms=round((time.perf_counter()-tic)*1000,3)
                txt="".join(str(getattr(line,"text","")) for line in page.lines)
                predictions.append(txt);times.append(delta_ms)
                metadata.append({"rotation":getattr(page,"rotation",None),
                                 "skew":getattr(page,"skew",None),
                                 "vertical":getattr(page,"vertical",None)})
            if len(set(predictions))>1:
                raise RuntimeError("Unstable Indra output "+ds+" id "+f["id"]+" mode "+mode)
            prediction=predictions[-1]
            edits,chars=edit_distance(reference,prediction)
            frame["runs"][mode]={"predicted_text":prediction,"times_ms":times,
                "orientation":metadata,"edits":edits,"characters":chars}
            rows.append({"dataset":ds,"mode":mode,"id":f["id"],"edits":edits,"characters":chars,
                "prediction_empty":not key(prediction),
                "warm_median_ms":statistics.median(times[1:])})
        rows.append({"dataset":ds,"mode":"E_saved","id":f["id"],
                    "edits":ef["edit_distance"],"characters":ef["reference_characters"],
                    "prediction_empty":not key(ef["ocr_text"]),"warm_median_ms":None})
        private_frames.append(frame)
        print("FRAMES_DONE",idx,"of 99",ds,f["id"],"E",ef["edit_distance"],
              "Indra",frame["runs"]["default"]["edits"],
              "Indra_upright",frame["runs"]["known_upright"]["edits"],flush=True)
    by_dataset={}
    for ds in DATASETS:
        by_dataset[ds]={m:summarize([r for r in rows if r["dataset"]==ds],m)
                        for m in ("E_saved","default","known_upright")}
    overall={m:summarize(rows,m) for m in ("E_saved","default","known_upright")}
    public={
       "provenance":"research-only, private 99 original video frames, captions and frame SHA verified",
       "model":"indra-ocr==1.1.2","os":platform.platform(),"cpu":platform.processor(),
       "machine":platform.machine(),"threads":a.threads,"repeat":a.repeat,
       "model_load_ms":model_load_ms,
       "source_dataset_manifests":MANIFEST_SHA,
       "e_verified_180_edits_2545_characters":True,
       "caveat":"E saved Android emulator pipeline latency cannot be divided into native Mac Indra latency",
       "caveat_accuracy":"E=caption selection + MLKit/v6 refine; Indra=entire image OCR. No equivalent subtitle selector on Indra.",
       "mode_labels":{"E_saved":"production E last replay", "default":"Indra full frame auto orientation",
                      "known_upright":"Indra full frame turn=False"},
       "by_dataset":by_dataset,"overall":overall,
    }
    a.private_output.mkdir(parents=True,exist_ok=True)
    private_path=a.private_output/"private99_indra_full_outputs.json"
    private_path.write_text(json.dumps({"provenance":public,"frames":private_frames},
                           ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    a.public_summary.parent.mkdir(parents=True,exist_ok=True)
    a.public_summary.write_text(json.dumps(public,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("COMPARISON_SUCCESS",json.dumps(overall,ensure_ascii=False),flush=True)
    print("PRIVATE_FRAMES_SAVED",str(private_path),flush=True)
    print("ANONYMIZED_NUMERIC_SUMMARY",str(a.public_summary),flush=True)
    print("DO_NOT_UPLOAD_PRIVATE_FRAMES_OR_CAPTION_TRANSCRIPTS",flush=True)

if __name__=="__main__":
    main()
