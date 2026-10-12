"""Serial emulator-only A/D raw coarse and P3 oracle ROI feasibility measurements."""
import argparse, hashlib, json, subprocess
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);p.add_argument('--data-root',type=Path,required=True);p.add_argument('--adb',required=True);p.add_argument('--device',default='emulator-5562');a=p.parse_args()
    if not a.device.startswith('emulator-'):p.error('This runner is emulator-only')
    w=a.workspace.resolve();out=w/'outputs/ocr-p0-p5-experiment-20261013';pkg='jp.hidemaru.burnedcaptionreader'
    def adb(*cmd,**kwargs):return subprocess.run([a.adb,'-s',a.device,*cmd],check=True,**kwargs)
    for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        adb('install','-r',str(w/'repo'/apk))
    sparse=[]
    for film in ['elephants','sintel']:
        root=a.data_root/f'private/open-movie-benchmark/{film}/pixelcopy-dataset';ds=json.loads((root/'dataset.json').read_text())
        prior=json.loads((a.data_root/f'outputs/open-movie-benchmark/{film}/images-hybrid-manual-roi.json').read_text())
        missing={f['id'] for f in prior['frames'] if film=='elephants' and f['expected_text'] and not f['ocr_text']}
        for frame in ds['frames']:
            path=root/frame['image'];assert hashlib.sha256(path.read_bytes()).hexdigest()==frame['sha256']
            sparse.append({**frame,'film':film,'path':path,'prior_A_empty':frame['id'] in missing})
    # All inputs, labels, order frozen before inference. Separate oracle ROI set.
    for label,mode,limited,rows in [('coarse-A','hybrid',False,sparse),('coarse-D','v6_both',False,sparse),
                                 ('p3-oracle-4','v6_both',True,[f for f in sparse if f['prior_A_empty']])]:
        adb('shell','run-as',pkg,'mkdir','-p','files/mac-experiment/images')
        frames=[]
        for i,row in enumerate(rows):
            remote=f'images/{i}.png'
            with row['path'].open('rb') as h:adb('shell',f'run-as {pkg} sh -c "cat > files/mac-experiment/{remote}"',stdin=h)
            frames.append({k:v for k,v in {**row,'image':remote}.items() if k!='path'})
        dataset={'id':label,'reference_verified_from_pixels':True,'ground_truth_source':'codex_visual_review','human_verified':False,'frames':frames}
        manifest=out/'private'/f'{label}.dataset.json';manifest.write_text(json.dumps(dataset,ensure_ascii=False,indent=2)+'\n')
        adb('shell',f'run-as {pkg} sh -c "cat > files/mac-experiment/dataset.json"',input=json.dumps(dataset,ensure_ascii=False).encode())
        adb('shell','run-as',pkg,'rm','-f','files/mac-experiment/results.json')
        result=adb('shell','am','instrument','-w','-r','-e','ocr_mode',mode,'-e','limited',str(limited).lower(),
          pkg+'.test/jp.hidemaru.burnedcaptionreader.evaluation.MacOcrExperimentInstrumentation',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        log=result.stdout.decode();(out/'logs'/f'{label}.log').write_text(log)
        if 'INSTRUMENTATION_CODE: -1' not in log or 'FAILED:' in log:raise RuntimeError(log)
        output=json.loads(adb('exec-out','run-as',pkg,'cat','files/mac-experiment/results.json',stdout=subprocess.PIPE).stdout)
        output['input_manifest_sha256']=hashlib.sha256(manifest.read_bytes()).hexdigest();output['device_serial']=a.device
        for k,apk in [('apk_sha256','app/build/outputs/apk/debug/app-debug.apk'),('test_apk_sha256','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:output[k]=hashlib.sha256((w/'repo'/apk).read_bytes()).hexdigest()
        (out/'private'/f'{label}.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
        print(label,len(rows),'completed',flush=True)
if __name__=='__main__':main()
