# E採用と「1回で発話」の維持（2026-10-13）

ユーザーの明示決定により、通常OCRをDから**E（ML Kit検出・粗認識＋PP-OCRv6 Small refine＋E字幕選別）**へ移行する製品変更を実装した。基準はPR #55の `815d0b8db357aa2afd323dfee2f22311e343e26a`。P1〜P4の試作は再開していない。OCR切替UIは追加せず、音声・再確認ON/OFF・発話順序・台帳の既存機能を維持する。

## 今回の実験はどちらの発話方式だったか

PR #55の891画像比較は **REOBSERVE / stableMs=300の再確認あり**で実行した。`PlayerFrameReplay`は当時、stabilizerにFIRST_OBSERVATIONを設定せず、`TemporalOcrConsensus.observe`を呼んでいた。データセットのmono_msを使う診断replayで、TTSは即時完了シミュレーションである。

ただし**CERは再確認・発話判定より前の、各画像の選別後文字列**で採点している。処理時間も関数実行の壁時計で、将来のフレームを待つ仮想300msをsleepして加算した時計ではない。CER 7.07%、中央値237msを実動画の発話開始遅延と呼んでいない。

二つの「読み直し」を区別する：

|項目|動作|E採用後|
|---|---|---|
|同一画像内のOCR|全体を粗く読み、字幕領域を切り出してv6で読み直す|行う。候補・行数に応じて複数のモデル呼出がある|
|別フレームでの発話前再確認|同じ字幕を次の取得画像でも観測し、安定化してからcommitする|既定OFF。FIRST_OBSERVATIONで次の再確認を待たない|

最近実装した1回の読み取りによる発話は後者の待機を省く機能で、OCRネットワークを必ず1回だけ呼ぶ意味ではない。採用後も「字幕を再確認してから読む」は既定OFF。既にユーザーがONへ設定していればその値を保持し、ON/OFF切替も動作する。可聴開始にはOCR処理・候補判定・発話順序・TTSキュー／初期化等の時間が残る。

## 製品コードへの接続

- `ECaptionOcrPolicy`をmain/ocrへ移動。PR #55の測定済みロジックから名前／package／説明以外を変更していない。
- `CaptionOcrMode.E` / 保存値 `e_mlkit_v6` を追加。factoryはML Kit検出・v6認識の既存engineを使用する。
- `AppPreferences.getOcrMode()` と旧setterはEに固定し、未設定、旧A/B/C/D、旧形式、不明値をEへ移行する。再確認キーや音声設定を移行処理で書き換えない。
- `SharedPlayerActivity.selectBands`でEの背景候補処理を適用し、`OcrRefinementSelector.selectForMode`でEの修正選別を使用。A/B/C/Dの明示診断は従来の選別を保持。
- 非登録のlegacy `CaptureService`にもEのOCR選別を接続したが、その発話方式・manifest登録は変更していない。現在の通常UIはSharedPlayerActivityである。
- 通常アプリAPKにEのpolicyが含まれる。試験専用source set指定を削除し、同一policyをユニットテスト／診断で共有する。
- versionCode 37 / versionName `1.0.6-e-fixed`。ローカルapp/test APKをビルドした。GitHub Release作成や実機へのインストールは行っていない。

## 検証

- Android単体テスト **258件成功、失敗／skip 0**。Eの16ケースに加え、製品selectorがE修正を採用しB/C/D等を変えないこと、初回観測でcommitすること、再確認ONでは次の観測を待つことを回帰検査した。
- エミュレーターの `ocr-settings-smoke` **PASS**。旧設定のE移行、一般OCR切替UIなし、通常プレーヤーのE engine（ML Kitあり・v6 detectorなし・v6 recognitionあり）、既定FIRST_OBSERVATION、再確認ON→OFFを確認。[結果](e-adoption-20261013/settings-smoke.json)。
- E採用APKで同じ99画像を **再確認OFF / FIRST_OBSERVATION**で各dataset1回再実行。全99出力文字列がPR #55のEと完全一致し、CER **180/2545 = 7.07%**を維持した。猫29、時計48、移行103 edits。v6 detector callsは全画像0、v6 recognizer calls合計223。[hash・数値](e-adoption-20261013/verification.json)。
- 画像runnerの入力ガード **15件成功**。E明示指定と再確認OFFがplayer-replayで使えること、raw scopeでの再確認overrideはADBへ到達する前に拒否されることを検査した。

99画像試験はOCR＋字幕選別＋同じ発話コンポーネントのreplayであり、通常WebViewでの動画再生／実音声を測る試験ではない。S25での可聴開始、イベント取り逃し、電力、完全未使用動画への一般化は未測定。元画像や字幕全文は新たに公開していない。

## 再現手順

以前と同じMacの確認済みJDK17/Gradle8.13/SDK36と専用API36 ARM64 emulator-5580を使用。モデル／辞書SHAはverification.jsonに保存した。

```bash
source runtime/env.sh
(cd repo && gradle --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest)
adb -s emulator-5580 install -r repo/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r repo/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e mode ocr-settings-smoke \
  jp.hidemaru.burnedcaptionreader.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation
# 各datasetの前にforce-stopし、同じ3manifestを順番に実行
python3 repo/tools/image-evaluator/run.py "$DATASET" "$PRIVATE_RESULT" \
  --adb "$ANDROID_HOME/platform-tools/adb" --device emulator-5580 \
  --label E-adoption-first-observation --mode player-replay \
  --ocr-mode e_mlkit_v6 --subtitle-recheck off
python3 repo/tools/image-evaluator/test_run.py
```

実験の既定replayは過去比較との再現性のため `--subtitle-recheck on` を維持し、出力に設定値を明示する。通常アプリの再確認既定OFFとは独立である。採用変更はPR #55をbaseにした専用PRで扱い、実験結果の履歴を上書きしない。
