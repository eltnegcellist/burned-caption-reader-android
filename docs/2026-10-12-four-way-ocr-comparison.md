# OCRモデル独立切替（4パターン）とMac評価依頼

## 変更意図
ML Kitをv6 Smallに追加するのではなく、文字位置検出を**置換**する。文字認識についてはv5 Mobile / v6 Smallを独立に切り替える。全方式、端末内のみで動作。

| No. | 検出 | 認識 | 設定 |
|---|---|---|---|
| A（現行基準） | ML Kit Japanese | PP-OCRv5 Mobile | OFF / OFF |
| B | ML Kit Japanese | PP-OCRv6 Small | OFF / ON |
| C | PP-OCRv6 Small Det | PP-OCRv5 Mobile | ON / OFF |
| D | PP-OCRv6 Small Det | PP-OCRv6 Small | ON / ON |

設定 > 「文字位置の検出」「文字の読み取り」の2スイッチ。初期値A。切替時は発話・追跡状態をリセット、診断ログのocr_modeを記録し、画面再読み込みは不要。従来のML Kitのみ・PP検出**追加**の旧実験モードはこの比較から除外し、保存値はAへ移行。

## モデルの準備
Gitには大型ONNXのバイナリをコミットしない。公式モデルのrevision・size・SHA256を固定した `python3 tools/models/prepare_v6.py` を実行し、モデル・辞書をassetsへ配置してからGradleをビルドする。CIも同じ処理を実行する。ONNX Runtimeは現在の依存を共用。検出はDB、認識はCTC。v6検出は長辺736/32倍数、入力BGR ImageNet正規化、公式のDB閾値0.2・箱0.45・膨張比1.4を使用する。現在のDB純Java幾何近似は維持するため、公式OpenCVポストプロセスとの厳密一致は未保証。v6認識は公式モデル辞書・高さ48を使い、モデル推論失敗は**比較条件を汚さないようエラーとして報告**（v5やML Kitにサイレント切替しない）。

検出のみをv6にしたC/Dでは、ML Kitを実行せず、検出した矩形を選択したPP認識モデルで文字化したうえで既存の字幕選択へ渡す。これまでML Kitから得ていたsymbol/element単位のgeometryが使えなくなるため、既存の名札・表除外精度が低下しうる。モデル認識の再実行・最大24候補の上限も処理時間・網羅率に影響する。

## MacのCodexへの依頼文（コピーして実行）

```text
字幕リーダー eltnegcellist/burned-caption-reader-android の
codex/ocr-v6-four-modes-20261012 ブランチを取得し、4方式のAndroid動作確認と同条件の比較評価を実施してください。

(1) まずコミット・依存・モデルのSHA256を確認。
python3 -m pip install PyYAML==6.0.3
python3 tools/models/prepare_v6.py
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
でモデル同梱・ビルド・単体テストを確認。モデル準備失敗、CTC辞書とONNX出力class数不一致、DB検出出力のshape不一致はエラーを握り潰さず報告・修正。既存の現行基準を悪化させない。

(2) エミュレーターAPI36 ARM64で以下の4パターンを検証：
A ML Kit Det + PP-OCRv5 Mobile Rec
B ML Kit Det + PP-OCRv6 Small Rec
C PP-OCRv6 Small Det + PP-OCRv5 Mobile Rec
D PP-OCRv6 Small Det + PP-OCRv6 Small Rec。
文字位置だけをv6にしたCと全面v6のDではML Kitの粗いOCRが実行されていないことを診断ログ/プロファイルで証明。必ず各条件のbackend、ocr_mode、使用モデルハッシュを記録。設定の各スイッチが独立し、4通り保存・再開・再生中の切替が正常なことを確認。既存の3行、複数字幕、数値・否定表現、発話台帳回帰テストも実行。

(3) 既存の検証動画を同一位置・同一PixelCopy画像・同一正解転記・同一フレーム時刻で4方式へ入力し、各方式CER/SER、字幕ありの空出力、字幕なしの余計な出力、位置検出Recall/Precision/IoU、全パイプライン処理時間（中央値/p95）、検出/再認識個別時間と回数、モデル初期化時間、ピークRSS、APKサイズを集計。画像が削除されている場合は削除済みと明記し、合法かつ許可された同一条件で再収録。異なる動画/採点セットや以前の実験値を混ぜない。結果を新旧ペア（A→B、A→C、B→D、C→D）の差分と4方式総覧で整理する。

(4) 字幕の全文認識と「本文以外の文字が入らないこと」の両方を評価。文字位置の検出だけは文字列CERと独立に採点する。字幕台帳とTTS要求・開始・完了・破棄・実音声の違いを明示。広告、名札、メニュー、短い字幕、3行字幕、上部字幕の事例を抽出。調整に使った動画と未使用の検証動画は必ず分離し、未使用データを見てから閾値をチューニングしない。

(5) 結果を docs に固定した評価表と失敗例・バージョン・入力ハッシュ・再現方法でまとめ、CI成功とAPK Artifactを確認。Galaxy S25での実音声・熱・遅延が未測定なら未測定と明記。効果が証明されるまでmainへはマージしない。既存のPP-OCRv5実験版と他PRは壊さない。
```

## 進捗と未完了の確認
この変更は4方式のコード・固定モデル取得スクリプト・CI・選択UI・JUnitを用意するもの。GitHub Actionsでの初回モデル取得/実機評価はPRのCI確認後に行う。実測性能が確認されるまで「v6が速い」「v6が正確」とは主張しない。
