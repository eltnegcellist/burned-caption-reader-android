# 再実行手順

本結果は自作合成コントロール。実験A/Bは未実行。Mac GUIのSubtitle Edit完全結果とも異なる。

## 既存Macで再実行

workspaceルートを`TASK_ROOT`、検証checkoutの`tools/video-ocr-comparison`を`BENCH_TOOLS`として使う。全実験を同時に走らせず、CPU負荷を揃える。

```bash
TASK_ROOT=/path/to/2026-10-12-codex-video-ocr-github-3-1
BENCH_TOOLS="$TASK_ROOT/benchmark/tools/video-ocr-comparison"
cd "$TASK_ROOT"
python3 -m unittest discover -s "$BENCH_TOOLS" -v
envs/videocr/bin/python "$BENCH_TOOLS/make_controls.py" private/controls \
  --font '/System/Library/Fonts/ヒラギノ角ゴシック W4.ttc'
bash "$BENCH_TOOLS/run_controls.sh" "$TASK_ROOT"
```

これは既存結果を上書きするため、再実行時は新しいworkspace/output保存先を使うか、先に結果を複製する。同じfont SHA、Python・AV・libx264の版を揃え、manifestのMP4/PNG SHAが一致することを確認する。MP4のビット一致を保証できない別環境では、新しい入力hashの別実験とする。

## 新規Macの分離環境

```bash
bash tools/video-ocr-comparison/setup_mac.sh /path/to/new-workspace /path/to/python3.12
```

このスクリプトは固定SHAの公式ソース・分離venv・依存lock・Mac用Fast-SSIM・PP-OCRv6公式モデルを用意する。Xcode command-line toolsのclang++、Git、curlが必要。Python3.14を代用しない。

Subtitle Edit GUI/ffmpegは[公式5.2.0リリース](https://github.com/SubtitleEdit/subtitleedit/releases/tag/v5.2.0)の`SubtitleEdit-macOS-ARM64.dmg`を使用し、workspaceの`upstream/Subtitle Edit.app`へ配置する。GUIではVideo → OCR burned-in subtitle、Apple Vision、ja-JP、同じROIを選ぶ。今回このGUI経路はロックで中断しているため、設定値とSRTを別結果に保存して初めて「GUI完走」とする。SeConvをその代替結果にしない。

## 固定ROI画像のエンジン試験

```bash
export VSE_REPO="$PWD/upstream/vse"
export PADDLE_PDX_CACHE_HOME="$PWD/private/videocr-model-cache"
export PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True QT_QPA_PLATFORM=offscreen OMP_NUM_THREADS=1
envs/vse/bin/python "$BENCH_TOOLS/compare_images.py" private/controls/control-1.manifest.json \
  --out outputs/images-vse-manual.json --engine vse --scope manual-roi
envs/videocr/bin/python "$BENCH_TOOLS/compare_images.py" private/controls/control-1.manifest.json \
  --out outputs/images-videocr-manual.json --engine videocr --scope manual-roi
envs/vse/bin/python "$BENCH_TOOLS/compare_images.py" private/controls/control-1.manifest.json \
  --out outputs/images-vision-manual.json --engine vision --scope manual-roi
```

OSのVision接続にはサンドボックス外での実行が必要な場合がある。`full-frame-engine`は字幕選択前の全テキスト検出。製品の自動字幕抽出と区別する。実画像manifestは`reference_verified_from_pixels:true`が必要で、OCR出力を正解に転用しない。

## しきい値の切り分け

実行JSONのcommandを正本にする。

- VSE：`run_vse.py ... --roi 0 350 960 190 --text-similarity 100`。既定は80。生成SRTを実験ごとに別名でコピー。
- VideOCR：`run_videocr_mac.py ... --ssim_threshold 99`は初段のみ、`--tight-box-ssim 1.0`は二段目のみ。後者は計測用アダプターの引数で公式CLIの引数ではない。既定の二段目は0.85。
- 実行前に`VIDEOCR_REPO`と`VIDEOCR_PADDLE_BACKEND`を設定する。`run_controls.sh`に同じ環境設定がある。

## Subtitle Edit公式ソースの補助実験

公式.NET SDK10.0.401を`private/dotnet/`へ展開し、公式release metadataのSHA-512と照合する。SkiaSharp3.119.4は.NET復元時に取得。公式SE source SHAとリンク8ファイルのhashはprovenanceに保存。

```bash
export DOTNET_CLI_HOME="$PWD/private/dotnet-home" NUGET_PACKAGES="$PWD/private/nuget"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
private/dotnet/dotnet build "$BENCH_TOOLS/se-source/SeSource.csproj" \
  -p:SeSource="$PWD/upstream/subtitleedit" -c Release
envs/videocr/bin/python "$BENCH_TOOLS/measure.py" --out outputs/se-source-control-1.json -- \
  private/dotnet/dotnet "$BENCH_TOOLS/se-source/bin/Release/net10.0/SeSource.dll" \
  'upstream/Subtitle Edit.app/Contents/MacOS/ffmpeg' private/controls/control-1.mp4 \
  outputs/se-source-control-1.srt private/se-frames-1
```

2、3でも同じコマンドの番号を置換する。8ファイルは公式ソースを変更せずリンクし、ProgramとFfmpegGeneratorは外側の実行アダプター。GUI/viewmodelの救済やスペル補正を省いている。結果をSubtitle Edit製品の完全成績と呼ばない。

時刻補正は、ffmpegで`-t 30 -vf tpad=start_duration=0.1`を使ってcontrol-1から別MP4を作り、参照の最初3イベントを+100msにする。上のコマンドへ末尾`refine`を追加する。`source.json`のcoarseLinesとSRTを同じ参照で採点する。補正の判断コードも公式の未変更ソース。

## Android hybrid

JDK17・Gradle8.13・SDK36、独立GRADLE_USER_HOMEで本番無変更のcheckoutをビルドする。AVDは旧AVDの独立コピーを使った。元AVDを書き換えない。GUI用Qtが欠けたため、`-no-window -no-snapshot-save -no-audio -gpu swiftshader_indirect -port 5560`で起動した。

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/image-evaluator/run.py /path/to/verified-dataset outputs/hybrid.json \
  --adb /path/to/sdk/platform-tools/adb --device emulator-5560 \
  --mode caption-crops --diagnostic-engine hybrid --label APP_SOURCE_SHA
```

manifestはimage SHA、時刻、expected_text、caption_regions、manual_caption_regionsを必要とする。共通ROIのnormalized rectangleは`[0,350/540,1,1]`で、空字幕でも同じ範囲を指定。`diagnostic-engine hybrid`はテスト専用で`hybrid.refine`を呼び、通常アプリのOCR選択を変更しない。player-replayは既存hybrid入口をそのまま利用する。

130フレーム試験は、control-1の最初100秒の静止画像から各字幕を480ms間隔8回、空白を480ms間隔5回繰り返す**合成の状態テスト**。MP4再生・PixelCopy・実機速度・実動画イベント漏れ率を測る試験ではない。

## 採点と保全

```bash
python3 "$BENCH_TOOLS/score_srt.py" reference.srt candidate.srt \
  --duration-ms 300000 --out scores.json
```

normalized CER/raw CER、正しい取得と時刻対応率、空白時間への出力、統合/分割、全対応詳細を確認する。差分例は元画像と照合する。入力hash・参照・予測・設定を保存し、再現性を確認するまで画像や中間物を削除しない。公開GitHubには動画、画像、AVD、APK、モデル、個人情報を含めない。
