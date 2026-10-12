# OCR改善 P0〜P5 実験報告（2026-10-13）

P0〜P4のMac検証と、独立Androidエミュレーターの実モデル推論を実行した。P1は数字遷移の合成観測を0/8→7/8へ改善したが、自然背景の既知9イベントは0/9復旧。P2はAndroid観測で字幕本文の精度改善を確認できなかった。P3の手動ROI実推論は既知4枚の2枚を正解復旧したが、実アプリの条件付き復旧率ではない。P4には独立真値の合成保護ケースで誤skipが残る。Galaxy S25は未接続でP5実機評価は未実施。mainのマージ、既定変更、リリース公開は行っていない。

## 1. 環境・固定状態

|項目|実際に確認した値|
|---|---|
|Mac|macOS 26.6.2、Apple M4、16 GiB|
|main|`2c4d3db86fdacae051480bd8c91c83f560200627`。未設定時はD|
|PR #49|`7d67fe28cd1f316813f751577c260756220d3e46`。GitHubでOPEN/Draft、CI SUCCESSを読み取り確認|
|検証用branch|`codex/ocr-p0-p5-mac-evaluation-20261013`。独立cloneとmainのdetached worktree|
|既存比較ソース|`5764edaa3e2bc019e52b628c51c5dbf5a7f9101f`。既存作業木を変更せず参照|
|実行環境|Python 3.14.4、既存Pillow 10.4.0 venv、JDK 17.0.20.1、Gradle 8.13、SDK 36/ARM64|
|Android|コピーした専用AVD、`emulator-5562`。Galaxy S25は認可済みADB端末一覧に存在しない|
|TTS|新しいMac/エミュレーター比較では発話を実行していない。音声開始、実聴、重複発話、待ち時間は未測定|
|アプリAPK SHA-256|`22ab8610333f6b8374b3420b66e4539f422d50ae555bc62456265fb027bfa3b4`|
|最終テストAPK SHA-256|`13e0de58d6d1f2ed0b2fb876226b5225937162e8abea9b8aa4435f69d501ef05`|

A=ML Kit検出＋PP-OCRv5認識、B=ML Kit検出＋v6認識、C=v6検出＋v5認識、D=v6検出＋v6認識。モデルは交換せず既存ファイルをコピーし、v6の4ファイルはpinされたhashと照合した。B/Cの新しい実推論は行っていない。今回のA粗認識段階はML Kitのみで、v5認識は候補の再認識段階にある。したがって下のA/D粗認識表はアプリ全体のA/D比較ではない。

## 2. 入力データと評価の定義

|データ|照合・実行した件数|
|---|---:|
|Elephants/Sintel、日本語字幕を追加したCG映像|各300秒、33/17イベント|
|連続PixelCopy PNG・保存済みOCR候補|各624、計1248。全PNG hash照合・画像decode|
|固定PixelCopy画像|43＋27＝70。字幕50、字幕なし20。全hash照合、全70枚を新規Android推論|
|既知raw正解→selected空|Elephants 1,2,15,16,17／Sintel 6,10,15,17。1始まりID、全9件を再現|
|検出漏れ4枚|Elephants手動ROIの4枚。原画像を今回目視確認・v6限定ROI推論|
|追加保護入力|12個の独立した合成画素遷移と21観測の価格リプレイ|

「4枚」は元報告のElephants手動ROI評価の数字。Sintel手動ROIにも1枚あり、2作品計では5枚。全画面評価の空出力はElephants 2枚＋Sintel 1枚＝3枚。これらを自然動画9イベントと混同しない。

参照は独立SRTと既存の画像目視転記。OCR出力から正解を作っていない。人間による二重監査は未実施で、`human_verified=false`。追加字幕の描画境界は約41.7msの量子化不確かさがある。既存2作品は既に結果を見た開発・回帰セットであり、新しい未使用検証ではない。実写・書影・上部3行の自然動画を網羅した成績ではない。

主指標はNFKC文字CER（空白/改行を除外、句読点は保持）とNFC原文CER（空白/改行/文字幅を保持）。字幕なし場面の挿入を含む値と字幕ありだけの値を分ける。過去の「9件」の照合だけは過去採点器と同じ句読点除去を使う。候補CER、粗認識CER、発話・SRT製品成績を合算しない。

## 3. P0：棄却原因と診断不変性

