"""Offline P0-P4: frozen OCR observations through production Java, independent SRT.

No images, OCR output, or reference text leave the local machine. The numeric
CSV/JSON is separate from private replay TSV. Time is desktop JVM component time.
"""
import argparse, base64, csv, hashlib, json, math, re, statistics, subprocess
from pathlib import Path
from collections import Counter

def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
def enc(s):return base64.b64encode(s.encode()).decode()
def dec(s):return base64.b64decode(s).decode()
def normalize(s,kind='NFKC',structure=False):
    import unicodedata
    s=unicodedata.normalize(kind,s).replace('\r','')
    return s if structure else re.sub(r'\s+','',s)
def historical_key(s):
    import unicodedata
    s=unicodedata.normalize('NFKC',s)
    return ''.join(c for c in s if not c.isspace() and not unicodedata.category(c).startswith('P'))
def distance(a,b):
    prev=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        cur=[i]
        for j,y in enumerate(b,1):cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
        prev=cur
    return prev[-1]
def quant(v):
    return {'n':len(v),'mean':statistics.mean(v) if v else None,'median':statistics.median(v) if v else None,
            'p95':sorted(v)[math.ceil(.95*len(v))-1] if v else None}
def refs(p):
    out=[]
    def ms(s):
        h,m,sec,milli=map(int,re.split('[:,]',s));return ((h*60+m)*60+sec)*1000+milli
    for block in re.split(r'\n\s*\n',p.read_text().strip()):
        a=block.splitlines()
        if len(a)>=3 and '-->' in a[1]:
            x,y=a[1].split(' --> ');out.append({'start':ms(x),'end':ms(y),'text':'\n'.join(a[2:])})
    return out
def expected(ref,t):return '\n'.join(r['text'] for r in ref if r['start']<=t<r['end'])
def serialize_row(r):
    return [str(r.get('block',0)),enc(r['text']),str(r.get('confidence',80))]+[str(r[k]) for k in ['left','top','right','bottom']]+[str(r.get('glyph_height',0)),str(r.get('model_probability',-1))]
def write_input(frames,images,p):
    lines=[]
    for i,f in enumerate(frames):
        s=f['stages'];lines.append('\t'.join(['F',str(i),str(f['mono_ms']),str(images/f['image']),str(not s['raw_rows']).lower(),str(s['strip_recovery'] is not None).lower()]))
        for tag,key in [('R','raw_rows'),('D','recognition_rows')]:
            for r in s[key]:
                parts=';'.join(','.join(serialize_row(v)) for v in r.get('separated_parts',[]))
                lines.append('\t'.join([tag]+serialize_row(r)+[parts]))
    p.write_text('\n'.join(lines)+'\n')
def parse(p):
    out={}
    for s in p.read_text().splitlines():
        a=s.split('\t');k=int(a[1])
        if a[0]=='F':out[k]={'ms':int(a[2]),'selected':dec(a[3]),'selection_us':float(a[4]),'parity':a[5]=='true',
          'old_layout':dec(a[6]),'new_layout':dec(a[7]),'compose_us':float(a[8]),'old_rows':int(a[9]),'new_rows':int(a[10]),'trace':[],'bands':[],'lane_evidence':[]}
        elif a[0]=='T':out[k]['trace'].append({'stage':a[2],'reason':a[3],'text':dec(a[4]),'top':float(a[5]),'bottom':float(a[6])})
        elif a[0]=='S':out[k]['bands'].append({'track':int(a[2]),'top':float(a[3]),'bottom':float(a[4]),'locked':a[5]=='true','text':dec(a[6])})
        elif a[0]=='L':out[k]['lane_evidence'].append({'text':dec(a[2]),'strong':a[3]=='true','upper':a[4]=='true','early':a[5]=='true','observations':int(a[6]),'transitions':int(a[7]),'age_ms':int(a[8])})
        elif a[0]=='H':out[k]['shadow']={'would_skip':a[2]=='true','reason':a[3],'probe_us':float(a[4]),'would_rescue':a[5]=='true','rescue_reason':a[6],'rescue_us':float(a[7])}
    return out
