#!/bin/bash
# Run only after the isolated emulator is booted. No Galaxy performance claims.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${1:?workspace root}"
ADB_BIN="${2:?adb executable}"
DEVICE="${3:-emulator-5560}"
for film in elephants sintel; do
  dataset="private/open-movie-benchmark/$film/pixelcopy-dataset"
  for scope in manual-roi full-frame-engine; do
    mode=caption-crops
    [[ "$scope" == full-frame-engine ]] && mode=hybrid-full-frame
    envs/videocr/bin/python "$SCRIPT_DIR/../image-evaluator/run.py" "$dataset" "outputs/open-movie-benchmark/$film/images-hybrid-$scope.json" --adb "$ADB_BIN" --device "$DEVICE" --label "open-film-pixelcopy-hybrid" --mode "$mode" --diagnostic-engine hybrid
  done
done
