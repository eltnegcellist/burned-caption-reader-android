#!/bin/bash
# Isolated local CPU setup. Pass workspace directory and a Python 3.12 executable.
set -euo pipefail
TASK_ROOT="${1:?workspace directory}"; PYTHON_BIN="${2:?Python 3.12 executable}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$TASK_ROOT/upstream" "$TASK_ROOT/envs" "$TASK_ROOT/private" "$TASK_ROOT/outputs"
cd "$TASK_ROOT"
clone_at() {
  local repo="$1" destination="$2" revision="$3"
  if [ ! -d "$destination/.git" ]; then git clone "$repo" "$destination"; fi
  git -C "$destination" checkout --detach "$revision"
}
clone_at https://github.com/YaoFANGUK/video-subtitle-extractor.git upstream/vse 85746f7df5bf85978fd05f3ca6ce66e321a87a72
clone_at https://github.com/timminator/VideOCR.git upstream/videocr 0078547b53609f2810b13eb602a4a67721d57952
clone_at https://github.com/timminator/PaddleOCR-Standalone.git upstream/paddle-standalone 6b9dca07636297b93fbb262b41af83caa2a13f4a
clone_at https://github.com/timminator/Fast-SSIM.git upstream/fast-ssim 8f61acb825d9d9f2a8d2ed1361ec471813ddbb31
clone_at https://github.com/SubtitleEdit/subtitleedit.git upstream/subtitleedit d8e3b8b41e856a896c541ce7e59b490a99c21196
"$PYTHON_BIN" -m venv envs/vse; "$PYTHON_BIN" -m venv envs/videocr
envs/vse/bin/python -m pip install --no-cache-dir -r "$SCRIPT_DIR/results/requirements-vse-lock.txt"
envs/videocr/bin/python -m pip install --no-cache-dir -r "$SCRIPT_DIR/results/requirements-videocr-lock.txt"
envs/videocr/bin/python -m pip install --no-deps -e upstream/paddle-standalone
curl -L --fail https://raw.githubusercontent.com/DLTcollab/sse2neon/60fc9391e378b58c60899791c0e9ee9cdaf43c08/sse2neon.h -o upstream/fast-ssim/src/sse2neon.h
"$PYTHON_BIN" - <<'PY'
from pathlib import Path
import hashlib
p=Path('upstream/fast-ssim/src/sse2neon.h')
assert hashlib.sha256(p.read_bytes()).hexdigest()=='0624a1270fc88a67e6b886cd0e33e322d93de4b3a760b3373dc3a66e713bd43f'
p=Path('upstream/fast-ssim/src/ssim_impl.hpp');s=p.read_text().replace('#include <emmintrin.h>','#include "sse2neon.h"').replace('#include <tmmintrin.h>','');p.write_text(s)
p=Path('upstream/fast-ssim/src/ssim_dispatch.cpp');s=p.read_text().replace('__builtin_cpu_supports("avx")','true').replace('__builtin_cpu_supports("avx2")','false').replace('__builtin_cpu_supports("fma")','false').replace('avx2_impl::','sse_impl::');p.write_text(s)
p=Path('private/paddle-backend');p.write_text('#!/bin/sh\nexec "'+str(Path('envs/videocr/bin/python').resolve())+'" -m paddleocr "$@" --enable_mkldnn false --cpu_threads 4 --device cpu\n');p.chmod(0o755)
PY
SSIM_LIB="$(envs/videocr/bin/python -c 'import fast_ssim, pathlib; print(pathlib.Path(fast_ssim.__file__).parent / "resources/libssim.so")')"
clang++ -dynamiclib -O3 -std=c++20 -pthread -DNDEBUG upstream/fast-ssim/src/ssim_dispatch.cpp upstream/fast-ssim/src/ssim_sse.cpp -o "$SSIM_LIB"
export PADDLE_PDX_CACHE_HOME="$PWD/private/videocr-model-cache" PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
envs/videocr/bin/python - <<'PY'
from paddleocr import PaddleOCR
PaddleOCR(text_detection_model_name='PP-OCRv6_small_det',text_recognition_model_name='PP-OCRv6_small_rec',use_doc_orientation_classify=False,use_doc_unwarping=False,use_textline_orientation=False,device='cpu',enable_mkldnn=False,cpu_threads=4)
PY
printf '%s\n' 'Local OCR installed. Install official Subtitle Edit ARM64 DMG for GUI/ffmpeg; see REPRODUCE.md for the source-component and Android tests.'