保存済みログに当時のselection_traceはなく、当時の理由は未観測。現在の同一候補列・同一時刻列をPR #49の実Javaクラスへ渡し、traceあり/なしの候補文字列・track ID・枠・locked状態が1248/1248フレーム一致した。追加OCRは0。候補列はstrip復旧後の保存済みrecognition_rowsも維持した。診断有効時の実音声・captureスケジューリング不変性は未測定。

|作品/イベント|raw正解を含む観測|現在の再現棄却理由|main正解候補→PR49正解候補|
|---|---:|---|---:|
|elephants/1|3|候補形状条件不成立（strong/upperともfalse）|0→0|
|elephants/2|3|候補形状条件不成立（strong/upperともfalse）|0→0|
|elephants/15|4|候補形状条件不成立（strong/upperともfalse）|0→0|
|elephants/16|1|候補形状条件不成立（strong/upperともfalse）|0→0|
|elephants/17|3|候補形状条件不成立（strong/upperともfalse）|0→0|
|sintel/6|1|candidate/length_or_height（1文字相当の短文）|0→0|
|sintel/10|2|候補形状条件不成立（strong/upperともfalse）|0→0|
|sintel/15|8|候補形状条件不成立（strong/upperともfalse）|0→0|
|sintel/17|5|候補形状条件不成立（strong/upperともfalse）|0→0|

8イベントは候補形状条件、Sintel 6は最低文字数等の候補生成条件で除外された。P1が修正する「似た文の数字・否定変化によるlane遷移」とは原因が異なる。traceの`temporal_or_caption_shape_gate`を時間条件と決めつけず、既存private predicateの非変更・読み取り評価で形状不成立を確認した。全しきい値緩和は実施していない。

## 4. P1：同一観測・単独対照

|作品|候補CER NFKC main→PR49|候補CER NFC原文 main→PR49|完全一致候補イベント NFKC|追加OCR|
|---|---:|---:|---:|---:|
|elephants|26.81%→26.81%|26.80%→26.80%|20→20/33|0|
|sintel|42.87%→42.87%|46.79%→46.79%|12→12/17|0|

自然動画の既知9件は0/9復旧、目標6/9未達。候補文字列はmainと全1248フレーム一致し、既存正解候補の増減も0。これはcoarse候補の評価であり、再crop認識後の最終selectedや実発話の新しい成績ではない。

保存済み価格観測21件では100円が5/8→5/8、blankの選択0/5→0/5、200円が0/8→7/8。初回200円を保留し、2観測目以降を選択。固定見出しの新しい選択0件。数字・否定5テスト、記録価格2テスト、初回確定4テストが通過した。初回確定/再確認ONの自然映像・音声の対照は未測定。

|Mac JVM候補選択時間（各条件3回、全624観測をprewarm後）|main p95範囲|PR49 p95範囲|PR49中央値範囲|
|---|---:|---:|---:|
|elephants|0.161〜0.170 ms|0.136〜0.145 ms|0.001〜0.002 ms|
|sintel|0.136〜0.160 ms|0.167〜0.174 ms|0.001〜0.001 ms|

デスクトップの追加処理は小さいが、Galaxyの2ms/10MiB予算を達成した証拠ではない。Javaのimage decode・trace・CSV出力は選択タイマーから除外。PSS/常駐増分は未測定。

## 5. P2：同一Android OCR枠のshadow

|作品|配置変更観測|NFKC CER 現行→compose|NFC原文CER 現行→compose|2行の原文構造一致|
|---|---:|---:|---:|---:|
|elephants|0/624|13.07%→13.07%|13.46%→13.46%|14→14/49|
|sintel|1/624|82.30%→82.30%|95.82%→95.66%|0→0/0|

追加OCRは0。Sintelの変更1観測は字幕なし場面の行配置で、NFKC CER・正しい字幕取得を改善しない。NFC原文CERはblank場面の改行等が2文字分減り、95.82%→95.66%へ小さく改善した。字幕ありのNFC CERには変化がない。新しい70枚のA粗認識/D PP観測も配置変更0/70ずつ。別blockを統合しない制約が効き、現在の断片には利得がない。元のVision CER改善をAndroidの改善へ換算していない。3行・段組みの自然例は未測定（単体テスト4件は通過）。

|Mac compose追加処理、3回のp95範囲|値|
|---|---:|
|elephants|0.006〜0.007 ms|
|sintel|0.006〜0.006 ms|

## 6. P3：発動条件と限定ROI実推論

|保存A観測の条件リプレイ|raw空・字幕あり|raw空・字幕なし|仮発動/字幕あり/字幕なし|
|---|---:|---:|---:|
|elephants|7|470|7/2/5|
|sintel|5|471|5/1/4|

