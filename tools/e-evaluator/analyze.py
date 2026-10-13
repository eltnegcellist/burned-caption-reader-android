#!/usr/bin/env python3
"""Score B/D/E actual Android replay. Publish identifiers, hashes and numbers only."""
import argparse,hashlib,json,sys,statistics,re,datetime,collections
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'bd-retest'))
from analyze import key,distance,metric,timing,cold_indices,deltas,clustered_ci
MODES={'v6_rec':'B','v6_both':'D','e_mlkit_v6':'E'}
NAMES=['dev-cat','dev-watch','heldout']

def main():
    p=argparse.ArgumentParser();p.add_argument('--datasets',type=Path,required=True);p.add_argument('--results',type=Path,required=True);p.add_argument('--labels',type=Path,required=True);p.add_argument('--frozen-build',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    labels=json.loads(a.labels.read_text());lookup={(x['dataset'],x['id']):x for x in labels};frozen=json.loads(a.frozen_build.read_text())
    out={'datasets':{},'overall':{},'strata':[],'result_sha256':{},'environments':[],'frozen_build':frozen,
        'notes':['Actual engine inference, not a saved-text oracle. No threshold changes after inspecting E predictions.',
        'Accuracy from repeat 1; three-repeat agreement checked. GT is Codex pixel transcription, human audit pending.',
        'Cold excludes frame 0 and any frame with changed lazy model init counters. No additional prewarm.',
        'Pipeline clock excludes image decode/install/upload/TTS. Selection includes E policy time; never add it twice.',
        'Same three already-known videos. Heldout is not independent unused material.',
        'E filter and selector are in experiment test source; normal D-fixed APK is identical to prior PR54 APK.']}
    public=[];pairs={label:[] for label in MODES.values()};rawdata={};global_times={label:[] for label in MODES.values()}
    for name in NAMES:
        path=a.datasets/name/'dataset.json';truth=json.loads(path.read_text())['frames'];manifest_hash=hashlib.sha256(path.read_bytes()).hexdigest();labs=[lookup[(name,t['id'])] for t in truth];data={};modes={}
        for t,l in zip(truth,labs):assert hashlib.sha256((a.datasets/name/t['image']).read_bytes()).hexdigest()==t['sha256']==l['frame_sha256']
        for mode,label in MODES.items():
            runs=[];diffs=[];skips=[]
            for repeat in range(1,4):
                path=a.results/f'{name}-{mode}-{repeat}.json';r=json.loads(path.read_text());assert r['ocr_mode']==mode and r['input_manifest_sha256']==manifest_hash
                assert r['apk_sha256']==frozen['app_apk_sha256'] and r['test_apk_sha256']==frozen['test_apk_sha256'];assert r['engine_class'].endswith('.V6ComparisonOcrEngine');assert len(r['frames'])==len(truth)
                out['result_sha256'][path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
                for t,f in zip(truth,r['frames']):
                    assert (t['id'],t['mono_ms'],t['expected_text'])==(f['id'],f['mono_ms'],f['expected_text'])
                    assert distance(key(t['expected_text']),key(f['ocr_text']))==f['edit_distance']
                    m=f['stages']['engine_metrics'];assert m['mlkit_calls']==0 if label=='D' else m['detector_calls']==0
                    assert f['stages']['e_policy_calls']>0 if label=='E' else f['stages']['e_policy_calls']==0
                last=r['frames'][-1]['stages']['engine_metrics'];assert last['recognizer_calls']>0
                environment=json.loads((a.results/f'{name}-{mode}-{repeat}.environment.json').read_text());out['environments'].append(dict(dataset=name,mode=label,repeat=repeat,**environment))
                runs.append(r);diffs.append(deltas(r));skips.append(cold_indices(r))
            data[label]=(runs,diffs,skips);rows=runs[0]['frames'];m=metric(list(zip(truth,rows)));pairs[label]+=list(zip(truth,rows))
            m['subtitle_events']=len({l['event_id'] for l in labs if l['gt_caption_row_count']})
            m['repeat_changed_ids']=[[f['id'] for x,f in zip(rows,r['frames']) if x['ocr_text']!=f['ocr_text']] for r in runs[1:]]
            m['warm_pipeline']=timing([f['ocr_processing_ms'] for r,skip in zip(runs,skips) for i,f in enumerate(r['frames']) if i not in skip])
            m['per_repeat_warm']=[timing([f['ocr_processing_ms'] for i,f in enumerate(r['frames']) if i not in skip]) for r,skip in zip(runs,skips)]
            m['cold_frames']=[[dict(id=r['frames'][i]['id'],pipeline_ms=r['frames'][i]['ocr_processing_ms'],engine_delta=diffs[j][i]) for i in sorted(skip)] for j,(r,skip) in enumerate(zip(runs,skips))]
            m['sampled_peak_pss_kb']=[r['sampled_peak_pss_kb'] for r in runs];m['rss_hwm_kb']=[int(re.search(r'VmHWM:\s+(\d+)',r['process_status'])[1]) for r in runs]
            m['stages']={}
            for stage in ['mlkit','detector','recognizer','selection','crop','e_policy']:
                ns=stage+'_ns';calls=stage+'_calls';all_calls=[];values=[];counts=[];total_ns=[]
                for j,r in enumerate(runs):
                    work=[f['stages'] if stage in ['selection','crop','e_policy'] else diffs[j][i] for i,f in enumerate(r['frames'])]
                    all_calls.append(sum(x[calls] for x in work));total_ns.append(sum(x[ns] for x in work))
                    for i,x in enumerate(work):
                        if i not in skips[j]:values.append(x[ns]/1e6);counts.append(x[calls])
                m['stages'][stage]=dict(total_calls_by_repeat=all_calls,total_time_ms_by_repeat=[x/1e6 for x in total_ns],warm_per_frame=timing(values),warm_calls_mean=statistics.mean(counts))
            m['E_actions']=dict(removed_rows_by_repeat=[sum(f['stages']['e_removed_rows'] for f in r['frames']) for r in runs],changed_choices_by_repeat=[sum(f['stages']['e_changed_choices'] for f in r['frames']) for r in runs])
            modes[label]=m
        rawdata[name]=(truth,labs,data)
        pair_summary={}
        for baseline in ['B','D']:
            b=data[baseline][0][0]['frames'];e=data['E'][0][0]['frames'];pair_summary['E_vs_'+baseline]=dict(
                cer_delta_pp=100*(modes['E']['cer']-modes[baseline]['cer']),
                better_ids=[t['id'] for t,x,y in zip(truth,b,e) if y['edit_distance']<x['edit_distance']],
                worse_ids=[t['id'] for t,x,y in zip(truth,b,e) if y['edit_distance']>x['edit_distance']],
                event_cluster_ci=clustered_ci(list(zip(truth,b,e)),labs))
        out['datasets'][name]=dict(manifest_sha256=manifest_hash,modes=modes,pairs=pair_summary)
        for i,t in enumerate(truth):
            row=dict(dataset=name,id=t['id'],frame_sha256=t['sha256'],mono_ms=t['mono_ms'],gt_rows=labs[i]['gt_caption_row_count'],noncaption=labs[i]['noncaption_text_visible'],event_id=labs[i]['event_id'],reference_characters=len(key(t['expected_text'])),modes={})
            for label,(runs,diffs,skips) in data.items():
                first=runs[0]['frames'][i];row['modes'][label]=dict(edits=first['edit_distance'],exact=key(first['ocr_text'])==key(t['expected_text']),removed_rows=first['stages']['e_removed_rows'],changed_choices=first['stages']['e_changed_choices'],passes=[])
                for j,r in enumerate(runs):
                    f=r['frames'][i];row['modes'][label]['passes'].append(dict(pipeline_ms=f['ocr_processing_ms'],cold=i in skips[j],engine_delta=diffs[j][i],stage_ns={k:f['stages'][k+'_ns'] for k in ['selection','crop','e_policy']},stage_calls={k:f['stages'][k+'_calls'] for k in ['selection','crop','e_policy']},pss_kb=f['pss_kb']))
            for j in range(3):
                if any(i in data[label][2][j] for label in data):continue
                for label in data:global_times[label].append(data[label][0][j]['frames'][i]['ocr_processing_ms'])
            public.append(row)
        for rows in [0,1,2,3,4]:
            for bg in [False,True,'unknown']:
                indices=[i for i,l in enumerate(labs) if min(l['gt_caption_row_count'],4)==rows and l['noncaption_text_visible']==bg]
                strata=dict(dataset=name,gt_rows=rows,noncaption=bg,frames=len(indices),subtitle_events=len({labs[i]['event_id'] for i in indices if rows}),modes={})
                for label,(runs,diffs,skips) in data.items():
                    m=metric([(truth[i],runs[0]['frames'][i]) for i in indices]);m['warm_pipeline']=timing([r['frames'][i]['ocr_processing_ms'] for r,skip in zip(runs,skips) for i in indices if i not in skip]);strata['modes'][label]=m
                out['strata'].append(strata)
    for label in pairs:out['overall'][label]=metric(pairs[label]);out['overall'][label]['paired_common_warm_pipeline']=timing(global_times[label])
    out['labels_sha256']=hashlib.sha256(a.labels.read_bytes()).hexdigest()
    # Remove device serial strings except our explicitly authorized emulator IDs before publishing snapshots.
    # Real device IDs are never needed for this emulator experiment.
    for env in out['environments']:
        for when in ['before','after']:
            d=env[when]['devices'];d['output']='\n'.join(x for x in d['output'].splitlines() if not x.strip() or x.startswith('List of devices') or x.startswith('emulator-'))
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'summary.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
    (a.output/'frames.numeric.json').write_text(json.dumps(public,indent=2)+'\n')
    print(json.dumps(out['overall']))
if __name__=='__main__':main()
