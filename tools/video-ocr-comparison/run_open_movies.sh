#!/bin/bash
# Prepared, visually verified, private authorized inputs only. Serial CPU runs.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${1:?workspace root}"
export VIDEOCR_REPO="$PWD/upstream/videocr" VSE_REPO="$PWD/upstream/vse"
export VIDEOCR_PADDLE_BACKEND="$PWD/private/paddle-backend"
export PADDLE_PDX_CACHE_HOME="$PWD/private/videocr-model-cache" PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
export OMP_NUM_THREADS=1 QT_QPA_PLATFORM=offscreen
for film in elephants sintel; do
  y=360; [[ "$film" == sintel ]] && y=228
  video="$PWD/private/open-movie-benchmark/$film/$film-300s.mp4"
  dest="$PWD/outputs/open-movie-benchmark/$film"
  mkdir -p "$dest"
  for similarity in 80 100; do
    envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "$dest/vse-$similarity.json" --cwd upstream/vse -- "$PWD/envs/vse/bin/python" "$SCRIPT_DIR/run_vse.py" "$video" --roi 0 "$y" 960 180 --text-similarity "$similarity"
    cp "${video%.mp4}.srt" "$dest/vse-$similarity.srt"
    cp "upstream/vse/output/$film-300s/subtitle/raw.txt" "$dest/vse-$similarity-raw.txt"
  done
  for similarity in 0.85 1.0; do
    envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "$dest/videocr-$similarity.json" -- envs/videocr/bin/python "$SCRIPT_DIR/run_videocr_mac.py" --tight-box-ssim "$similarity" --video_path "$video" --output "$dest/videocr-$similarity.srt" --ocr_engine paddleocr --lang japan --use_gpu false --crop_x 0 --crop_y "$y" --crop_width 960 --crop_height 180 --allow_system_sleep true
  done
  envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "$dest/se-source.json" -- "$PWD/private/dotnet/dotnet" "$SCRIPT_DIR/se-source/bin/Release/net10.0/SeSource.dll" "$PWD/upstream/Subtitle Edit.app/Contents/MacOS/ffmpeg" "$video" "$dest/se-source.srt" "$PWD/private/open-movie-benchmark/$film/se-frames" refine "crop=960:180:0:$y,scale=720:-2"
  envs/videocr/bin/python "$SCRIPT_DIR/score_srt.py" "private/open-movie-benchmark/$film/reference.srt" "$dest/vse-80.srt" "$dest/vse-100.srt" "$dest/videocr-0.85.srt" "$dest/videocr-1.0.srt" "$dest/se-source.srt" --duration-ms 300000 --out "$dest/scores.json"
done
