#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
here=$root/tools/replay-evaluator
out=$here/out
rm -rf "$out"
mkdir -p "$out"
if command -v javac >/dev/null 2>&1; then
  compiler=javac
else
  compiler="java -m jdk.compiler/com.sun.tools.javac.Main"
fi
$compiler -d "$out" \
  "$root/tools/replay-evaluator/baseline/SubtitleEventManager.java" \
  "$root/app/src/main/java/jp/hidemaru/burnedcaptionreader/subtitle/RowSpeechLedger.java" \
  "$root/app/src/main/java/jp/hidemaru/burnedcaptionreader/subtitle/SubtitleEvent.java" \
  "$root/app/src/main/java/jp/hidemaru/burnedcaptionreader/subtitle/SubtitleNormalizer.java" \
  "$root/app/src/main/java/jp/hidemaru/burnedcaptionreader/subtitle/Similarity.java" \
  "$root/app/src/main/java/jp/hidemaru/burnedcaptionreader/subtitle/TrailingPunctuation.java" \
  "$here/src/ReplayEvaluator.java"
java -cp "$out" ReplayEvaluator "$here/replay-fixture.tsv"