合計12仮発動中、字幕あり3、字幕なし9。明暗contrastは文字存在の証明ではない。今回リプレイでは追加OCRを呼ばず、保存Aのtrusted lane/strip有無を使う。D/Cの同じ時系列・Activity上のP3発動率とは呼ばない。字幕初登場時やraw空の後でtrustが失われた場合、P3は発動できない。

別実験として、Elephants手動ROIの既知4枚を固定領域でDの`recognizeLimited`→`LockedRegionRescue.merge`へ入力した。画像・正解は同一。各枚は検出1回＋認識1回、計4＋4回で上限内。手動の既知領域を与えた到達可能性試験であり、本番のraw空/前回帯/strip不発/cooldownをすべて満たした復旧率ではない。

|画像ID|限定ROIのraw|merge後|原因|
|---|---|---|---|
|elephants-pixelcopy-16685|正解|正解|復旧可|
|elephants-pixelcopy-21005|正解|正解|復旧可|
|elephants-pixelcopy-64205|正解|空|短文の横幅が`.18`未満、mergeで除外|
|elephants-pixelcopy-132366|誤字あり|誤字あり|認識の濁点誤り、領域だけでは解決せず|

正解復旧2/4。merge後NFKC CER 17.86%, NFC原文CER 25.00%。初回221.503ms、残り3回中央値84.975ms/p95 85.456ms。初期化はdetector 56.629ms、recognizer 39.110ms。サンプル最大PSS 218.8MiBは別プロセスの総値であり、P3の追加PSSではない。

5分総OCR時間+10%、全体p95+20%、追加PSS+50MiB、busy/capture欠落、Bitmap解放と世代変更の実ライブ回帰は未測定。merge座標・上限の既存4単体テストは通過。P3 ONの本採用条件は未達。

## 7. P4：独立真値による誤skip検証

|実フレームのshadow|仮skip|独立SRTの誤skip|比較p95（Mac）|
|---|---:|---:|---:|
|elephants|0/624|0|0.123 ms|
|sintel|0/624|0|0.130 ms|

背景変化・trusted lane条件により実フレームの仮skipは0/1248。したがってこのセットで速度改善の証拠も0。実OCR削減は未導入のため測定不可で、0件の実省略を20%達成と扱わない。判定はselectedの変化ではなく、独立SRTの最後の非skip観測との変化を使用。

|合成保護ケース|仮skip|独立真値による誤skip|
|---|---:|---:|
|unchanged|1|0|
|first-appearance|0|0|
|caption-disappearance|0|0|
|digit-100-to-200|0|0|
|unsampled-small-digit-1-to-2|1|1|
|unsampled-short-glyph-appearance|1|1|
|unsampled-equal-luma-red-green|1|1|
|sampled-equal-luma-red-green|0|0|
|moving-background|0|0|
|generation-reset|0|0|
|upper-three-lines-appearance|0|0|
|dark-negation-appearance|1|1|

12制御中、誤skip 4件。1桁の小さな字形、短い変化、サンプル間の等輝度色変化、暗い否定表示が反例。既存の「サンプルした1セルの色変化」テストは通る一方、16×9/48×12の点の間にある画素は最大RGB差でも見えない。常駐fingerprint配列のpayloadは720×4＝2880 bytes（オブジェクト等の実heap/PSSを除く）。サイズが小さくても安全性の証明にはならない。実スキップを導入していない。

## 8. 新しいAndroid粗認識の補助測定

|同一70画像、粗認識のみ|NFKC CER（blank挿入含む）|NFC原文CER|正解字幕画像/50|blank誤出力/20|中央値/p95、初回除外|サンプル最大PSS|
|---|---:|---:|---:|---:|---:|---:|
|coarse-A|17.62%|19.55%|23|1|6.856 / 10.399 ms|100.0 MiB|
|coarse-D|13.66%|15.27%|45|4|135.176 / 182.772 ms|335.9 MiB|

各条件1回の画像batch。A粗認識はML Kit検出の出力、v5再認識なし。Dはv6検出＋認識。ROI精度、候補選択、refine、安定化、TTSを含まない。初回1件除外は厳密なcold-load分離ではない。エミュレーター速度・PSSをGalaxyの数値に置き換えない。A/Dの純粋な認識モデル比較や3回の実機試験を完了したとは扱わない。

## 9. P5と採否

