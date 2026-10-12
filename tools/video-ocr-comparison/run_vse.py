"""Configure and invoke upstream VSE; run from its repository directory."""
import argparse
import multiprocessing
import os
import sys
import json
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('video',type=Path)
    p.add_argument('--roi',nargs=4,type=int,metavar=('X','Y','W','H'))
    p.add_argument('--text-similarity',type=int,default=80)
    p.add_argument('--auto-accept-region',action='store_true',help='Official full-frame area-frequency path; answer no to watermark removal, yes to its proposed subtitle region')
    a=p.parse_args();sys.path.insert(0,str(Path.cwd()))
    if a.roi and a.auto_accept_region:p.error('Automatic area experiment must not receive a manual ROI')
    from backend.config import config
    config.language.value='japan'; config.mode.value='fast'
    config.hardwareAcceleration.value=False; config.checkUpdateOnStartup.value=False
    config.thresholdTextSimilarity.value=a.text_similarity
    config.debugNoDeleteCache.value=True; config.videoSubFinderCpuCores.value=4
    # Spawned OCR worker reloads the same file. Persist only in the isolated upstream.
    config.save()
    from backend.main import SubtitleExtractor
    from backend.bean.subtitle_area import SubtitleArea
    extractor=SubtitleExtractor(str(a.video.resolve()))
    # Upstream's CLI placeholder lacks `self`; GUI normally replaces it.
    # Keep the registered ProcessManager, and replace only this no-op hook.
    extractor.manage_process=lambda pid: None
    if a.roi:
        x,y,w,h=a.roi;extractor.sub_area=SubtitleArea(y,y+h,x,x+w)
    if a.auto_accept_region:
        import builtins
        responses=iter(['n','y'])
        def answer(prompt):
            value=next(responses);print(prompt,value,flush=True);return value
        builtins.input=answer
        original=extractor.filter_scene_text
        def region():
            proposed=extractor._detect_subtitle_area()
            meta={'algorithm':'official frequency of observed OCR y extents; watermark removal declined; proposed subtitle area accepted',
                  'proposal':proposed,'subtitle_area_deviation_pixels':config.subtitleAreaDeviationPixel.value}
            a.video.with_suffix('.auto-region.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
            original()
        extractor.filter_scene_text=region
    extractor.run()

if __name__=='__main__':
    multiprocessing.set_start_method('spawn');main()
