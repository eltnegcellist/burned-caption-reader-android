"""Local engine adapters on frozen image bytes. Not product subtitle-selection pipelines."""
import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path
from PIL import Image
import numpy as np

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest',type=Path); p.add_argument('--out',type=Path,required=True)
    p.add_argument('--engine',choices=['vse','videocr','vision'],required=True)
    p.add_argument('--scope',choices=['manual-roi','full-frame-engine'],required=True)
    p.add_argument('--limit',type=int,default=20)
    a=p.parse_args();data=json.loads(a.manifest.read_text());start=time.perf_counter()
    if data.get('source')!='owned_synthetic_render' and not data.get('reference_verified_from_pixels'):
        p.error('Real images require independently pixel-verified ground truth')
    if a.engine=='vse':
        sys.path.insert(0,os.environ['VSE_REPO'])
        from backend.config import config
        config.language.value='japan';config.hardwareAcceleration.value=False
        from backend.tools.ocr import OcrRecogniser
        x=OcrRecogniser();x.recogniser=x.init_model()
        def recognize(image):
            boxes,readings=x.predict(np.array(image)[:,:,::-1].copy())
            return [{'text':v[0],'confidence':v[1],'polygon':box} for box,v in zip(boxes,readings)]
    elif a.engine=='videocr':
        from paddleocr import PaddleOCR
        root=Path(os.environ['PADDLE_PDX_CACHE_HOME'])/'official_models'
        x=PaddleOCR(text_detection_model_name='PP-OCRv6_small_det',text_detection_model_dir=str(root/'PP-OCRv6_small_det'),
            text_recognition_model_name='PP-OCRv6_small_rec',text_recognition_model_dir=str(root/'PP-OCRv6_small_rec'),
            use_doc_orientation_classify=False,use_doc_unwarping=False,use_textline_orientation=False,
            device='cpu',enable_mkldnn=False,cpu_threads=4,text_rec_score_thresh=0)
        def recognize(image):
            r=list(x.predict(np.array(image)[:,:,::-1].copy()))[0]
            values=[{'text':v,'confidence':float(c),'polygon':box.tolist()} for box,v,c in zip(r['rec_polys'],r['rec_texts'],r['rec_scores'])]
            return sorted(values,key=lambda v:(min(p[1] for p in v['polygon']),min(p[0] for p in v['polygon'])))
    else:
        import Foundation,Vision
        def recognize(image):
            import io
            buf=io.BytesIO();image.save(buf,format='PNG');raw=buf.getvalue()
            nsdata=Foundation.NSData.dataWithBytes_length_(raw,len(raw))
            req=Vision.VNRecognizeTextRequest.alloc().init()
            req.setRecognitionLevel_(Vision.VNRequestTextRecognitionLevelAccurate)
            req.setRecognitionLanguages_(['ja-JP']);req.setUsesLanguageCorrection_(True)
            handler=Vision.VNImageRequestHandler.alloc().initWithData_options_(nsdata,{})
            ok,error=handler.performRequests_error_([req],None)
            if not ok: raise RuntimeError(error)
            rows=[]
            for obs in req.results() or []:
                candidate=obs.topCandidates_(1)[0];box=obs.boundingBox()
                rows.append({'text':str(candidate.string()),'confidence':float(candidate.confidence()),
                    'normalized_box':[box.origin.x,1-box.origin.y-box.size.height,box.size.width,box.size.height]})
            return sorted(rows,key=lambda v:(v['normalized_box'][1],v['normalized_box'][0]))
    initialization_ms=(time.perf_counter()-start)*1000
    results=[]
    for frame in data['frames'][:a.limit]:
        path=a.manifest.parent/frame['image'];raw=path.read_bytes()
        if hashlib.sha256(raw).hexdigest()!=frame['sha256']:raise ValueError('Image hash changed')
        image=Image.open(path).convert('RGB');roi=None
        if a.scope=='manual-roi':
            roi=data.get('common_roi_pixels')
            if not roi:raise ValueError('Explicit frozen common ROI required')
            x0,y0,w,h=roi;image=image.crop((x0,y0,x0+w,y0+h))
        begin=time.perf_counter();rows=recognize(image);ms=(time.perf_counter()-begin)*1000
        text='\n'.join(row['text'] for row in rows)
        results.append({'id':frame['id'],'sha256':frame['sha256'],'roi':roi,
            'roi_rgb_sha256':hashlib.sha256(image.tobytes()).hexdigest(),'expected_text':frame['expected_text'],
            'text':text,'ocr_ms':ms,'rows':rows})
        print(frame['id'],f'{ms:.1f}ms',repr(text),flush=True)
    out={'engine':a.engine,'scope':a.scope,'source':data['source'],
        'product_pipeline':False,'pixelcopy':False if data['source']=='owned_synthetic_render' else data.get('pixelcopy'),
        'caveat':'Manual ROI still uses detector + recognizer. Full-frame engine does not implement semantic subtitle selection.',
        'initialization_ms':initialization_ms,'manifest_sha256':hashlib.sha256(a.manifest.read_bytes()).hexdigest(),'frames':results}
    a.out.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
