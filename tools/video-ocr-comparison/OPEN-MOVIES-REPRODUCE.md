# 公開映画追試の再現手順

対象はMac ARM64の独立検証workspace。測定済みworkspaceでは媒体・venv・AVDを作り直さず、保存済み結果の再集計から始める。既存Android checkoutやPR29/31は変更しない。全OCRはローカル。以下の`WORKSPACE`・SDK・JDK・Gradle・.NETは自分の実際のパスに合わせる。

## 固定入力と依存

1. 本検証branchを`WORKSPACE/benchmark`へcloneする。`upstream`、`envs`、`private`、`outputs`も同じworkspaceに分離する。
2. 新規workspaceでは[既存の環境手順](REPRODUCE.md)と`setup_mac.sh WORKSPACE PYTHON_3_12`を使う。**既存venvへの上書き再実行はしない。** PaddleOCR 3.4.1/3.7.0の別環境、Paddle3.3.1、公式Fast-SSIMのSSE2NEON適合、PP-OCRv5/v6ローカルモデル、公式Subtitle Edit5.2.0 ARM64 appと同梱ffmpegを用意する。lockと固定Git SHAは同ディレクトリの`results/`および`open-movies/benchmark-provenance.json`。
3. SE部品は公式tag `d8e3b8b41e856a896c541ce7e59b490a99c21196`の8ファイルを未変更でリンクする。.NET10.0.401/SkiaSharp3.119.4でrestore/buildする。初回restoreは公式NuGet、以後`--no-restore`。`SeSource` propertyを`WORKSPACE/upstream/subtitleedit`へ設定する。`Program.cs`/`ImageExperiment.cs`等の新しいadapterも必要。
4. Android本番source/modelはPR29 `eff6b846d02d74e7995652903ecdcc97144d2baa`と一致することを確認する。別AVD/API36 ARM64、別GRADLE_USER_HOME、Temurin17.0.20.1とGradle8.13でdebug/app-test APKをbuildする。最新mainを基準に置き換えない。

```bash
cd "$WORKSPACE"
PYTHON=envs/vse/bin/python
SCRIPT=benchmark/tools/video-ocr-comparison
# 初回のみ --download：Commonsの配布ファイルと固定revisionの日本語字幕を取得
"$PYTHON" "$SCRIPT/fetch_open_movies.py" "$WORKSPACE" --download
# 保存済みファイルの照合だけなら --download を省略
"$PYTHON" "$SCRIPT/fetch_open_movies.py" "$WORKSPACE"
```

原本2本は合計約1.08GB。Commonsの正規配布URLを使用し、YouTubeダウンロードは行わない。HTTP429等は回避せず配布側が回復するまで待つ。revision、原本SHA、字幕SHAは`open-movies/{elephants,sintel}.download.json`。映像/字幕のライセンス・帰属・投稿履歴も元ページで確認する。字幕全文を公開GitHubへ保存しない。

## 5分動画とgold

```bash
"$PYTHON" "$SCRIPT/prepare_open_movie_benchmark.py" "$WORKSPACE"
```

先頭300秒、24fps、幅960、Hiragino Sans W4/24/outline2/margin20を焼き付ける。`private/open-movie-benchmark/{film}/`へMP4、参照SRT、代表PNG、review sheets、`dataset.json`を生成し、`outputs/open-movie-benchmark/inputs.json`へコマンド/hashを保存する。**生成直後のgoldは未確認**。全50字幕イベント・字幕なし20画像を目視し、元字幕と実画面の文字/改行が一致する場合のみ、manifestを`ground_truth_source:codex_visual_review, reference_verified_from_pixels:true, human_verified:false`へ変える。人が確認した場合は確認者の正確な記録を残す。

設定候補をOCR前に固定する：VSE80/100、VideOCR初段94・枠SSIM0.85/1.0、SE brightness190/image92/text80/fps5。Elephantsはdevelopment、Sintelは候補変更なしのheld-out。新しい候補をSintelの成績に合わせて選ぶ場合、そのSintelはheld-outではなくなる。

