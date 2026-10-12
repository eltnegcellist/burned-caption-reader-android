import json,math,statistics,unicodedata,re,hashlib
from pathlib import Path

def key(s):
    s=unicodedata.normalize('NFKC',s).replace('\r','\n')
    s=re.sub('[\u200B-\u200D\uFEFF]','',s)
    s=re.sub('[\t \u3000]+','',s)
    s=re.sub('[~～]+','〜',s)
    s=re.sub('[.．・･·•‧∙⋅◦…]{2,}','…',s)
    return re.sub(r'[\n、。,.，．・…⋯‥︙:：;；!?！？「」『』（）()\[\]【】]','',s).lower()
def edits(a,b):
    dp=[[(i+j,0,j,i) for j in range(len(b)+1)] for i in range(len(a)+1)]
    for i in range(1,len(a)+1):
        for j in range(1,len(b)+1):
            if a[i-1]==b[j-1]:dp[i][j]=dp[i-1][j-1];continue
            z=dp[i-1][j-1];sub=(z[0]+1,z[1]+1,z[2],z[3])
            z=dp[i-1][j];delete=(z[0]+1,z[1],z[2],z[3]+1)
            z=dp[i][j-1];insert=(z[0]+1,z[1],z[2]+1,z[3])
            dp[i][j]=min([sub,delete,insert],key=lambda x:x[0])
    return dp[-1][-1]
def percentile(v,p):return sorted(v)[max(0,math.ceil(len(v)*p)-1)] if v else None
def timing(v):return {'n':len(v),'median_ms':statistics.median(v) if v else None,'p95_ms':percentile(v,.95)}
def iou(a,b):
    x=max(0,min(a[2],b[2])-max(a[0],b[0]));y=max(0,min(a[3],b[3])-max(a[1],b[1]))
    intersection=x*y;union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection
    return intersection/union if union>0 else 0
def matches(gt,pred,threshold):
    edges=[[j for j,b in enumerate(pred) if iou(a,b)>=threshold] for a in gt];owners={}
    def visit(i,seen):
        for j in edges[i]:
            if j in seen:continue
            seen.add(j)
            if j not in owners or visit(owners[j],seen):owners[j]=i;return True
        return False
    for i in range(len(gt)):visit(i,set())
    return [(i,j,iou(gt[i],pred[j])) for j,i in owners.items()]