def score(fs,ref,outputs,field):
    result={}
    for kind in ['NFKC','NFC']:
        n=d=pos_d=0; exact=set();blank=blank_fp=0;line_exact=0;multi=0
        for i,f in enumerate(fs):
            gold=expected(ref,f['mono_ms']);pred=outputs[i][field];a=normalize(gold,kind,kind=='NFC');b=normalize(pred,kind,kind=='NFC')
            n+=len(a);e=distance(a,b);d+=e
            if a:pos_d+=e
            else:blank+=1;blank_fp+=bool(b)
            for j,r in enumerate(ref):
                if r['start']<=f['mono_ms']<r['end'] and normalize(r['text'],kind,kind=='NFC')==b:exact.add(j)
            if '\n' in gold:multi+=1;line_exact+=normalize(gold,kind,True)==normalize(pred,kind,True)
        result[kind]={'chars':n,'edits_including_blank':d,'cer_including_blank':d/n,'positive_only_cer':pos_d/n,
                     'exact_events':[i+1 for i in sorted(exact)],'exact_event_count':len(exact),'blank_frames':blank,'blank_nonempty_frames':blank_fp,
                     'multiline_frames':multi,'multiline_structure_exact_frames':line_exact}
    return result
def main():
    p=argparse.ArgumentParser();p.add_argument('--data-root',type=Path,required=True);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);a=p.parse_args()
    w=a.workspace.resolve();out=w/'outputs/ocr-p0-p5-experiment-20261013';out.mkdir(parents=True,exist_ok=True)
    private=out/'private';private.mkdir(exist_ok=True);logs=out/'logs';logs.mkdir(exist_ok=True)
    src=w/'repo/tools/mac-p0-p5/ObservationReplay.java'
    inventory=[];summary={};all_events=[];all_frames=[]
    # Production classes are compiled from two pinned, independent checkouts.
    for label,checkout in [('main','baseline-main'),('pr49','repo')]:
        b=out/('classes-'+label);b.mkdir(exist_ok=True);root=w/checkout/'app/src/main/java/jp/hidemaru/burnedcaptionreader'
        sources=list((root/'subtitle').glob('*.java'))+[root/'ocr'/v for v in ['OcrLine.java','OcrResult.java','SubtitleCropPlan.java']]+[src]
        if label=='pr49':sources.append(root/'ocr/LockedRegionRescue.java')
        run=subprocess.run([str(a.jdk/'bin/javac'),'-d',str(b),*map(str,sources)],capture_output=True,text=True)
        (logs/('javac-'+label+'.log')).write_text(run.stdout+run.stderr);run.check_returncode()
    for name in ['elephants','sintel']:
        saved=a.data_root/'outputs/open-movie-benchmark'/name;media=a.data_root/'private/open-movie-benchmark'/name
        data=json.loads((saved/'android-video.json').read_text());fs=data['frames'];ref=refs(media/'reference.srt')
        inventory.extend({'kind':kind,'film':name,'path':str(path),'sha256':sha(path)} for kind,path in [('video',media/f'{name}-300s.mp4'),('reference',media/'reference.srt'),('ocr_observations',saved/'android-video.json')])
        assert inventory[-3]['sha256']==data['video_sha256']
        for f in fs:
            path=media/'android-pixelcopy'/f['image'];actual=sha(path);assert actual==f['sha256'],path
            inventory.append({'kind':'dense_pixelcopy','film':name,'path':str(path),'sha256':actual,'mono_ms':f['mono_ms']})
        sparse=json.loads((media/'pixelcopy-dataset/dataset.json').read_text())
        for f in sparse['frames']:
            path=media/'pixelcopy-dataset'/f['image'];actual=sha(path);assert actual==f['sha256'],path
            inventory.append({'kind':'sparse_pixelcopy','film':name,'id':f['id'],'path':str(path),'sha256':actual})
        inp=private/(name+'.input.tsv');write_input(fs,media/'android-pixelcopy',inp)
        runs={}
        # P0/P1/P2 complete before P3/P4 replay. Three desktop repetitions measure scatter.
        for label in ['main','pr49']:
            repeats=[]
            for repeat in range(3):
                dest=private/f'{name}.{label}.{repeat}.tsv'
                with dest.open('w') as handle:
                    subprocess.run([str(a.jdk/'bin/java'),'-Djava.awt.headless=true','-cp',str(out/('classes-'+label)),
                      'ObservationReplay',str(inp),'false'],stdout=handle,check=True)
                repeats.append(parse(dest))
            runs[label]=repeats[0]
            timing=[quant([v['selection_us']/1000 for v in r.values()]) for r in repeats]
            summary.setdefault(name,{})[label]={'selection_metrics':score(fs,ref,runs[label],'selected'),
               'selection_time_ms_runs':timing,'compose_time_ms_runs':[quant([v['compose_us']/1000 for v in r.values()]) for r in repeats]}
        assert all(v['parity'] for v in runs['pr49'].values()),'Diagnostics changed selections'
        summary[name]['diagnostic_parity_frames']=len(fs)
        events=[]
        for j,r in enumerate(ref):
            indices=[i for i,f in enumerate(fs) if r['start']<=f['mono_ms']<r['end']]
            gold=historical_key(r['text']);raw_indices=[i for i in indices if gold in historical_key(fs[i]['stages']['raw_text'])]
            historical_empty=all(not historical_key(fs[i]['ocr_text']) for i in indices)
            known_loss=bool(raw_indices) and historical_empty
            row={'film':name,'event_id_1based':j+1,'start_ms':r['start'],'end_ms':r['end'],'frames':len(indices),
                 'raw_contains_correct_frames':len(raw_indices),'historical_selected_empty':historical_empty,'known_loss':known_loss,
                 'main_exact_candidate_frames':sum(historical_key(runs['main'][i]['selected'])==gold for i in indices),
                 'pr49_exact_candidate_frames':sum(historical_key(runs['pr49'][i]['selected'])==gold for i in indices),
                 'known_loss_matching':'legacy NFKC whitespace/punctuation removal; primary CER preserves punctuation',
                 'historical_trace':'unobserved'}
            counts=Counter()
            for i in raw_indices:
                for t in runs['pr49'][i]['trace']:
                    if t['stage']!='selected' and (historical_key(t['text']) in gold or gold in historical_key(t['text'])):
                        counts[t['stage']+'/'+t['reason']]+=1
            lane_counts=Counter()
            for i in raw_indices:
                for l in runs['pr49'][i]['lane_evidence']:
                    if gold not in historical_key(l['text']) and historical_key(l['text']) not in gold:continue
                    if not l['strong'] and not l['upper']:lane_counts['caption_shape_failed']+=1
                    elif not l['early'] and l['transitions']==0 and l['age_ms']>2500:lane_counts['provisional_static_expired']+=1
                    else:lane_counts['other_temporal_or_overlap']+=1
            row['replayed_trace_reasons']=dict(counts);row['predicate_evidence']=dict(lane_counts);events.append(row);all_events.append(row)
        summary[name]['events']=events
        summary[name]['known_losses']=sum(e['known_loss'] for e in events)
        summary[name]['known_losses_recovered']=sum(e['known_loss'] and e['pr49_exact_candidate_frames']>0 and e['main_exact_candidate_frames']==0 for e in events)
        summary[name]['p2']={'baseline_layout':score(fs,ref,runs['pr49'],'old_layout'),'composed_layout':score(fs,ref,runs['pr49'],'new_layout'),
                            'changed_frames':sum(v['old_layout']!=v['new_layout'] for v in runs['pr49'].values()),'extra_ocr_calls':0}
        # Exact production P3/P4 classes on saved RGB pixels and selection-derived trust.
        dest=private/f'{name}.shadow.tsv'
        with dest.open('w') as handle:
            subprocess.run([str(a.jdk/'bin/java'),'-Djava.awt.headless=true','-cp',str(out/'classes-pr49'),
              'ObservationReplay',str(inp),'true'],stdout=handle,check=True)
        shadows=parse(dest);last_actual_gold=None;skip_errors=[];skips=rescues=0;rescue_positive=rescue_blank=0;reasons=Counter()
        rawempty_positive=rawempty_blank=0
        for i,f in enumerate(fs):
            v=shadows[i];h=v['shadow'];gold=normalize(expected(ref,f['mono_ms']));error=h['would_skip'] and last_actual_gold is not None and last_actual_gold!=gold
            skips+=h['would_skip']
            if error:skip_errors.append(i)
            if not h['would_skip']:last_actual_gold=gold
            rescues+=h['would_rescue'];rescue_positive+=h['would_rescue'] and bool(gold);rescue_blank+=h['would_rescue'] and not gold
            if not f['stages']['raw_rows']:
                rawempty_positive+=bool(gold);rawempty_blank+=not gold;reasons[h['rescue_reason']]+=1
            all_frames.append({'film':name,'frame_index':i,'mono_ms':f['mono_ms'],'sha256':f['sha256'],'gold_present':bool(gold),
              'main_exact':normalize(runs['main'][i]['selected'])==gold,'pr49_exact':normalize(v['selected'])==gold,
              'would_skip':h['would_skip'],'independent_gold_false_skip':error,'skip_reason':h['reason'],'would_rescue':h['would_rescue'],
              'rescue_reason':h['rescue_reason'],'probe_us':h['probe_us'],'rescue_us':h['rescue_us']})
        summary[name]['p3']={'raw_empty_positive_frames':rawempty_positive,'raw_empty_blank_frames':rawempty_blank,'shadow_rescue_candidates':rescues,
          'candidates_on_positive_frames':rescue_positive,'candidates_on_blank_frames':rescue_blank,'raw_empty_reasons':dict(reasons),'probe_time_ms':quant([v['shadow']['rescue_us']/1000 for v in shadows.values()]),
          'model_mode':'saved A observations only; C/D actual cost measured separately if available','actual_additional_calls':0}
        summary[name]['p4']={'shadow_skip_candidates':skips,'candidate_fraction':skips/len(fs),'independent_gold_false_skips':len(skip_errors),
          'false_skip_frame_indices':skip_errors,'comparison_time_ms':quant([v['shadow']['probe_us']/1000 for v in shadows.values()]),'actual_saved_ocr_calls':0}
        summary[name]['sparse_detection_misses']=[f['id'] for f in json.loads((saved/'images-hybrid-full-frame-engine.json').read_text())['frames'] if f['expected_text'] and not f['ocr_text']]
        summary[name]['manual_roi_detection_misses']=[f['id'] for f in json.loads((saved/'images-hybrid-manual-roi.json').read_text())['frames'] if f['expected_text'] and not f['ocr_text']]
        print(name,'known losses',summary[name]['known_losses'],'P1 recovered',summary[name]['known_losses_recovered'],'P4 candidates/errors',skips,len(skip_errors),flush=True)
    (out/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    (out/'events.json').write_text(json.dumps(all_events,ensure_ascii=False,indent=2)+'\n')
    for name,rows in [('frames.csv',all_frames),('events.csv',[{**r,'replayed_trace_reasons':json.dumps(r['replayed_trace_reasons'])} for r in all_events])]:
        with (out/name).open('w',newline='') as h:
            c=csv.DictWriter(h,fieldnames=list(rows[0]));c.writeheader();c.writerows(rows)
if __name__=='__main__':main()
