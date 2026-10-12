#!/bin/bash
# From the workspace root after setup. Run serially; no cloud OCR.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TASK_ROOT="${1:?workspace directory}"
cd "$TASK_ROOT"
export VSE_REPO="$PWD/upstream/vse" VIDEOCR_REPO="$PWD/upstream/videocr"
export VIDEOCR_PADDLE_BACKEND="$PWD/private/paddle-backend"
export PADDLE_PDX_CACHE_HOME="$PWD/private/videocr-model-cache" PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
export OMP_NUM_THREADS=1 QT_QPA_PLATFORM=offscreen
for number in 1 2 3; do
  envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "outputs/vse-control-$number.json" --cwd upstream/vse -- "$PWD/envs/vse/bin/python" "$SCRIPT_DIR/run_vse.py" "$PWD/private/controls/control-$number.mp4" --roi 0 350 960 190
  cp "private/controls/control-$number.srt" "outputs/vse-control-$number.srt"
  envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "outputs/videocr-control-$number.json" -- envs/videocr/bin/python "$SCRIPT_DIR/run_videocr_mac.py" --video_path "private/controls/control-$number.mp4" --output "outputs/videocr-control-$number.srt" --ocr_engine paddleocr --lang japan --use_gpu false --crop_x 0 --crop_y 350 --crop_width 960 --crop_height 190 --allow_system_sleep true
  envs/videocr/bin/python "$SCRIPT_DIR/score_srt.py" "private/controls/control-$number.reference.srt" "outputs/vse-control-$number.srt" "outputs/videocr-control-$number.srt" --duration-ms 300000 --out "outputs/control-$number-scores.json"
done
