#!/usr/bin/env python3
"""Analyze private B/D replay; publish only numbers, identifiers and hashes.
No raw captions, PNG, models, APK or original diagnostics are copied to output.
"""
import argparse, collections, hashlib, json, math, random, re, statistics, unicodedata
from pathlib import Path
from trigger import suspect_three_rows

def key(s):
    s=unicodedata.normalize('NFKC',s).replace('\r','\n')
    s=re.sub('[\u200B-\u200D\uFEFF]','',s); s=re.sub('[\t \u3000]+','',s)
    s=re.sub('[~～]+','〜',s);s=re.sub('[.．・･·•‧∙⋅◦…]{2,}','…',s)
    return re.sub(r'[\n、。,.，．・…⋯‥︙:：;；!?！？「」『』（）()\[\]【】]','',s).lower()
def distance(a,b):
    row=list(range(len(b)+1))
    for i,c in enumerate(a,1):
        new=[i]
        for j,d in enumerate(b,1):new.append(min(new[-1]+1,row[j]+1,row[j-1]+(c!=d)))
        row=new
    return row[-1]
def percentile(values,p):return sorted(values)[math.ceil(len(values)*p)-1] if values else None
def timing(v):
    return dict(n=len(v),mean_ms=statistics.mean(v) if v else None,median_ms=statistics.median(v) if v else None,p95_ms=percentile(v,.95),p99_ms=percentile(v,.99))
def metric(rows):
    chars=edits=rawchars=rawedits=errors=miss=blankfp=contamination=audited=digits=negation=order=0
    for truth,r in rows:
        a,b=key(truth['expected_text']),key(r['ocr_text']);chars+=len(a);edits+=distance(a,b)
        rawchars+=len(truth['expected_text']);rawedits+=distance(truth['expected_text'],r['ocr_text'])
        errors+=a!=b;miss+=bool(a) and not b;blankfp+=not a and bool(b)
        labels=[key(x['text']) for x in truth.get('text_boxes',[]) if x['role']!='caption']
        audited+='text_boxes' in truth
        contamination+=any(x and b.count(x)>a.count(x) for x in labels)
        digits+=re.findall(r'\d+',a)!=re.findall(r'\d+',b)
        negation+=any(a.count(x)!=b.count(x) for x in ['ない','なく','ません','ぬ','無い','ダメ'])
        la=[key(x) for x in truth['expected_text'].splitlines() if key(x)]
        lb=[key(x) for x in r['ocr_text'].splitlines() if key(x)]
        order+=la!=lb and sorted(la)==sorted(lb)
    n=len(rows)
    return dict(frames=n,reference_characters=chars,edit_distance=edits,cer=edits/chars if chars else None,
        raw_cer=rawedits/rawchars if rawchars else None,ser=errors/n if n else None,exact_frames=n-errors,
        complete_misses=miss,blank_false_positives=blankfp,known_noncaption_contamination_frames=contamination,
        contamination_audited_frames=audited,numeric_mismatch_frames=digits,negation_token_mismatch_frames=negation,
        exact_row_permutation_frames=order)
def cold_indices(run):
    skip={0}; previous=(0,0)
    for i,f in enumerate(run['frames']):
        m=f['stages']['engine_metrics'];current=(m['detector_init_ns'],m['recognizer_init_ns'])
        if current!=previous:skip.add(i)
        previous=current
    return skip
def deltas(run):
    previous={};out=[]
    for f in run['frames']:
        m=f['stages']['engine_metrics'];out.append({k:v-previous.get(k,0) for k,v in m.items()});previous=m
    return out
def clustered_ci(pairs,labels):
    groups=collections.defaultdict(list)
    for (t,b,d),lab in zip(pairs,labels):
        groups[lab['event_id']].append((len(key(t['expected_text'])),distance(key(t['expected_text']),key(b['ocr_text'])),distance(key(t['expected_text']),key(d['ocr_text']))))
    if len(groups)<2:return None
    events=[tuple(sum(x[j] for x in g) for j in range(3)) for g in groups.values()];rng=random.Random(53);samples=[]
    for _ in range(2000):
        drawn=[rng.choice(events) for _ in events];chars=sum(e[0] for e in drawn)
        if chars:samples.append(100*sum(e[2]-e[1] for e in drawn)/chars)
    return dict(method='paired subtitle-event cluster bootstrap, seed=53, 2000 resamples; exploratory',lower_pp=percentile(samples,.025),upper_pp=percentile(samples,.975))

