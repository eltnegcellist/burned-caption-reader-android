"""Debug-only MP4 playback on an isolated emulator. Preserve every PixelCopy locally."""
import argparse, hashlib, json, subprocess, tarfile
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);p.add_argument('--adb',required=True);p.add_argument('--device',default='emulator-5560');a=p.parse_args()
    if not a.device.startswith('emulator-'):p.error('Emulator only; no Galaxy speed claim')
    root=a.workspace.resolve();pkg='jp.hidemaru.burnedcaptionreader'
    def adb(*cmd,**kw):return subprocess.run([a.adb,'-s',a.device,*cmd],check=True,**kw)
    for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        adb('install','-r',str(root/'benchmark'/apk))
    for name,height in [('elephants',540),('sintel',408)]:
        movie=root/f'private/open-movie-benchmark/{name}/{name}-300s.mp4'
        dest=root/f'outputs/open-movie-benchmark/{name}';dest.mkdir(parents=True,exist_ok=True)
        # Preserve a previous run before cleaning emulator-only test buffers.
        adb('shell','run-as',pkg,'mkdir','-p','files/video-evaluation')
        adb('shell',f"run-as {pkg} sh -c 'rm -f files/video-evaluation/results.json; rm -rf files/video-evaluation/images'")
        with movie.open('rb') as media:
            adb('shell',f"run-as {pkg} sh -c 'cat > files/video-evaluation/input.mp4'",stdin=media)
        with (dest/'android-video.log').open('w') as log:
            run=adb('shell','am','instrument','-w','-r','-e','mode','local-video','-e','height',str(height),
                    pkg+'.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation',stdout=log,stderr=subprocess.STDOUT)
        log=(dest/'android-video.log').read_text()
        if 'INSTRUMENTATION_CODE: -1' not in log or 'FAILED:' in log:raise RuntimeError('Local playback failed; no measurements accepted')
        result=json.loads(adb('exec-out','run-as',pkg,'cat','files/video-evaluation/results.json',stdout=subprocess.PIPE).stdout)
        if result['video_sha256']!=hashlib.sha256(movie.read_bytes()).hexdigest():raise ValueError('MP4 hash mismatch')
        result['device_serial']=a.device
        result['apk_sha256']=hashlib.sha256((root/'benchmark/app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
        (dest/'android-video.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        archive=root/f'private/open-movie-benchmark/{name}/android-pixelcopy.tar'
        with archive.open('wb') as out:adb('exec-out','run-as',pkg,'tar','-cf','-','-C','files/video-evaluation','images',stdout=out)
        images=archive.parent/'android-pixelcopy';images.mkdir(exist_ok=True)
        with tarfile.open(archive) as tar:tar.extractall(images,filter='data')
        for frame in result['frames']:
            if hashlib.sha256((images/frame['image']).read_bytes()).hexdigest()!=frame['sha256']:raise ValueError('Captured PNG hash mismatch')
        print(name,len(result['frames']),'actual local MP4 PixelCopy frames',flush=True)
if __name__=='__main__':main()
