"""Validate locally exported official GUI SRTs; retain interval timings, never invent OCR counters."""
import argparse,hashlib,json
from pathlib import Path
from score_srt import read_srt

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);a=p.parse_args();root=a.workspace.resolve();base=root/'outputs/open-movie-benchmark'
    observations=json.loads((base/'se-gui-observations.json').read_text());inputs=json.loads((base/'inputs.json').read_text())
    evidence={'version':'5.2.0 macOS arm64','status':'completed','gui_product_run':True,'local_ocr_only':True,'engine':'AppleVision','language':'ja-JP','films':{},'component_result_label':'se-source, separate from se-gui',
        'official_app_executable_sha256':sha(root/'upstream/Subtitle Edit.app/Contents/MacOS/SubtitleEdit'),
        'official_ffmpeg_sha256':sha(root/'upstream/Subtitle Edit.app/Contents/MacOS/ffmpeg')}
    for name,o in observations.items():
        folder=base/name;settings=json.loads((folder/'se-gui-settings.json').read_text());monitor=json.loads((folder/'se-gui-monitor.json').read_text())
        video=root/f'private/open-movie-benchmark/{name}/{name}-300s.mp4';film=next(f for f in inputs['films'] if f['id']==name)
        actual=sha(video);expected=film['video_sha256'];assert actual==expected,(name,'Video hash mismatch')
        assert sha(folder/'reference.srt')==film['reference_sha256'],(name,'Reference hash mismatch')
        assert len(read_srt(folder/'se-gui.srt'))==o['gui_lines_found']
        assert settings['Engine']=='AppleVision' and settings['AppleVisionLanguage']=='ja-JP' and settings['FixOcrErrors'] is False
        for key,value in {'FramesPerSecond':5,'ImageSimilarityPercent':92,'TextSimilarityPercent':80,'MaxGapMs':250,'MinDurationMs':250,'BrightnessMinimum':190,'MaxImageWidth':720}.items():assert settings[key]==value
        height=540 if name=='elephants' else 408
        assert round(settings['CropYPercent']*height/100)==height-180
        assert round(settings['CropHeightPercent']*height/100)==180
        rows=[r for r in monitor['readings'] if o['start_before_ms']/1000<=r['unix_seconds']<=o['first_complete_observation_after_ms']/1000];assert rows
        bounds=[(o['last_running_observation_before_ms']-o['start_after_ms'])/1000,(o['first_complete_observation_after_ms']-o['start_before_ms'])/1000]
        resource={'exit_code':None,'completion_verified_in_gui':True,'elapsed_seconds':None,'elapsed_seconds_bounds':bounds,
            'time_caveat':'GUI start-click bracket and last-running/first-complete UI observation bound processing duration. No exact elapsed timer; file chooser, operator idle before start and SRT save are excluded. Interval width prohibits close speed ranking.',
            'peak_sampled_tree_rss_bytes':max(r['tree_rss_bytes'] for r in rows),'baseline_tree_rss_bytes':rows[0]['tree_rss_bytes'],
            'rss_sampling_interval_ms':50,'rss_caveat':monitor['rss_caveat'],'settings':settings,
            'work_counts':{'ocr_groups_observed':o['ocr_groups_observed'],'ocr_calls':None,'mean_ocr_ms':None,'sampled_frames':None,'scheduled_scan_frames_calculated':1500,'non_ocr_sampled_frames':None,
                'caveat':'GUI OCR progress denominator counts groups, not calls. Verification/retry can add calls. 1500 is computed from duration*5fps; actual extracted-frame counter not captured.'}}
        (folder/'se-gui.json').write_text(json.dumps(resource,indent=2)+'\n')
        evidence['films'][name]={'video_sha256':actual,'srt_sha256':sha(folder/'se-gui.srt'),'gui_lines_found':o['gui_lines_found'],'settings':settings,'observations':o,'resources':resource}
    evidence['component_difference_caveat']='Same MP4/ROI/visible thresholds and ffmpeg binary. GUI JPEG q:v=2, fps then crop/scale; source adapter default JPEG quality, crop/scale then fps. GUI also verifies/retries alternate group frames. Differences are not a controlled rescue-only ablation.'
    (base/'subtitleedit-gui-status.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print('Official GUI inputs, settings, SRT event counts and bounded timings verified.')
if __name__=='__main__':main()
