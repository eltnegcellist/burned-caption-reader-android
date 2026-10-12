#!/bin/bash
# Official SE Vision and spatial layout, linked without modifying upstream sources.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${1:?workspace root}"
for film in elephants sintel; do
  for scope in manual-roi full-frame-engine; do
    out="outputs/open-movie-benchmark/$film/images-se-layout-$scope"
    envs/videocr/bin/python "$SCRIPT_DIR/measure.py" --out "$out-resource.json" -- private/dotnet/dotnet "$SCRIPT_DIR/se-source/bin/Release/net10.0/SeSource.dll" --images "private/open-movie-benchmark/$film/pixelcopy-dataset/dataset.json" "$out.json" "$scope"
  done
done
