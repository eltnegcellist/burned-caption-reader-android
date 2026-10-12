"""Read-only RSS sampler for an already-running official GUI; no UI automation."""
import argparse,json,time
from pathlib import Path
import psutil
p=argparse.ArgumentParser();p.add_argument('--pid',type=int,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--stop',type=Path,required=True);a=p.parse_args()
proc=psutil.Process(a.pid);start=time.time();peak=0;samples=0;rows=[]
while not a.stop.exists() and time.time()-start<900:
    try: children=[proc]+proc.children(recursive=True)
    except psutil.Error: children=[]
    rss=0;cpu=0
    for x in children:
        try:rss+=x.memory_info().rss;ct=x.cpu_times();cpu+=ct.user+ct.system
        except psutil.Error:pass
    peak=max(peak,rss);samples+=1;rows.append({'unix_seconds':time.time(),'tree_rss_bytes':rss,'live_tree_cpu_seconds':cpu});time.sleep(.05)
a.out.write_text(json.dumps({'pid':a.pid,'monitor_start_unix_seconds':start,'monitor_end_unix_seconds':time.time(),'peak_sampled_tree_rss_bytes':peak,'samples':samples,'rss_sampling_interval_ms':50,'rss_caveat':'Includes already-open GUI plus live children; shared pages may be double-counted, short peaks missed, Apple Vision OS services excluded. Monitoring interval includes UI observation idle time, not processing duration.','readings':rows},indent=2)+'\n')
print(json.dumps({'samples':samples,'peak_mib':peak/1048576}))
