#!/bin/bash
# Serial local OCR on independently reviewed, byte-identical saved PixelCopy inputs.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${1:?workspace root}"
export VSE_REPO="$PWD/upstream/vse" VIDEOCR_REPO="$PWD/upstream/videocr"
export VIDEOCR_PADDLE_BACKEND="$PWD/private/paddle-backend"
export PADDLE_PDX_CACHE_HOME="$PWD/private/videocr-model-cache" PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
export OMP_NUM_THREADS=1 QT_QPA_PLATFORM=offscreen
for film in elephants sintel; do
  manifest="private/open-movie-benchmark/$film/pixelcopy-dataset/dataset.json"
  for engine in vse videocr vision; do
    python=envs/vse/bin/python
    [[ "$engine" == videocr ]] && python=envs/videocr/bin/python
    for scope in manual-roi full-frame-engine; do
      out="outputs/open-movie-benchmark/$film/images-$engine-$scope"
      envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "$out-resource.json" -- "$python" "$SCRIPT_DIR/compare_images.py" "$manifest" --engine "$engine" --scope "$scope" --limit 70 --out "$out.json"
    done
  done
done
