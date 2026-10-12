"""Run a local command; sample aggregate RSS of its process tree (not OS high-water RSS)."""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path
import psutil

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--cwd',type=Path)
    p.add_argument('command',nargs=argparse.REMAINDER)
    a=p.parse_args(); command=a.command
    if command and command[0]=='--': command=command[1:]
    if not command: p.error('command required')
    a.out.parent.mkdir(parents=True,exist_ok=True)
    log=a.out.with_suffix('.log'); start=time.perf_counter(); peak=0; samples=0
    with log.open('w') as f:
        child=subprocess.Popen(command,cwd=a.cwd,stdout=f,stderr=subprocess.STDOUT)
        proc=psutil.Process(child.pid)
        while child.poll() is None:
            total=0
            try: processes=[proc]+proc.children(recursive=True)
            except psutil.Error: processes=[]
            for current in processes:
                try: total+=current.memory_info().rss
                except psutil.Error: pass
            peak=max(peak,total); samples+=1; time.sleep(.05)
    result={'command':command,'cwd':str(a.cwd or Path.cwd()),'exit_code':child.returncode,
        'elapsed_seconds':time.perf_counter()-start,'peak_sampled_tree_rss_bytes':peak,
        'rss_sampling_interval_ms':50,'samples':samples,
        'rss_caveat':'sum of parent and live children RSS; shared pages may be double-counted; short peaks may be missed',
        'environment':{k:os.environ[k] for k in ['PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK','PADDLE_PDX_CACHE_HOME','OMP_NUM_THREADS','QT_QPA_PLATFORM'] if k in os.environ},
        'log':log.name}
    a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result))
    raise SystemExit(child.returncode)

if __name__=='__main__': main()
