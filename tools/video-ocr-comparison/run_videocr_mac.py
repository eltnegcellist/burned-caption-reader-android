"""Mac CPU platform adapters around unmodified VideOCR algorithms. Cloud OCR forbidden."""
import os
import platform
import sys
import types
from pathlib import Path

def main():
    if platform.machine() != 'arm64' or sys.platform != 'darwin':
        raise SystemExit('This adapter is for macOS arm64 only')
    args=sys.argv[1:]
    tight_threshold=None
    if '--tight-box-ssim' in args:
        i=args.index('--tight-box-ssim');tight_threshold=float(args[i+1])
        del sys.argv[i+1:i+3];args=sys.argv[1:]
        if not 0 < tight_threshold <= 1: raise ValueError('Invalid tight-box threshold')
    if '--ocr_engine' not in args or args[args.index('--ocr_engine')+1]!='paddleocr':
        raise SystemExit('Explicit local --ocr_engine paddleocr required')
    if '--use_gpu' in args and args[args.index('--use_gpu')+1].lower()!='false':
        raise SystemExit('Mac adapter supports CPU only')
    cli=Path(os.environ['VIDEOCR_REPO']).resolve()/'CLI'
    sys.path.insert(0,str(cli))
    # The upstream AVX test only applies to x86 prebuilt binaries. The CPU backend
    # here is an installed native arm64 Paddle wheel, checked by executing inference.
    sys.modules['cpuid']=types.SimpleNamespace(cpuid=None,xgetbv=None)
    from videocr import utils
    import numpy as np
    import simplejpeg
    from videocr.utils import Frame
    def hardware_check(path,use_gpu):
        if use_gpu: raise ValueError('GPU unsupported')
        if not Path(path).is_file(): raise FileNotFoundError(path)
    def executable(name):
        if name=='paddleocr': return os.environ['VIDEOCR_PADDLE_BACKEND']
        if name=='chrome-lens': return '/cloud-ocr-disabled'
        raise ValueError(name)
    def models(lang,server):
        if lang!='japan' or server: raise ValueError('Pinned small Japanese models only')
        root=Path(os.environ['PADDLE_PDX_CACHE_HOME'])/'official_models'
        return str(root/'PP-OCRv6_small_det'),str(root/'PP-OCRv6_small_rec'),'/unused-orientation-model'
    def encode(canvas,w,h,quality):
        return simplejpeg.encode_jpeg(np.frombuffer(canvas,dtype=np.uint8).reshape(h,w,3),quality=quality,colorspace='RGB')
    def decode(path):
        with open(path,'rb') as f: data=simplejpeg.decode_jpeg(f.read(),colorspace='RGB')
        h,w,_=data.shape;return path,Frame(data.tobytes(),h,w)
    utils.perform_hardware_check=hardware_check;utils.find_executable=executable
    utils.resolve_model_dirs=models
    # Upstream uses a custom simplejpeg fork whose bytes API has no arm64 wheel.
    # Stock arm64 simplejpeg uses ndarray; decoded pixels/quality are unchanged.
    utils.encode_frame_to_jpeg=encode;utils.decode_jpeg_to_frame=decode
    if tight_threshold is not None:
        original_group=utils.process_ssim_group
        def process_group(rects,frames,grids,threshold):
            return original_group(rects,frames,grids,tight_threshold)
        utils.process_ssim_group=process_group
    from videocr_cli import main as cli_main
    cli_main()

if __name__=='__main__': main()