names=['dev-cat','dev-watch','heldout'];modes=['hybrid','v6_rec','v6_det','v6_both']
summary={'datasets':{},'pairs':{},'metric_note':'First pass accuracy; two passes warm latency excludes the first image and model-initialization image in each fresh process; nearest-rank p95. Frame SER, not unique subtitle-event SER.'}
for name in names:
    dataset=json.loads(Path(f'datasets/{name}/dataset.json').read_text());out={'split':dataset['split'],'frames':len(dataset['frames']),'modes':{}}
    out['manifest_sha256']=hashlib.sha256(Path(f'datasets/{name}/dataset.json').read_bytes()).hexdigest()
    for mode in modes:
        runs=[json.loads(Path(f'results/{name}-{mode}-{j}.json').read_text()) for j in [1,2]]
        assert all(r['input_manifest_sha256']==out['manifest_sha256'] and r['ocr_mode']==mode for r in runs)
        assert runs[0]['apk_sha256']==runs[1]['apk_sha256'] and runs[0]['test_apk_sha256']==runs[1]['test_apk_sha256']
        rows=runs[0]['frames'];total=[0,0,0,0];chars=0;raw_edits=0;raw_chars=0;ser=0;raw_ser=0;miss=0;blank_fp=0;blank=0;contamination=0
        for f,r in zip(dataset['frames'],rows):
            assert (f['id'],f['mono_ms'],f['expected_text'])==(r['id'],r['mono_ms'],r['expected_text'])
            a,b=key(r['expected_text']),key(r['ocr_text']);e=edits(a,b)
            assert e[0]==r['edit_distance'],(r['id'],e,r['edit_distance'])
            total=[u+v for u,v in zip(total,e)];chars+=len(a);ser+=a!=b
            raw_edits+=edits(r['expected_text'],r['ocr_text'])[0];raw_chars+=len(r['expected_text']);raw_ser+=r['expected_text']!=r['ocr_text']
            miss+=bool(a) and not b;blank+=not a;blank_fp+=not a and bool(b)
            labels=[x['text'] for x in f.get('text_boxes',[]) if x['role']!='caption']
            contamination+=any(key(label) and b.count(key(label))>a.count(key(label)) for label in labels)
        times=[];excluded=[]
        for r in runs:
            skip={0}
            if mode in ['hybrid','v6_rec']:
                first=next((i for i,f in enumerate(r['frames']) if f['stages']['bands']),None)
                if first is not None:skip.add(first)
            else:
                previous=(0,0)
                for i,f in enumerate(r['frames']):
                    m=f['stages']['engine_metrics'];current=(m['detector_init_ns'],m['recognizer_init_ns'])
                    if current!=previous:skip.add(i)
                    previous=current
            excluded.append([r['frames'][i]['id'] for i in sorted(skip)])
            times.extend(f['ocr_processing_ms'] for i,f in enumerate(r['frames']) if i not in skip)
        o={'cer':total[0]/chars if chars else None,'edits':total,'reference_characters':chars,'ser':ser/len(rows),'error_frames':ser,
           'raw_cer':raw_edits/raw_chars if raw_chars else None,'raw_ser':raw_ser/len(rows),
           'complete_misses':miss,'caption_frames':len(rows)-blank,'blank_false_positives':blank_fp,'blank_frames':blank,
           'known_noncaption_contamination_frames':contamination,'warm_pipeline':timing(times),'warm_excluded_ids':excluded,
           'all_pipeline':timing([f['ocr_processing_ms'] for r in runs for f in r['frames']]),'cold_first_frame_ms':[r['frames'][0]['ocr_processing_ms'] for r in runs],
           'sampled_peak_pss_kb':[r['sampled_peak_pss_kb'] for r in runs],
           'vmhwm_kb':[int(re.search(r'VmHWM:\s+(\d+)',r['process_status'])[1]) for r in runs],
           'ocr_jobs':[sum(f['stages']['ocr_jobs'] for f in r['frames']) for r in runs],
           'speech_requests':[len(r['speech']) for r in runs],
           'repeat_changed_frames':[a['id'] for a,b in zip(*[r['frames'] for r in runs]) if a['ocr_text']!=b['ocr_text']],
           'engine_class':runs[0]['engine_class'],'apk_sha256':runs[0]['apk_sha256']}
        if 'engine_metrics' in rows[-1]['stages']:o['engine_metrics']=rows[-1]['stages']['engine_metrics']
        out['modes'][mode]=o
    components=json.loads(Path(f'results/{name}-components.json').read_text());out['initialization']=components['initialization'];det={};recognition={}
    for backend in ['mlkit','v6']:
        truth=proposals=tp=0;overlaps=[];best=[];times=[];cold=None;boxframes=0;captiontruth=captiontp=0
        for f,r in zip(dataset['frames'],components['frames']):
            if 'text_boxes' not in f:continue
            boxframes+=1;gt=[x['box'] for x in f['text_boxes']];p=r[backend+'_boxes']
            if 'detection_roi' in f:
                z=f['detection_roi'];p=[b for b in p if z[0]<=(b[0]+b[2])/2<=z[2] and z[1]<=(b[1]+b[3])/2<=z[3]]
            for z in f.get('detection_ignore_regions',[]):
                p=[b for b in p if not(z[0]<=(b[0]+b[2])/2<=z[2] and z[1]<=(b[1]+b[3])/2<=z[3])]
            match=matches(gt,p,.5);truth+=len(gt);proposals+=len(p);tp+=len(match);overlaps.extend(x[2] for x in match)
            best.extend(max([iou(a,b) for b in p],default=0) for a in gt)
            caps=[x['box'] for x in f['text_boxes'] if x['role']=='caption'];captiontruth+=len(caps);captiontp+=len(matches(caps,p,.5))
            if cold is None:cold=r[backend+'_detection_ms'][0]
            times.extend(r[backend+'_detection_ms'][1:])
        det[backend]={'frames':boxframes,'truth_boxes':truth,'predicted_boxes':proposals,'tp_at_iou_0_5':tp,'recall':tp/truth if truth else None,'precision':tp/proposals if proposals else None,
            'matched_mean_iou':statistics.mean(overlaps) if overlaps else None,'mean_best_iou_including_misses':statistics.mean(best) if best else None,'caption_recall':captiontp/captiontruth if captiontruth else None,
            'warm_inference':timing(times),'first_inference_ms':cold}
    for backend in ['v5','v6']:
        total=chars=rawtotal=rawchars=count=0;times=[];cold=None;errors=[]
        for r in components['frames']:
            for c in r.get('recognition_crops',[]):
                a,b=key(c['expected_text']),key(c[backend+'_text']);distance=edits(a,b)[0];total+=distance;chars+=len(a)
                rawtotal+=edits(c['expected_text'],c[backend+'_text'])[0];rawchars+=len(c['expected_text']);count+=1
                if cold is None:cold=c[backend+'_ms'][0]
                times.extend(c[backend+'_ms'][1:])
                if distance:errors.append({'frame_id':r['id'],'expected':c['expected_text'],'actual':c[backend+'_text'],'edits':distance})
        recognition[backend]={'crops':count,'cer':total/chars if chars else None,'edit_distance':total,'reference_characters':chars,'raw_cer':rawtotal/rawchars if rawchars else None,'warm_inference':timing(times),'first_inference_ms':cold,'errors':errors}
    out['detection']=det;out['recognition']=recognition;summary['datasets'][name]=out
    pairs={}
    for a,b in [('hybrid','v6_rec'),('hybrid','v6_det'),('v6_rec','v6_both'),('v6_det','v6_both')]:
        x,y=out['modes'][a],out['modes'][b];pairs[a+'->'+b]={'cer_percentage_points':100*(y['cer']-x['cer']),'ser_percentage_points':100*(y['ser']-x['ser']),
            'median_pipeline_ms_delta':y['warm_pipeline']['median_ms']-x['warm_pipeline']['median_ms'],'edits_delta':y['edits'][0]-x['edits'][0]}
    pairs['interaction_cer_percentage_points']=100*(out['modes']['v6_both']['cer']-out['modes']['v6_det']['cer']-out['modes']['v6_rec']['cer']+out['modes']['hybrid']['cer'])
    summary['pairs'][name]=pairs
Path('results/summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({n:{m:{k:v for k,v in o.items() if k in ['cer','ser','complete_misses','blank_false_positives','warm_pipeline']} for m,o in d['modes'].items()} for n,d in summary['datasets'].items()},ensure_ascii=False,indent=2))
