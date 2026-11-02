(() => {
  'use strict';
  if (document.getElementById('vocr-probe-host')) return;
  const host=document.createElement('div');
  host.id='vocr-probe-host';
  host.style.cssText='all:initial;position:fixed;z-index:2147483646;bottom:90px;right:10px;font:14px system-ui';
  const shadow=host.attachShadow({mode:'closed'});
  const button=document.createElement('button');
  button.textContent='🔎 OCR比較';
  button.style.cssText='border:0;border-radius:30px;background:#5b21b6;color:white;padding:12px 16px;font:700 14px system-ui;box-shadow:0 3px 15px #0005';
  const panel=document.createElement('div');
  panel.style.cssText='display:none;position:fixed;z-index:2147483647;right:8px;bottom:8px;width:min(430px,calc(100vw - 16px));height:min(88dvh,800px);background:white;border-radius:14px;box-shadow:0 12px 40px #0007;overflow:hidden';
  const frame=document.createElement('iframe');
  frame.src=chrome.runtime.getURL('ui.html');
  frame.title='字幕OCR比較';
  frame.style.cssText='border:0;width:100%;height:100%';
  panel.append(frame);shadow.append(button,panel);
  (document.body||document.documentElement).append(host);
  const origin=new URL(frame.src).origin;
  const setOpen=v=>{panel.style.display=v?'block':'none';button.style.display=v?'none':'block'};
  button.addEventListener('click',()=>setOpen(true));
  const getVideo=()=>{
    const vs=[...document.querySelectorAll('video')].sort((a,b)=>Number(!b.paused)-Number(!a.paused));
    const v=vs.find(v=>v.readyState>=2&&v.videoWidth>0);
    if(!v)throw Error('再生中のYouTube動画がありません');
    return v;
  };
  window.addEventListener('message',e=>{
    if(e.source!==frame.contentWindow||e.origin!==origin||!e.data)return;
    if(e.data.type==='OCR_BENCH_CLOSE'){setOpen(false);return}
    if(e.data.type!=='OCR_BENCH_REQUEST_FRAME')return;
    const id=e.data.id;
    try{
      const v=getVideo(),scale=Math.min(1,1280/Math.max(v.videoWidth,v.videoHeight));
      const c=document.createElement('canvas');c.width=Math.round(v.videoWidth*scale);c.height=Math.round(v.videoHeight*scale);
      c.getContext('2d',{willReadFrequently:true}).drawImage(v,0,0,c.width,c.height);
      let out=c;
      if(e.data.crop!==false){
        const sx=Math.round(c.width*.06),sy=Math.round(c.height*.60),sw=Math.round(c.width*.88),sh=Math.round(c.height*.37);
        out=document.createElement('canvas');out.width=sw*2;out.height=sh*2;
        out.getContext('2d',{willReadFrequently:true}).drawImage(c,sx,sy,sw,sh,0,0,out.width,out.height);
      }
      const png=out.toDataURL('image/png');
      frame.contentWindow.postMessage({type:'OCR_BENCH_FRAME',id,ok:true,png,time:v.currentTime,width:out.width,height:out.height,source:[v.videoWidth,v.videoHeight]},origin);
    }catch(err){
      frame.contentWindow.postMessage({type:'OCR_BENCH_FRAME',id,ok:false,error:String(err?.message||err)},origin);
    }
  });
})();
