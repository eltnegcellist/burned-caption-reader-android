#!/usr/bin/env python3
"""Balanced fresh-process four-mode replay plus independent model diagnostics."""
import argparse, hashlib, json, subprocess, sys
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('datasets',nargs='+',type=Path)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--adb',required=True)
p.add_argument('--device',required=True)
p.add_argument('--label',required=True)
p.add_argument('--passes',type=int,default=2)
a=p.parse_args()
if not a.device.startswith('emulator-') or a.passes<1:p.error('Explicit emulator and positive passes required')
root=Path(__file__).resolve().parents[2]
a.output.mkdir(parents=True,exist_ok=True)
package='jp.hidemaru.burnedcaptionreader'
def adb(*args,**kwargs):return subprocess.run([a.adb,'-s',a.device,*args],check=True,**kwargs)
for dataset in a.datasets:
    dataset=dataset.resolve();name=dataset.name
    digest=hashlib.sha256((dataset/'dataset.json').read_bytes()).hexdigest()
    for repeat in range(1,a.passes+1):
        modes=['hybrid','v6_rec','v6_det','v6_both']
        if repeat%2==0:modes.reverse()
        for mode in modes:
            if hashlib.sha256((dataset/'dataset.json').read_bytes()).hexdigest()!=digest:raise RuntimeError('Manifest changed during comparison')
            adb('shell','am','force-stop',package)
            with (a.output/f'{name}-{mode}-{repeat}.log').open('w') as log:
                subprocess.run([sys.executable,str(root/'tools/image-evaluator/run.py'),str(dataset),str(a.output/f'{name}-{mode}-{repeat}.json'),
                    '--adb',a.adb,'--device',a.device,'--label',a.label,'--mode','player-replay','--ocr-mode',mode],check=True,stdout=log,stderr=subprocess.STDOUT)
    # Last replay uploaded and checked the same images/labels to app-private storage.
    adb('shell','am','force-stop',package)
    adb('shell','run-as',package,'rm','-f','files/image-evaluation/components.json')
    log=adb('shell','am','instrument','-w','-r','-e','mode','four-way-components',
        package+'.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation',stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout
    (a.output/f'{name}-components.log').write_bytes(log)
    if b'INSTRUMENTATION_CODE: -1' not in log or b'FAILED:' in log:raise RuntimeError('Independent component inference failed')
    result=json.loads(adb('exec-out','run-as',package,'cat','files/image-evaluation/components.json',stdout=subprocess.PIPE).stdout)
    manifest=json.loads((dataset/'dataset.json').read_text());assert result['dataset']==manifest['id']
    assert [f['id'] for f in result['frames']]==[f['id'] for f in manifest['frames']]
    result.update(input_manifest_sha256=digest,device_serial=a.device,label=a.label)
    (a.output/f'{name}-components.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