フォント・ffmpeg build・macOSが違うと描画bitstreamが変わり得る。`open-movies/benchmark-provenance.json`のffmpeg/font SHA・実際のfontselectを照合する。原本/SRTは固定hashを要求するが、生成MP4は実際のhashを保存し、全方式へその同一ファイルを渡す。異なる描画hashを同じ試験の再実行と偽らない。

## デスクトップ動画比較

```bash
DOTNET_CLI_HOME="$PWD/private/dotnet-home" NUGET_PACKAGES="$PWD/private/nuget" \
  private/dotnet/dotnet build "$SCRIPT/se-source/SeSource.csproj" --no-restore \
  -p:SeSource="$PWD/upstream/subtitleedit" -c Release
bash "$SCRIPT/run_open_movies.sh" "$WORKSPACE"
```

CPU OCRを順次実行する。手動ROIは下180px、SRT・raw・resources・scoresは`outputs/open-movie-benchmark/{film}/`。SEは公式Vision/brightness/group/line/timing部品で、GUI recovery/spellingを含まない。原本機能`Video→OCR burned-in subtitle`のGUI成績は、実際に同じMP4と設定でGUIを完走した場合だけ追加する。

VSE自動領域は別の実験。`run_vse.py VIDEO --auto-accept-region`をVSE repo cwdから呼び、watermark削除n、公式の提案字幕帯をyで受諾する。`.auto-region.json`・SRT・rawを別名で保存する。今回Elephantsのみ実行、Sintelのautoは未測定。手動ROI→VideoSubFinderと自動3fps経路は処理全体が異なる。自動帯判定だけの速度とは扱わない。VSEのatexit ProcessManager警告は残ったがexit0とSRTの生成を確認した。

## 公式Subtitle Edit GUI（部品実験と別記録）

1. 公式5.2.0 ARM64 appを起動し、`Video → OCR burned-in subtitle`で同じ `{film}-300s.mp4` を指定する。GUI操作はMacのロック解除が必要。
2. Apple Vision・Japanese (ja-JP)、5fps、brightness190、text similarity80、gap/min duration250ms、ASS tag off、Fix OCR errors offを設定する。保存設定のOCR項目のみを検証し、image similarity92・max width720を確認する。API鍵を含み得るSettings.json全体は公開しない。
3. ROIはElephants `960x180 (0,360)`、Sintel `960x180 (0,228)`。SintelのBottom thirdは136pxなので使わず、選択枠をドラッグして表示数値180pxを確認する。成功出力に合わせてSintelの設定を変更しない。
4. 既に起動している公式アプリPIDを確認し、`sample_gui_process.py --pid PID --out MONITOR.json --stop UNIQUE_STOP_FILE`を別の読み取りプロセスで実行する。stopファイルは初めに存在しない新しい名前を使う。50ms RSSのみを読む補助ツールで、GUIを操作しない。
5. Start OCRクリック前後の時刻、最後の処理中画面と最初の完了画面の時刻、進捗の分母と最終イベント数を記録する。終了観測後stopファイルを作成する。`elapsed_seconds`は精密タイマーがなければnullにし、区間と不確かさを保存する。UI待機・保存時間を解析時間に加えない。
6. 完了後OK、Saveで**別名**`se-gui-local.srt`へ保存し、`outputs/open-movie-benchmark/{film}/se-gui.srt`へコピーする。参照SRTを上書きしない。Save Asの名前欄へ絶対パスを入れるとmacOSがslashをcolonに置換する場合があるため、ファイル名とフォルダー選択を分ける。抽出後のSRTを編集しない。
7. `se-gui-settings.json`は今回の `subtitleedit-gui-status.json`の許可済みOCR設定項目だけを保存する。`se-gui-observations.json`とmonitorも保存し、`finalize_se_gui.py WORKSPACE`でMP4 hash、ROI、設定、実イベント数を検証する。既存SRTから同じ数値を再集計できる。

GUIはJPEG q:v2、fps→crop/scaleと別フレーム確認/救済を含む。部品adapterはJPEG既定品質、crop/scale→fpsで、救済を含まない。GUI/部品の差は単一技術の対照試験ではない。公式ソースとGUIを変更しない。再実行は新しい出力名・観測時刻で記録する。

