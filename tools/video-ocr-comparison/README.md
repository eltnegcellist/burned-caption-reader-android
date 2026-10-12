# 動画内字幕リーダー：OCR比較結果と再現手順

## 最新の実測（2026-10-12～13）

[公開映画の比較報告](OPEN-MOVIES.md)と[再現手順](OPEN-MOVIES-REPRODUCE.md)を保存した。
Elephants Dream・Sintelの各5分をVSE/VideOCR/SE公式部品/Android hybridの検証専用MP4経路へ入力し、
同一PixelCopy PNG70枚を4エンジンの手動ROI・全画面に渡した。数値・設定・hash・文字なし対応付けは `open-movies/benchmark-*.json`。

Androidには生認識→字幕選択の欠落、デスクトップには英語タイトル混入・分割重複があった。
SEの公式行組み立ては同じOCR観測で画像CERを改善した。厳しい重複閾値の丸採用は自然背景で改善せず、VSEは悪化例もあった。
Subtitle Edit GUIはMacロックで未測定。SE公式部品、CG映画への追加字幕、エミュレーターの成績を
製品GUI・実写テロップ・Galaxy S25の成績と混同しない。本番ソース・モデル・PR29/31は変更しない。

[合成コントロールの履歴](REPORT.md)と[その再現手順](REPRODUCE.md)も保持した。
採点器11テスト、画像runner14テスト、nativeモード誤表示ガードを確認済み。
以下はPR31の準備計画を履歴として残す。

## PR #31時点の準備計画（当時の状態）

既存Androidアプリ（ML Kit + PP-OCRv5の`hybrid`、PR #29 の最新HEAD）を、
VSE / VideOCR / Subtitle Editの3つと**同じ入力**で比較する。合格した技術のみ後で
Androidへ移植を検討する。**本作業ではアプリ本体の改変や既存PRのマージをしない。**

**結果ステータス：ベンチマーク設計とイベント単位のSRT採点器のみ完成。実動画を通した4方式の精度・処理速度は未計測。**

2026-10-11引き継ぎ資料によれば、過去のPixelCopy画像・アーカイブ1,291ファイル
（約1.6GB）は容量確保のため削除済み。`outputs/.../evidence/`に結果JSON、
正解JSON、元画像SHA-256、実行環境・処理時間が残るが、元画像を再度OCRするには
撮り直すか、別の許可された素材が必要。**画像を取り戻したかのように結果を作らない。**
また `main` はOCR3方式切替より古い。作業前にPR #29 のHEADとローカル作業木を検証する。

## 比較するもの・公平性

比較は必ず**二系列**に分ける。

### A. 静止フレームの画像OCR（現在のYouTube再生に近い）

- 現行アプリのPixelCopyから得た**元フレーム**を対象とする。同じ画像SHA-256を入力し、出力文字列を比較。
- 既存画像が見つからなければ、検証アプリの正式な診断モードで新規収録し、再現性のためハッシュ・動画ID・`media_ms`・横縦・画面の遮蔽物有無を保存。
- 字幕あり/なし、3行字幕、名前札、固定見出し、背景文字、数字/否定、装飾文字を揃える。
- 手動で字幕ROIを指定した**OCR単体精度**と、モデル任せで字幕候補を見つける**自動選抜精度**を別表にする。正解は画像を見て独立に転記し、OCR予測を正解に使わない。
- VSE/VideOCR/Subtitle Editは一括動画入力に最適化されているため、静止画投入用の最小OCRアダプターが必要。まずローカルOCRエンジンに同じ画像・同じROIを与える。各製品の**フルパイプライン**の成績と混ぜない。
- 現行の`tools/image-evaluator`が持つ `caption-crops`, `player-replay` の区別に準拠。参照画像が少数/不均一な場合、動画全体の漏れ率を主張しない。

### B. 連続動画ファイルの焼き付け字幕抽出（エンドツーエンド）

- **権利上処理できる同一のMP4ファイル**から4方式を比較する。各ツールがまったく同じ動画バイト列、フレーム範囲を扱ったことをSHA-256で記録。
- YouTubeの任意動画を非公式手法で取得することを前提にしない。A系列のYouTube PixelCopy断片を連結して作った疑似動画を、元の実動画の完全再現や時間精度の根拠にしない。
- 3本程度の5分以上の横書き日本語字幕付き動画を選び、少なくとも1本/投稿者を未調整のheld-outにする。動画ソースが少ない場合はその範囲を明記。
- 全方式とも**クラウドOCR禁止**。VideOCR `--ocr_engine paddleocr`。Subtitle EditはmacOS内蔵Apple Vision。VSEはローカルCPUのFast/Autoから開始。性能比較ではエンジン名とモデル版を必ず残す。
- 字幕領域は、(1)同じ共通ROI固定と (2)各方式の自動/標準設定を分ける。VSE等が指定ROI以外を見た場合、同条件の比較とは呼ばない。
- OCR出力SRTを固定して、このディレクトリの `score_srt.py` で自動採点する。元の動画時刻を揃える。抽出率・誤検出・二重字幕・OCR精度・開始/終了時刻ズレ、総処理時間、ピークRSSを記録。
- Androidアプリはストリーミング表示時OCR/TTSであり、オフライン動画一括抽出の速度と**直接優劣をつけない**。B系列に正式に参加させるには同一動画ファイルのローカル再生用**テスト限定**パスを追加してから比較。製品の通常YouTube再生は無断変更しない。

