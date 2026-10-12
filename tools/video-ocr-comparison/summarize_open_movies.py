"""Aggregate audited local files; publish numeric results/hashes, not film subtitle text."""
import argparse, hashlib, json, re, statistics
from pathlib import Path
from score_srt import read_srt, compare, norm, edits

def hashfile(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def android_metrics(d,ref):
    frames=d['frames'];matched=set();blank=0;false_blank=0;distance=0;chars=0;reads=[]
    for f in frames:
        events=[(i,r) for i,r in enumerate(ref) if r['start']<=f['mono_ms']<r['end']]
        expected='\n'.join(r['text'] for _,r in events)
        if not events:blank+=1;false_blank+=bool(norm(f['ocr_text']))
        for i,r in events:
            if norm(f['ocr_text'])==norm(r['text']):matched.add(i)
        distance+=edits(norm(expected),norm(f['ocr_text']));chars+=len(norm(expected))
        reads.append(f['ocr_processing_ms'])
    spoken=set();dupes=0;extra=0;wrong=0
    for s in d['speech']:
        events=[(i,r) for i,r in enumerate(ref) if r['start']<=s['mono_ms']<r['end']]
        if not events:extra+=1;continue
        i,r=events[0]
        if norm(s['text'])==norm(r['text']):
            dupes+=i in spoken;spoken.add(i)
        else:wrong+=1
    stage_counts=[]
    for i,r in enumerate(ref):
        fs=[f for f in frames if r['start']<=f['mono_ms']<r['end']]
        stage_counts.append({'reference_index':i,'reference_lines':len(r['text'].splitlines()),'sampled_frames':len(fs),
            'raw_exact_frames':sum(norm(f['stages']['raw_text'])==norm(r['text']) for f in fs),
            'raw_contains_caption_frames':sum(norm(r['text']) in norm(f['stages']['raw_text']) for f in fs),
            'selected_exact_frames':sum(norm(f['ocr_text'])==norm(r['text']) for f in fs),
            'selected_nonempty_frames':sum(bool(norm(f['ocr_text'])) for f in fs)})
    return {'frames':len(frames),'ocr_jobs':sum(f['stages']['ocr_jobs'] for f in frames),
      'mean_pipeline_ocr_ms_per_captured_frame':statistics.mean(reads),
      'median_pipeline_ocr_ms_per_captured_frame':statistics.median(reads),
      'sum_pipeline_ocr_ms':sum(reads),'elapsed_seconds':d['elapsed_seconds'],
      'sampled_peak_pss_kb':d['sampled_peak_pss_kb'],
      'missed_busy_sample_slots':d['missed_busy_sample_slots'],'pixelcopy_failures':d['pixelcopy_failures'],
      'blank_sample_count':blank,'blank_false_output_sample_count':false_blank,
      'blank_false_output_sample_fraction':false_blank/blank if blank else None,
      'frame_weighted_selected_text_cer':distance/chars if chars else None,
      'reference_events':len(ref),'events_with_at_least_one_exact_selected_frame':len(matched),
      'events_without_exact_selected_frame':sorted(set(range(len(ref)))-matched),
      'two_line_reference_events':sum(len(r['text'].splitlines())==2 for r in ref),
      'two_line_events_with_exact_selected_frame':sum(i in matched and len(r['text'].splitlines())==2 for i,r in enumerate(ref)),
      'event_stage_counts':stage_counts,
      'speech_emissions':len(d['speech']),'events_with_exact_in_interval_speech':len(spoken),
      'repeated_exact_speech_in_same_event':dupes,'speech_outside_reference_interval':extra,
      'speech_wrong_or_partial_in_reference_interval':wrong,
      'video_sha256':d['video_sha256'],'apk_sha256':d['apk_sha256'],
      'caveat':'Test activity playback, SurfaceView PixelCopy, existing hybrid/player components and simulated immediate TTS. Frame-weighted CER/blank sample rate are not SRT event CER/blank time rate; no SRT product or Galaxy speed claims.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);a=p.parse_args();root=a.workspace.resolve()
    base=root/'outputs/open-movie-benchmark';output={'scope':'local Japanese-subtitled Commons CG films, 300 seconds each',
        'input_manifest_sha256':hashfile(base/'inputs.json'),'desktop':{},'android':{},'images':{}}
    for name in ['elephants','sintel']:
        ref=read_srt(root/f'private/open-movie-benchmark/{name}/reference.srt');folder=base/name;output['desktop'][name]={}
        labels=['vse-80','vse-100','videocr-0.85','videocr-1.0','se-source','vse-auto']
        for label in labels:
            if not (folder/f'{label}.srt').exists():continue
            metrics=compare(ref,read_srt(folder/f'{label}.srt'),duration_ms=300000)
            # Local full output retains text. Published metrics retain pairing evidence without text.
            (folder/f'{label}-score.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2)+'\n')
            metrics['pairs']=[{k:v for k,v in pair.items() if k not in ['reference_text','prediction_text']} for pair in metrics['pairs']]
            resource=json.loads((folder/f'{label}.json').read_text());metrics['resources']={k:resource[k] for k in ['exit_code','elapsed_seconds','peak_sampled_tree_rss_bytes','rss_sampling_interval_ms','rss_caveat']}
            metrics['output_sha256']=hashfile(folder/f'{label}.srt')
            if label.startswith('videocr'):
                log=(folder/f'{label}.log').read_text();det=re.findall(r'pass on (\d+) filtered frame\(s\) stitched into (\d+) image grid',log)
                rec=re.findall(r'Performing OCR on image \d+ of (\d+)',log);removed=re.findall(r'Filtered out (\d+) redundant frame',log)
                metrics['work_counts']={'first_ssim_surviving_frames':int(det[-1][0]),'detector_grid_images':int(det[-1][1]),
                    'recognition_images':int(rec[-1]),'tight_box_discarded_frames':int(removed[-1]),
                    'stride_frames_omitted_calculated':3600,'first_ssim_discarded_calculated':3600-int(det[-1][0]),
                    'caveat':'Detector calls operate on stitched grids, recognition images are text crops; not equivalent to full-frame engine calls. Stride counters are calculations from 7200 frames and stride 2.'}
            if label=='se-source':
                c=json.loads((folder/'se-source.source.json').read_text());metrics['work_counts']={k:c[k] for k in ['sampled_frames','group_count','ocr_calls','non_ocr_sampled_frames']}
                metrics['work_counts']['mean_ocr_ms']=statistics.mean(r['ocr_ms'] for r in c['readings'])
                metrics['gui_product_run']=False
            if label.startswith('vse'):
                raw=folder/f'{label}-raw.txt'
                metrics['work_counts']={'raw_text_rows':len(raw.read_text().splitlines()) if raw.exists() else None,'ocr_calls':None,
                    'caveat':'Raw text row count excludes OCR jobs with empty results; it is not an OCR call counter.'}
            output['desktop'][name][label]=metrics
        native=folder/'android-video.json'
        if native.exists():output['android'][name]=android_metrics(json.loads(native.read_text()),ref)
        for file in folder.glob('images-*.json'):
            if file.name.endswith('resource.json'):continue
            data=json.loads(file.read_text());fs=data['frames']
            positive=[f for f in fs if norm(f['expected_text'])];blank=[f for f in fs if not norm(f['expected_text'])]
            text=lambda f:f.get('text',f.get('ocr_text',''))
            ms=[f.get('ocr_ms',f.get('ocr_processing_ms',0)) for f in fs]
            chars=sum(len(norm(f['expected_text'])) for f in fs)
            result={'images':len(fs),'positive_images':len(positive),'blank_images':len(blank),
                'image_cer_including_blank_insertions':sum(edits(norm(f['expected_text']),norm(text(f))) for f in fs)/chars if chars else None,
                'positive_only_cer':sum(edits(norm(f['expected_text']),norm(text(f))) for f in positive)/chars if chars else None,
                'exact_positive_images':sum(norm(f['expected_text'])==norm(text(f)) for f in positive),
                'false_positive_blank_images':sum(bool(norm(text(f))) for f in blank),
                'mean_ocr_ms':statistics.mean(ms),'median_ocr_ms':statistics.median(ms),
                'output_sha256':hashfile(file),'input_manifest_sha256':data.get('manifest_sha256',data.get('input_manifest_sha256'))}
            result['false_positive_blank_fraction']=result['false_positive_blank_images']/len(blank) if blank else None
            result['multiline']={str(lines):{'reference_images':sum(len(f['expected_text'].splitlines())==lines for f in positive),
                'normalized_exact_images':sum(len(f['expected_text'].splitlines())==lines and norm(f['expected_text'])==norm(text(f)) for f in positive),
                'raw_line_structure_exact_images':sum(len(f['expected_text'].splitlines())==lines and f['expected_text'].splitlines()==text(f).splitlines() for f in positive)} for lines in (2,3)}
            resource=file.with_name(file.stem+'-resource.json')
            if resource.exists():
                r=json.loads(resource.read_text());result['resources']={k:r[k] for k in ['exit_code','elapsed_seconds','peak_sampled_tree_rss_bytes','rss_caveat']}
            if 'sampled_peak_pss_kb' in data:result['sampled_peak_pss_kb']=data['sampled_peak_pss_kb']
            output['images'][name+'/'+file.stem]=result
    (base/'metrics.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:list(v) if isinstance(v,dict) else v for k,v in output.items()},ensure_ascii=False))
if __name__=='__main__':main()