## Androidの同一MP4再生

まず独立AVDをheadlessで起動し、`adb -s emulator-5560 shell getprop sys.boot_completed`が1になることを確認する。今回の起動引数は `-avd Benchmark_API_36 -no-window -no-snapshot-save -no-audio -gpu swiftshader_indirect -port 5560`。`ANDROID_AVD_HOME`/SDKも独立環境の実パスを使う。

```bash
"$GRADLE_BIN" -p benchmark --offline :app:assembleDebug :app:assembleDebugAndroidTest
envs/videocr/bin/python "$SCRIPT/run_android_open_movies.py" "$WORKSPACE" \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" --device emulator-5560
```

`JAVA_HOME`は分離JDK、`ANDROID_HOME`はSDK、`GRADLE_USER_HOME`はprivate配下を設定する。scriptはdebug/test APKと許可済みMP4を独立AVDへ転送し、debug-only Activityで再生。SurfaceView PixelCopy→既存hybrid/player部品へ入力し、全取得PNGとSHA・media_ms・各段階・発話コミット・PSSを保存する。各MP4のhash、tar復元した各PNGのhashも照合する。実TTS完了は即時simulation。動画タイマーはprepare/engine構築後、timestampはPixelCopy要求直前のMediaPlayer位置。SRTの製品出力・Galaxyの速度・YouTube再生成績ではない。

## 同一PixelCopy画像の比較

```bash
"$PYTHON" "$SCRIPT/prepare_pixelcopy_dataset.py" "$WORKSPACE"
```

OCR前に固定した代表時刻へ最も近い保存PixelCopyを選び、`private/open-movie-benchmark/{film}/pixelcopy-dataset/`へ生成する。**全review sheetsを独立に目視してから**goldを確認済みに変える。結果が良い画像だけ選ばない。各字幕中間/空白の時刻がgold区間に合うかscriptも検証する。

```bash
bash "$SCRIPT/run_pixelcopy_images.sh" "$WORKSPACE"
bash "$SCRIPT/run_se_pixelcopy_images.sh" "$WORKSPACE"
bash "$SCRIPT/run_android_pixelcopy_images.sh" "$WORKSPACE" \
  "$ANDROID_SDK_ROOT/platform-tools/adb" emulator-5560
```

前者はVSE/VideOCR/単純Visionをmanual-ROIとfull-frameで実行。SE scriptは公式Vision＋`AppleVisionTextLayout.Compose`を実行し、同じ観測の単純y-sortも診断用に保存する。Android scriptは`caption-crops --diagnostic-engine hybrid`と`hybrid-full-frame --diagnostic-engine hybrid`。全画像の転送後にAndroid上のSHAも検証する。主比較はhybrid/VSE/VideOCR/SE公式layout、単純Visionは追加のadapter診断。full-frameエンジンは字幕の意味選択を含まない。手動ROIもdetector＋recognizerで、純粋なdecoderのみの試験ではない。

## 集計・保存

```bash
envs/videocr/bin/python "$SCRIPT/summarize_open_movies.py" "$WORKSPACE"
envs/videocr/bin/python -m unittest discover -s "$SCRIPT" -p 'test*.py'
envs/videocr/bin/python -m unittest discover -s benchmark/tools/image-evaluator -p 'test*.py'
```

`metrics.json`は文字を除いた数値/時刻対応を出力する。各SRT/JSON/元SRT/PNG/commands/logはMacへ保持。公開保存には `publish_open_movie_results.py`を使い、設定/数値/hash/原本検証/短いケース記録とレポート・再現コードだけをコピーする。`git diff --cached --name-only`で画像・動画・字幕全文・APK・モデルを含まないことを再確認して、検証branch/draft PR32へ保存する。本番sourceやPR29/31のmerge/変更はしない。

1回ずつの測定値なので、repeat条件・モデルwarmup・OS資源差に留意する。数%の時間差で優劣を断定しない。SRTイベントCER、フレーム加重CER、画像CER、音声コミット取得率を合算しない。未測定項目は未測定と記す。
