"""Configure and invoke upstream VSE; run from its repository directory."""
import argparse
import multiprocessing
import os
import sys
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('video',type=Path)
    p.add_argument('--roi',nargs=4,type=int,metavar=('X','Y','W','H'))
    p.add_argument('--text-similarity',type=int,default=80)
    a=p.parse_args();sys.path.insert(0,str(Path.cwd()))
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
    extractor.run()

if __name__=='__main__':
    multiprocessing.set_start_method('spawn');main()