## Mac/Codexの最初の手順

1. Mac内の作業フォルダー `.../files-mentioned-by-the-user-android/work/ppocr-detector-native-20261009` とGitHub PR #29 を確認し、現行HEAD・ワークツリー・実機向け既定`hybrid`を記録。過去資料には古いHEADがあるため**Gitリモートの現物を優先**。
2. `outputs/2026-10-09-PP-OCR Android検出実装検証/evidence` のJSONを確認する。`{quiz,saratame,puromaneko,holdout}-{off,rescue}.json`、`datasets/*/dataset.json`、`native-summary.json` を**読み取り専用**で再集計。元フレームは削除済みという前提だが、もし残る画像があればハッシュと元データ出所を検証。
3. 動画の必要範囲・フレームが本当にあるかを確認。欠けている場合は再収録を行い、開始前にYouTube動画の再生画面のミュートUI・コントロール・広告・字幕隠れを目視確認。利用可能な許可済みMP4を別途準備してB系列を実施。
4. 実行するツールは別々のvenv/インストールで環境干渉を回避。まず各ツールがMacで起動するか確認し、**VSEのmacOS ONNX/CoreMLは公式READMEで未検証扱い**なので無理に高速化を約束しない。
5. 各ツールで初期の5分区間を処理。SRTを元のまま保全し、同一の参照SRTと比較。字幕のない区間も必ず採点に含める。
6. 画像と正解/出力/ハッシュをフリーズし、変更候補の方式を決める前にheld-outを固定。数値だけでなく、変更箇所の画面・元字幕と照合する。
7. 期間・モデル・ハッシュ・不具合・設定を記した `report.md` と構造化JSONを`outputs/`に保存。動画本体・画像は公開GitHubにはコミットせず、容量整理前に再実験の可否を確認する。

## 各アプリの実行時の注意

- **VSE** <https://github.com/YaoFANGUK/video-subtitle-extractor>（Apache-2.0）：Fast/Auto（CPU/ローカル）を優先。macOS向けソース起動は `python gui.py`、CLIは `python backend/main.py`。必ず実際のヘルプと版を確認してから実行。日本語・字幕領域を指定。UIまたは出力SRTを記録。
- **VideOCR** <https://github.com/timminator/VideOCR>（MIT）：CLI `--ocr_engine paddleocr --lang japan --use_gpu false` を指定する。`google_lens` はクラウドのため利用しない。`--video_path <MP4> --output <SRT>` を基本とし、フレームスキップ・画像類似度・crop設定も記録。Macでのインストール/動作を先に確認する。
- **Subtitle Edit 5.2** <https://github.com/SubtitleEdit/subtitleedit>（MIT）：GUIの `Video -> OCR burned-in subtitle...` を使い、macOSでは `Apple Vision`、認識言語 `ja-JP`、対象領域を指定。`seconv` の一般的な画像字幕ストリームOCRは**映像焼き付きVideo OCRと同じとは限らない**。GUIの結果をSRT保存し、処理条件を残す。
- **Android現行** <https://github.com/eltnegcellist/burned-caption-reader-android/pull/29>：`hybrid`を基準とし、`mlkit` と `pp_detection` は参考結果を別行に保存。`main`旧版を現行と誤認しない。

## 解析と簡易実行例（B系列）

```bash
python3 -m unittest discover -s tools/video-ocr-comparison -p 'test_*.py' -v
python3 tools/video-ocr-comparison/score_srt.py reference.srt \
  baseline.srt vse.srt videocr.srt subtitle_edit.srt \
  --out report_event_scores.json
```

- 自動採点は**イベント単位の暫定スコア**。イベントは時間重なり80ms以上で一対一対応し、余剰予測・未検出をCERへ加算。フレーム単位のAndroid `comparisonKey` とは正規化式が違う。文字単位・イベント単位の採点結果を混ぜない。
- TTS音声の自然さ、実際に発話した文、字幕出現→発話開始遅延、電池・熱はこのスクリプトでは計測しない。必ず別にAndroid実機テストする。
- 認識時刻は動画内の `media_ms` を使う。動画開始オフセットを揃えないままSRTを採点しない。

## 採用判断

一次基準は「未知動画において、誤検出/字幕漏れを増やさずCERを改善」。速度・メモリ・バッテリー・ライセンスがAndroidでも実現可能か別判定。候補が旧方式と同等以下なら採用しない。小さな試験の誤差範囲を超える改善がなければ、アルゴリズムを無理に導入しない。

出力は`status: not_run / completed / blocked`を方式ごとに明記し、未計測欄を0や成功として報告しない。
