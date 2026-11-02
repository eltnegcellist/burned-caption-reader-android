#!/usr/bin/env python3
"""Build Vivaldi Android MV3 OCR benchmark as a locally bundled ZIP."""
from pathlib import Path
import json, hashlib, shutil, subprocess, tempfile, tarfile, gzip, urllib.request, zipfile, time

BASE=Path(__file__).resolve().parent.parent
NODE=BASE/'node_modules'
DST=BASE/'dist'/'vivaldi-youtube-ocr-benchmark-v0.3'
ASSETS=DST/'assets'
MODELS=['PP-OCRv6_small_det','PP-OCRv6_small_rec']
PADDLE='https://huggingface.co/PaddlePaddle'

def download(url, dst):
    dst.parent.mkdir(parents=True,exist_ok=True)
    error=None
    for n in range(3):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 VivaldiOCRBenchmark/0.3'})
            with urllib.request.urlopen(req,timeout=120) as response, dst.open('wb') as handle:
                shutil.copyfileobj(response,handle)
            if dst.stat().st_size < 1000: raise ValueError('unexpectedly short download')
            print('downloaded',url,dst.stat().st_size,flush=True)
            return
        except Exception as ex:
            error=ex
            dst.unlink(missing_ok=True)
            print('download attempt',n+1,'failed:',url,ex,flush=True)
            time.sleep(2)
    raise RuntimeError('Asset unavailable: '+url+' : '+str(error))

def copy(src, dst):
    if not src.is_file(): raise FileNotFoundError(src)
    dst.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(src,dst)

def model(name):
    out=ASSETS/(name+'.tar')
    # Official upstream repository, license Apache-2.0; put real ONNX/YML files in a simple tar.
    with tempfile.TemporaryDirectory() as t:
        folder=Path(t)
        for file in ('inference.onnx','inference.yml'):
            download(PADDLE+'/'+name+'_onnx/resolve/main/'+file,folder/file)
        yaml=(folder/'inference.yml').read_text(encoding='utf-8')
        if 'model_name: '+name not in yaml: raise RuntimeError('unexpected model: '+name)
        with tarfile.open(out,'w') as archive:
            for file in ('inference.onnx','inference.yml'):
                archive.add(folder/file,arcname=file)
        with tarfile.open(out) as archive:
            assert {'inference.onnx','inference.yml'} <= set(archive.getnames())

def main():
    if not NODE.exists(): raise RuntimeError('Run npm install before building')
    if DST.exists():shutil.rmtree(DST)
    ASSETS.mkdir(parents=True)
    copy(BASE/'manifest.json',DST/'manifest.json')
    for name in ('content.js','ui.html','ui.js'):copy(BASE/'src'/name,DST/name)
    subprocess.run([str(NODE/'.bin'/'esbuild'),str(BASE/'src'/'paddle-entry.js'),'--bundle','--platform=browser','--format=esm','--target=es2022','--log-level=warning','--outfile='+str(DST/'paddle.js')],check=True,cwd=BASE)
    copy(NODE/'tesseract.js'/'dist'/'tesseract.min.js',ASSETS/'tesseract.min.js')
    copy(NODE/'tesseract.js'/'dist'/'worker.min.js',ASSETS/'worker.min.js')
    for item in (NODE/'tesseract.js-core').glob('tesseract-core*'):
        if item.is_file() and item.suffix in ('.js','.wasm'):copy(item,ASSETS/'tess-core'/item.name)
    if not list((ASSETS/'tess-core').glob('*.js')):raise RuntimeError('Tesseract core missing')
    ort=NODE/'onnxruntime-web'/'dist'
    for item in ort.glob('ort-wasm*'):
        if item.is_file() and item.suffix in ('.wasm','.mjs','.js'):copy(item,ASSETS/'ort'/item.name)
    if not list((ASSETS/'ort').glob('*.wasm')):raise RuntimeError('ONNX Runtime WASM missing')
    for mode in ('fast','best'):
        with tempfile.TemporaryDirectory() as t:
            raw=Path(t)/'jpn.traineddata'
            download('https://raw.githubusercontent.com/tesseract-ocr/tessdata_'+mode+'/main/jpn.traineddata',raw)
            path=ASSETS/('tess-'+mode)/'jpn.traineddata.gz'
            path.parent.mkdir(parents=True,exist_ok=True)
            with raw.open('rb') as src, gzip.open(path,'wb',compresslevel=8) as dst:shutil.copyfileobj(src,dst)
    for name in MODELS:model(name)
    lic=DST/'licenses';lic.mkdir()
    for package,label in [('tesseract.js','Tesseract-JS'),('tesseract.js-core','Tesseract-Core'),('onnxruntime-web','ONNX-Runtime-Web'),('@paddleocr/paddleocr-js','PaddleOCR-JS')]:
        base=NODE/package
        choices=[f for f in base.iterdir() if f.is_file() and f.name.lower().startswith(('license','copying'))]
        if choices:copy(choices[0],lic/(label+'-'+choices[0].name))
    (lic/'MODEL-SOURCES.txt').write_text('PaddlePaddle/PP-OCRv6_small_det_onnx and PP-OCRv6_small_rec_onnx: Apache-2.0, huggingface.co/PaddlePaddle\nTesseract jpn fast and best: github.com/tesseract-ocr/tessdata_fast and tessdata_best, Apache-2.0\n',encoding='utf-8')
    copy(BASE/'README.md',DST/'README.md')
    checks={}
    for f in sorted(DST.rglob('*')):
        if f.is_file():checks[str(f.relative_to(DST))]={'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
    (DST/'ASSET-MANIFEST.json').write_text(json.dumps(checks,indent=2,ensure_ascii=False),encoding='utf-8')
    target=BASE/'dist'/'Vivaldi_YouTube_OCR_Benchmark_v0.3.0.zip'
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
        for f in sorted(DST.rglob('*')):
            if f.is_file():z.write(f,arcname=str(f.relative_to(DST.parent)))
    with zipfile.ZipFile(target) as z:
        bad=z.testzip()
        if bad:raise RuntimeError('ZIP corrupt at '+bad)
    print('BUILT',target,'size',target.stat().st_size,flush=True)

if __name__=='__main__':main()
