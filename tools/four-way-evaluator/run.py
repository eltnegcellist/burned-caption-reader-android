#!/usr/bin/env python3
"""Balanced fresh-process four-mode replay plus independent model diagnostics."""
import argparse, hashlib, json, subprocess, sys, datetime
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('datasets',nargs='+',type=Path)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--adb',required=True)
p.add_argument('--device',required=True)
p.add_argument('--label',required=True)
p.add_argument('--passes',type=int,default=2)
p.add_argument('--modes',nargs='+',choices=['hybrid','v6_rec','v6_det','v6_both','e_mlkit_v6'],default=['hybrid','v6_rec','v6_det','v6_both'])
p.add_argument('--record-environment',action='store_true')
p.add_argument('--rotate-order',action='store_true',help='Cyclic order, each mode occupies each position once over three passes')
p.add_argument('--skip-components',action='store_true')
p.add_argument('--component-mode',choices=['four-way-components','bd-components'],default='four-way-components')
a=p.parse_args()
if not a.device.startswith('emulator-') or a.passes<1:p.error('Explicit emulator and positive passes required')
root=Path(__file__).resolve().parents[2]
a.output.mkdir(parents=True,exist_ok=True)
package='jp.hidemaru.burnedcaptionreader'
def adb(*args,**kwargs):return subprocess.run([a.adb,'-s',a.device,*args],check=True,**kwargs)
def environment_snapshot():
    def output(command):
        r=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        return {'exit_code':r.returncode,'output':r.stdout.decode('utf-8',errors='replace')}
    return {'time_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'host_thermal':output(['pmset','-g','therm']),
        'devices':output([a.adb,'devices','-l']),
        'battery':output([a.adb,'-s',a.device,'shell','dumpsys','battery']),
        'thermal':output([a.adb,'-s',a.device,'shell','dumpsys','thermalservice'])}
for dataset in a.datasets:
    dataset=dataset.resolve();name=dataset.name
    digest=hashlib.sha256((dataset/'dataset.json').read_bytes()).hexdigest()
    for repeat in range(1,a.passes+1):
        modes=list(a.modes)
        if a.rotate_order:
            offset=(repeat-1)%len(modes);modes=modes[offset:]+modes[:offset]
        elif repeat%2==0:modes.reverse()
        for mode in modes:
            if hashlib.sha256((dataset/'dataset.json').read_bytes()).hexdigest()!=digest:raise RuntimeError('Manifest changed during comparison')
            before=environment_snapshot() if a.record_environment else None
            adb('shell','am','force-stop',package)
            with (a.output/f'{name}-{mode}-{repeat}.log').open('w') as log:
                subprocess.run([sys.executable,str(root/'tools/image-evaluator/run.py'),str(dataset),str(a.output/f'{name}-{mode}-{repeat}.json'),
                    '--adb',a.adb,'--device',a.device,'--label',a.label,'--mode','player-replay','--ocr-mode',mode],check=True,stdout=log,stderr=subprocess.STDOUT)
            if a.record_environment:
                (a.output/f'{name}-{mode}-{repeat}.environment.json').write_text(json.dumps({'before':before,'after':environment_snapshot()},indent=2)+'\n')
    for repeat in range(1,a.passes+1):
        for mode in a.modes:
            result=json.loads((a.output/f'{name}-{mode}-{repeat}.json').read_text())
            assert result['ocr_mode']==mode and result['input_manifest_sha256']==digest
            if mode in ['v6_rec','v6_both','e_mlkit_v6']:
                metrics=result['frames'][-1]['stages']['engine_metrics']
                assert metrics['recognizer_calls']>0, 'PP-OCRv6 recognition did not execute'
                assert metrics['detector_calls']==0 if mode in ['v6_rec','e_mlkit_v6'] else metrics['mlkit_calls']==0
                assert metrics['mlkit_calls']>0 if mode in ['v6_rec','e_mlkit_v6'] else metrics['detector_calls']>0
    if a.skip_components: continue
    # Last replay uploaded and checked the same images/labels to app-private storage.
    adb('shell','am','force-stop',package)
    adb('shell','run-as',package,'rm','-f','files/image-evaluation/components.json')
    log=adb('shell','am','instrument','-w','-r','-e','mode',a.component_mode,
        package+'.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation',stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout
    (a.output/f'{name}-components.log').write_bytes(log)
    if b'INSTRUMENTATION_CODE: -1' not in log or b'FAILED:' in log:raise RuntimeError('Independent component inference failed')
    result=json.loads(adb('exec-out','run-as',package,'cat','files/image-evaluation/components.json',stdout=subprocess.PIPE).stdout)
    manifest=json.loads((dataset/'dataset.json').read_text());assert result['dataset']==manifest['id']
    assert [f['id'] for f in result['frames']]==[f['id'] for f in manifest['frames']]
    result.update(input_manifest_sha256=digest,device_serial=a.device,label=a.label)
    (a.output/f'{name}-components.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