def iou(a,b):
    area=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
    union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area
    return area/union if union else 0

def matches(gt,pred):
    edges=[[j for j,b in enumerate(pred) if iou(a,b)>=.5] for a in gt];owners={}
    def visit(i,seen):
        for j in edges[i]:
            if j in seen:continue
            seen.add(j)
            if j not in owners or visit(owners[j],seen):owners[j]=i;return True
        return False
    for i in range(len(gt)):visit(i,set())
    return len(owners)

def main():
    p=argparse.ArgumentParser();p.add_argument('--datasets',type=Path,required=True);p.add_argument('--results',type=Path,required=True);p.add_argument('--labels',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    labels=json.loads(a.labels.read_text());lookup={(x['dataset'],x['id']):x for x in labels}
    summary={'datasets':{},'strata':[],'conditional':{},'notes':[
        'Accuracy uses pass 1; all 3 passes are checked for changed predictions.',
        'Warm excludes frame 0 and any frame where detector/recognizer init counters increase; no extra prewarm.',
        'ML Kit stage includes public recognition and callback, not a detector-only network.',
        'Selection timing covers flushBefore, tracker.selectAll and refinement selector; counted calls are tracker plus refinement selector. Crop timing covers coarse and refinement band bitmaps.',
        'Residual includes dispatch, engine line crops, sorting, consensus, JSON and other work.',
        'Numeric/negation mismatches are lexical diagnostics, not human-adjudicated semantic error counts.',
        'Contamination evaluated only where frozen text_boxes exists; absent annotation never means no contamination.',
        'Conditional tables are offline choices between separately stateful replays, not a hybrid state simulation.'
    ]}
    all_pairs=[];all_labels=[];data={};public_frames=[];hashes={};timestamps=[]
    for name in ['dev-cat','dev-watch','heldout']:
        path=a.datasets/name/'dataset.json';truth=json.loads(path.read_text());frames=truth['frames'];digest=hashlib.sha256(path.read_bytes()).hexdigest()
        labs=[lookup[(name,f['id'])] for f in frames]
        for t,l in zip(frames,labs):assert t['sha256']==l['frame_sha256'];assert hashlib.sha256((a.datasets/name/t['image']).read_bytes()).hexdigest()==t['sha256']
        modes={};runs_by_mode={};diffs={};skips={}
        for mode in ['v6_rec','v6_both']:
            runs=[]
            for repeat in range(1,4):
                path=a.results/f'{name}-{mode}-{repeat}.json';r=json.loads(path.read_text());runs.append(r)
                hashes[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
                assert r['ocr_mode']==mode and r['input_manifest_sha256']==digest
                assert len(r['frames'])==len(frames)
                assert r['engine_class'].endswith('.V6ComparisonOcrEngine')
                for t,f in zip(frames,r['frames']):
                    assert (t['id'],t['mono_ms'],t['expected_text'])==(f['id'],f['mono_ms'],f['expected_text'])
                    assert distance(key(t['expected_text']),key(f['ocr_text']))==f['edit_distance']
                m=r['frames'][-1]['stages']['engine_metrics'];assert m['recognizer_calls']>0
                assert m['detector_calls']==0 and m['mlkit_calls']>0 if mode=='v6_rec' else m['mlkit_calls']==0 and m['detector_calls']>0
                log=a.results/f'{name}-{mode}-{repeat}.log'
                timestamps.append(dict(dataset=name,mode=mode,repeat=repeat,run_start_unix=log.stat().st_birthtime,result_saved_unix=path.stat().st_mtime,source='macOS log creation and result modification times; no thermal snapshot per individual run'))
            runs_by_mode[mode]=runs;diffs[mode]=[deltas(r) for r in runs];skips[mode]=[cold_indices(r) for r in runs]
            m=metric(list(zip(frames,runs[0]['frames'])));m['subtitle_events']=len({l['event_id'] for l in labs if l['gt_caption_row_count']});m['events_including_blanks']=len({l['event_id'] for l in labs})
            m['warm_pipeline']=timing([f['ocr_processing_ms'] for r,skip in zip(runs,skips[mode]) for i,f in enumerate(r['frames']) if i not in skip])
            m['all_pipeline']=timing([f['ocr_processing_ms'] for r in runs for f in r['frames']])
            m['cold_frames']=[[dict(id=r['frames'][i]['id'],pipeline_ms=r['frames'][i]['ocr_processing_ms'],init_ms={k:v/1e6 for k,v in diffs[mode][j][i].items() if k.endswith('_init_ns')}) for i in sorted(skip)] for j,(r,skip) in enumerate(zip(runs,skips[mode]))]
            m['changed_ids_by_repeat']=[[f['id'] for first,f in zip(runs[0]['frames'],r['frames']) if first['ocr_text']!=f['ocr_text']] for r in runs[1:]]
            m['sampled_peak_pss_kb']=[r['sampled_peak_pss_kb'] for r in runs];m['rss_hwm_kb']=[int(re.search(r'VmHWM:\s+(\d+)',r['process_status'])[1]) for r in runs]
            m['stages']={}
            for stage in ['mlkit','detector','recognizer','selection','crop']:
                ns_key=stage+'_ns';calls_key=stage+'_calls';per_frame=[];calls=[];total_calls=[];total_ns=[]
                for j,r in enumerate(runs):
                    work=[(f['stages'] if stage in ['selection','crop'] else diffs[mode][j][i]) for i,f in enumerate(r['frames'])]
                    total_calls.append(sum(x[calls_key] for x in work));total_ns.append(sum(x[ns_key] for x in work))
                    for i,x in enumerate(work):
                        if i not in skips[mode][j]:per_frame.append(x[ns_key]/1e6);calls.append(x[calls_key])
                m['stages'][stage]=dict(total_calls_by_repeat=total_calls,total_time_ms_by_repeat=[x/1e6 for x in total_ns],warm_per_frame=timing(per_frame),warm_calls_per_frame=dict(mean=statistics.mean(calls),median=statistics.median(calls),p95=percentile(calls,.95),p99=percentile(calls,.99)),aggregate_ms_per_call=sum(total_ns)/sum(total_calls)/1e6 if sum(total_calls) else None)
            modes[mode]=m
        assert len({r['apk_sha256'] for runs in runs_by_mode.values() for r in runs})==1
        assert len({r['test_apk_sha256'] for runs in runs_by_mode.values() for r in runs})==1
        b=runs_by_mode['v6_rec'][0]['frames'];d=runs_by_mode['v6_both'][0]['frames'];pairs=list(zip(frames,b,d));all_pairs+=pairs;all_labels+=labs
        summary['datasets'][name]=dict(manifest_sha256=digest,apk_sha256=runs_by_mode['v6_rec'][0]['apk_sha256'],test_apk_sha256=runs_by_mode['v6_rec'][0]['test_apk_sha256'],modes=modes,paired_delta_ci=clustered_ci(pairs,labs))
        data[name]=(frames,labs,runs_by_mode,diffs,skips)
        for i,(t,x,y) in enumerate(pairs):
            public_frames.append(dict(dataset=name,id=t['id'],sha256=t['sha256'],mono_ms=t['mono_ms'],event_id=labs[i]['event_id'],gt_rows=labs[i]['gt_caption_row_count'],noncaption=labs[i]['noncaption_text_visible'],nameplate=labs[i]['nameplate_visible'],reference_characters=len(key(t['expected_text'])),B_edits=x['edit_distance'],D_edits=y['edit_distance'],B_exact=key(t['expected_text'])==key(x['ocr_text']),D_exact=key(t['expected_text'])==key(y['ocr_text']),trigger=suspect_three_rows(x['stages']['raw_rows']),passes={mode:[dict(pipeline_ms=r['frames'][i]['ocr_processing_ms'],cold=i in skips[mode][j],engine_delta=diffs[mode][j][i],selection_ns=r['frames'][i]['stages']['selection_ns'],selection_calls=r['frames'][i]['stages']['selection_calls'],crop_ns=r['frames'][i]['stages']['crop_ns'],crop_calls=r['frames'][i]['stages']['crop_calls'],pss_kb=r['frames'][i]['pss_kb']) for j,r in enumerate(runs)] for mode,runs in runs_by_mode.items()}))
        component=json.loads((a.results/f'{name}-components.json').read_text());assert [f['id'] for f in component['frames']]==[f['id'] for f in frames]
        component_summary={'initialization':component['initialization'],'sampled_peak_pss_kb':component['sampled_peak_pss_kb']}
        for backend in ['mlkit','v6']:
            times=[time for f in component['frames'] for time in f.get(backend+'_detection_ms',[])[1:]]
            component_summary[backend+'_detection_warm']=timing(times)
            component_summary[backend+'_detection_calls']=sum(len(f.get(backend+'_detection_ms',[])) for f in component['frames'])
            component_summary[backend+'_first_frame_inferences_ms']=next((f[backend+'_detection_ms'] for f in component['frames'] if backend+'_detection_ms' in f),[])
            total=predicted=tp=cap_total=cap_tp=0
            for t,f in zip(frames,component['frames']):
                if 'text_boxes' not in t:continue
                gt=[x['box'] for x in t['text_boxes']];caps=[x['box'] for x in t['text_boxes'] if x['role']=='caption'];boxes=f[backend+'_boxes']
                if 'detection_roi' in t:
                    z=t['detection_roi'];boxes=[b for b in boxes if z[0]<=(b[0]+b[2])/2<=z[2] and z[1]<=(b[1]+b[3])/2<=z[3]]
                for z in t.get('detection_ignore_regions',[]):
                    boxes=[b for b in boxes if not(z[0]<=(b[0]+b[2])/2<=z[2] and z[1]<=(b[1]+b[3])/2<=z[3])]
                total+=len(gt);predicted+=len(boxes);tp+=matches(gt,boxes);cap_total+=len(caps);cap_tp+=matches(caps,boxes)
            component_summary[backend+'_boxes']=dict(truth=total,proposals=predicted,tp=tp,recall=tp/total if total else None,precision=tp/predicted if predicted else None,caption_truth=cap_total,caption_tp=cap_tp,caption_recall=cap_tp/cap_total if cap_total else None)

        crops=[c for f in component['frames'] for c in f.get('recognition_crops',[])];chars=sum(len(key(c['expected_text'])) for c in crops)
        component_summary['v6_recognition']=dict(crops=len(crops),calls=sum(len(c['v6_ms']) for c in crops),warm=timing([t for c in crops for t in c['v6_ms'][1:]]),cer=sum(distance(key(c['expected_text']),key(c['v6_text'])) for c in crops)/chars if chars else None)
        summary['datasets'][name]['components']=component_summary
    for name in ['dev-cat','dev-watch','heldout','all']:
        for rows in [0,1,2,3,4]:
            for bg in [False,True,'unknown']:
                selected=[i for i,l in enumerate(all_labels) if (name=='all' or l['dataset']==name) and min(l['gt_caption_row_count'],4)==rows and l['noncaption_text_visible']==bg]
                triples=[all_pairs[i] for i in selected]; labs=[all_labels[i] for i in selected];per={}
                for mode,j in [('v6_rec',1),('v6_both',2)]:
                    metrics=metric([(t[0],t[j]) for t in triples]);time=[]
                    for ix in selected:
                        name2=all_labels[ix]['dataset'];frame_id=all_labels[ix]['id'];fs,_,runs,_,skips=data[name2];index=next(i for i,f in enumerate(fs) if f['id']==frame_id)
                        for r,skip in zip(runs[mode],skips[mode]):
                            if index not in skip:time.append(r['frames'][index]['ocr_processing_ms'])
                    metrics['warm_pipeline']=timing(time);per[mode]=metrics
                summary['strata'].append(dict(dataset=name,gt_rows=rows,noncaption=bg,frames=len(selected),subtitle_events=len({l['event_id'] for l in labs if l['gt_caption_row_count']}),events_including_blanks=len({l['event_id'] for l in labs}),modes=per,cer_delta_D_minus_B_pp=100*(per['v6_both']['cer']-per['v6_rec']['cer']) if per['v6_rec']['cer'] is not None else None,D_better_ids=[t[0]['id'] for t in triples if t[2]['edit_distance']<t[1]['edit_distance']],B_better_ids=[t[0]['id'] for t in triples if t[1]['edit_distance']<t[2]['edit_distance']],paired_delta_ci=clustered_ci(triples,labs)))
    assert len({x['apk_sha256'] for x in summary['datasets'].values()})==1
    assert len({x['test_apk_sha256'] for x in summary['datasets'].values()})==1
    summary['overall']={mode:metric([(t[0],t[j]) for t in all_pairs]) for mode,j in [('B',1),('D',2)]}
    for method in ['B_fixed','D_fixed','oracle_direct','oracle_B_first','B_output_trigger']:
        picked=[];times=[];extra=[];calls={k:[] for k in ['mlkit_calls','detector_calls','recognizer_calls']}
        for name,(frames,labs,runs,diffs,skips) in data.items():
            bs=runs['v6_rec'][0]['frames'];ds=runs['v6_both'][0]['frames']
            for i,(t,b,d,l) in enumerate(zip(frames,bs,ds,labs)):
                use_d=method=='D_fixed' or (method.startswith('oracle') and l['gt_caption_row_count']>=3) or (method=='B_output_trigger' and suspect_three_rows(b['stages']['raw_rows']))
                picked.append((t,d if use_d else b))
                for j in range(3):
                    bd=runs['v6_rec'][j]['frames'][i];dd=runs['v6_both'][j]['frames'][i]
                    if i in (skips['v6_rec'][j]|skips['v6_both'][j]):continue
                    base=bd['ocr_processing_ms'];cost=dd['ocr_processing_ms'] if use_d else base
                    if use_d and method in ['oracle_B_first','B_output_trigger']:cost+=base
                    times.append(cost)
                    extra.append(diffs['v6_both'][j][i]['detector_calls'] if use_d and method in ['oracle_B_first','B_output_trigger'] else 0)
                    for k in calls:
                        n=diffs['v6_both' if use_d else 'v6_rec'][j][i][k]
                        if use_d and method in ['oracle_B_first','B_output_trigger']:n+=diffs['v6_rec'][j][i][k]
                        calls[k].append(n)
        summary['conditional'][method]=dict(accuracy=metric(picked),paired_common_warm_pipeline=timing(times),additional_v6_detection_calls=sum(extra),additional_v6_detection_calls_per_warm_frame=statistics.mean(extra),calls_per_warm_frame={k:statistics.mean(v) for k,v in calls.items()},trigger_runtime_ms=None,cost_note='Arithmetic from isolated replay clocks; actual trigger and hybrid-state costs unmeasured')
    triggers=[suspect_three_rows(b['stages']['raw_rows']) for t,b,d in all_pairs];truth=[l['gt_caption_row_count']>=3 for l in all_labels]
    tp=sum(t and g for t,g in zip(triggers,truth));fp=sum(t and not g for t,g in zip(triggers,truth));positives=sum(truth);predicted=sum(triggers)
    summary['trigger_evaluation']=dict(rule_sha256=hashlib.sha256(Path(__file__).with_name('trigger.py').read_bytes()).hexdigest(),frames=len(truth),true_three_or_more=positives,triggered=predicted,tp=tp,fp=fp,fn=positives-tp,recall=tp/positives if positives else None,precision=tp/predicted if predicted else None,false_positive_rate=fp/(len(truth)-positives),missed_ids=[t[0]['id'] for t,pred,g in zip(all_pairs,triggers,truth) if g and not pred],false_trigger_ids=[t[0]['id'] for t,pred,g in zip(all_pairs,triggers,truth) if pred and not g])
    summary['result_sha256']=hashes;summary['run_times']=timestamps;summary['labels_sha256']=hashlib.sha256(a.labels.read_bytes()).hexdigest()
    a.output.mkdir(parents=True,exist_ok=True)
    for name,value in [('summary.json',summary),('frames.numeric.json',public_frames),('strata-labels.json',labels)]:
        (a.output/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(summary['overall']));print(json.dumps(summary['trigger_evaluation']))
if __name__=='__main__':main()