|改善|判定（提案）|根拠と必要な残作業|
|---|---|---|
|P1|限定採用|数字・否定の保護用途に限定。価格0/8→7/8、自然9件は0/9。一般欠落の修正としては保留。短い字幕のshape/1文字条件を別修正として検証|
|P2|保留|Android字幕本文のCER/行構造の改善0、blankの改行のみNFC挿入誤差2文字減。shadow保持。same block制限を緩める前に実際の断片・段組み負例を集める|
|P3|保留|手動ROIで2/4回復、短文1件再棄却、12仮発動中blank9。C/Dの条件付きライブ対照と負荷予算が未測定|
|P4|見送り|実スキップは保護入力で誤skip4件、自然セット仮削減0%。領域内の面積/エッジ差などサンプル間の変化を検出する別案が必要。shadow記録のみ維持|
|P5|未実施|Galaxy未接続。未使用5分以上素材・3回交互比較・実音声開始/PSS/温度も未測定|

P1の候補選択だけが変わる狭い保護用途と、P2/P4のshadowは併存可能だが、精度・速度改善の有望な実稼働組み合わせを今回の数字から選べない。初回確定/再確認、P3 OFF/ONは同じD・同じGalaxyで評価する必要がある。P1だけで6/9目標が達成される想定は棄却する。元PR #49/mainのコードと通常設定は変更していない。

## 10. 再現と証拠

`summary.json`（Mac単独対照）、`events.csv/json`（全50イベント）、`frames.csv`（全1248フレーム）、`controls.json`、`android-metrics.json`、`android-pairs.json`、`environment.json`、`inventory.json`に数値・hashを保存。原文を含む実験JSON/TSVは`private/`へ分離。`logs/`にビルド、失敗・修正履歴、実行ログを保存。入力動画・SRT・PNGは既存保存先を読み取るだけで上書きしていない。

```sh
# 作業ルートから。DATA_ROOTは既存比較データのルート。パスはruntime/env.shを参照。
source runtime/env.sh
python3 repo/tools/device-ad-compare/test_selection.py
python3 repo/tools/device-ad-compare/test_compare_trials.py
python3 repo/tools/mac-p0-p5/test_metrics.py
gradle --offline --no-daemon -p repo -PcaptionAbis=arm64-v8a :app:testDebugUnitTest :app:assembleDebug
python3 repo/tools/mac-p0-p5/evaluate.py --workspace . --data-root "$DATA_ROOT" --jdk "$JAVA_HOME"
"$VSE_PYTHON" repo/tools/mac-p0-p5/controls.py --workspace . --jdk "$JAVA_HOME"
gradle --offline --no-daemon -p repo -PcaptionAbis=arm64-v8a -PcaptionTestRunner=jp.hidemaru.burnedcaptionreader.evaluation.MacOcrExperimentInstrumentation :app:assembleDebugAndroidTest
# 独立エミュレーターだけ。既存実機runnerと混ぜない。
python3 repo/tools/mac-p0-p5/run_android.py --workspace . --data-root "$DATA_ROOT" --adb "$ANDROID_HOME/platform-tools/adb" --device emulator-5562
python3 repo/tools/mac-p0-p5/report.py --workspace .
```

Android単体テスト264件、失敗0。指定Python診断テスト8件、新しい採点テスト5件も通過。デバッグapp/test APKをビルドし、70＋70＋4画像の本物の推論を完走。検証用runner追加だけでmainソースの差分は0。通常test runnerは既定を保持し、任意Gradleプロパティで今回runnerを指定する。正式releaseビルド/通常YouTube再生は未検証。

モデルhashはenvironment.json、素材全hashはinventory.json。主要動画：
- elephants: `64c18561c5c753e15d40e78a5fd67a05a84aee7021b908b675424bcff9a0e7f0`
- sintel: `2ac9f667efbcd95405377d13c2ba973663a98cb67c18ec6e27b27d7f95db5c60`

今回の検証用コードは別branchへ保存する。次の機能修正は、短文shape/最低文字数、P3の実際の発動・短文map、P4のサンプル間保護を分けたDraft PRで提案すべきであり、今回は安全性未証明の修正を本番へ配線しない。素材、画像、全字幕、診断ZIP、APKは公開しない。

## 11. 実機評価に必要なユーザー操作

- Galaxy S25をUSB接続し、開発者設定のUSBデバッグを有効にして、このMacへのデバッグ認可を許可する。
- 許可済みの未使用5分以上の日本語字幕動画のローカルパスを指定する。CG2作品以外で固定見出し・短文・上部複数行を含む素材が望ましい。
- 実際の音声の開始・二重読みを評価する場合は、端末を操作可能な状態にし、録音/実聴による独立確認を行う。
