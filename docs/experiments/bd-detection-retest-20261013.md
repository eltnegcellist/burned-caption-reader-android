# B/D検出再テスト実測レポート（2026-10-13）

**判断：通常アプリのD固定を維持する。条件付きDの採否は判断保留。** PR #53の同一99画像について594回のB/D再推論を完了した。B 274/2545 = **10.77%**、D 143/2545 = **5.62%**で、旧値を完全再現。各方式の3反復に出力差はない。新しい未使用動画、Galaxy S25、可聴音声は**未測定**。P1〜P4を再開せず、製品コード・一般設定・D固定方針は変更していない。マージ、Release、実機へのインストールも行っていない。

## 測定範囲・凍結条件

- 計画：[PR #53](https://github.com/eltnegcellist/burned-caption-reader-android/pull/53)、計画commit `5df065b0342cd8067a2c69de90055f417ed577a3`。基準は[PR #52](https://github.com/eltnegcellist/burned-caption-reader-android/pull/52)の指定commit `294536fc3a8ced4d69ff3a641f295d93196d43bc`。空の今回workspace内へローカルGitをbare cloneし、そこから専用worktreeを作成した。既存作業treeは読み取りのみ。結果PRのbaseはPR #52のブランチに置き、製品変更をdiffへ混ぜない。
- PR #33測定commit `b1c8b18f5b871d59412c2a8f73966a004665b61f`から基準までにはD固定UI、FIRST_OBSERVATION等の既存変更がある。今回追加していない。OCRエンジン本体・画像入力・字幕候補選別の変更はなく、旧replayはそのまま継承。replayのstabilizerはREOBSERVE / stableMs=300のままで、現在の実アプリの発話方式を再現する試験ではない。
- **B=`v6_rec`、D=`v6_both`を診断factoryで明示生成**。BはML Kit公開OCRで粗検出し、refineでv6 Small認識。Dはv6 Small検出＋認識。全runがV6ComparisonOcrEngineで、Bのv6検出呼出0、DのML Kit呼出0、双方のv6認識呼出>0を検査した。一般設定値の書換えはしていない。
- 「Recognition固定」は使用するPP recognizerをv6に固定する意味。既存Bは粗OCRのML Kit認識文を使い、refineの受理ポリシーによりML Kit文へ戻る場合がある。全出力文字を強制的にv6だけから得る新方式ではない。この構造、検出矩形、crop、候補数、再認識数の違いを含む**経路比較**で、検出ネットワーク単体の因果効果ではない。
- dev-cat 28 / dev-watch 28 / heldout 43。`heldout`は既に評価済みの既知素材。全99PNGのSHAと各frame ID・expected_text・mono_msを照合。旧`evidence.tar.gz`には元PNGがなく、保管済みMacの元PNGを使用。archiveのmanifest hash、ローカルmanifest hash、repoのfrozen-manifestsが完全一致した。再収録ではない。
- 保存済み24run＋componentsを既存analyze.pyで再集計し、旧summary.json全体が完全一致。旧APKと新APKは異なる。同一の新APKを今回の全B/Dに使用。モデル追加ダウンロードはなく、既存assetsをコピーしprepare_v6.pyでpin検証・辞書再生成後、APK内bytesも照合した。

|対象|SHA-256|
|---|---|
|旧APK|5944c384459f2aee3c29680c24608c76ea9e43761266b221acf82fe7db061901|
|新app APK|29401639309768ac189421cb2a68628bbaf15b7607f66c64803878891a15970b|
|新test APK|8d40f7a619d59f3b9199c4b780973731047866e61b78e849ed341849bf3233ed|
|dev-cat manifest|c927d62f79814a626c347c1ab74a07a3372f75e3591926bbb6b07d56856469d4|
|dev-watch manifest|15bcd55d7105a95ac14f97f324d810c4eb76242b821b64fd45af3e2b7b7e7d53|
|heldout manifest|b6d1a41f1b6f0f418314febd4db6d05cb0c2e41314fab23de28dfb748c771c17|
|assets/ppocrv6/characters.json|a49d51120786924a642c9e8a14b2a2ab73c019699abf5b2b6970ec7355c0415f|
|assets/ppocrv6/detection.onnx|d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e|
|assets/ppocrv6/detection.yml|193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516|
|assets/ppocrv6/model-provenance.json|e21c605b1fbbb8ac421dd5851cc9de3421fb8943de8c08de1661a5b08420732f|
|assets/ppocrv6/recognition.onnx|5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634|
|assets/ppocrv6/recognition.yml|ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1|
|行数／背景ラベル|de7b5b06d193ac89f7e19e436cad3421d873098996dce1fd20933bf548e1b5fb|
|B出力トリガ|a1613a62d425554af522134cbc6c04147855be34c30f26cfb8c476998db4b58c|

全frame hashは[input-integrity.json](bd-detection-retest-20261013/input-integrity.json)、APKに入ったモデル／辞書／設定の全hashは[environment.json](bd-detection-retest-20261013/environment.json)、ビルド時Android診断ソースhashは[build-inputs.json](bd-detection-retest-20261013/build-inputs.json)に保存。元PNG、正解全文、動画、SRT、モデル、APK、診断ZIP、署名鍵は新たに公開Gitへ追加していない。

## 環境・時計・未測定事項

|項目|確認値|
|---|---|
|Mac|macOS 26.6.2 / 25G83、Apple M4、16 GiB|
|JDK / Gradle|Temurin 17.0.20.1+1 / 8.13、SDK 36|
|Android|API36 ARM64、Google APIs、emulator 37.3.3.0 build 16489710|
|AVD|専用BD_Retest_API36、4 CPU / 4096 MiB、1080×2340 / 420dpi、SwiftShader indirect、headless、音声OFF、snapshot不使用|
|fingerprint|google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys|
|libraries|Android ONNX Runtime 1.30.0 / ML Kit Japanese 16.0.1|
|時計|pipeline: Android elapsedRealtime（1ms）、stage: elapsedRealtimeNanos。Macネイティブ推論は実施していない|
|温度／電源|Mac lowpowermode=0 / AC。途中・後のpmset thermに警告記録なし。CPU実温度・run別温度は未測定。AVD battery=25℃/thermal=約30℃は模擬値で実温度ではない|
|反復|各datasetでfresh process B→D、D→B、B→D。追加prewarmなし。frame0とlazy-init増分frameをwarmから除外|
|実機|開始時ADB 0台、後は専用emulator-5580のみ。Galaxy S25未接続|
|実動画／音声|Galaxy処理、PixelCopy/poll/busy、TTS request→onStart、可聴開始p90/p95、字幕消失前の発話、動画イベント取り逃し、消費電力、長時間発熱は全て未測定|
|新規素材|完全未使用動画の追試は未実施。今回の既知素材を独立holdoutと呼ばない|

実行時刻はMacのrunログ作成時刻と結果ファイル保存時刻から復元した値。18runの詳細はsummary.json/run_times。単体の温度観測を全runの温度とみなさない。cold表はprocessごとの初回処理であり、アプリ起動全体の時間ではない。

|dataset|pass|順|開始時刻（JST）|B warm中央値|D warm中央値|
|---|---|---|---|---|---|
|dev-cat|1|B→D|16:11:26 / 16:11:34|180.00|470.00|
|dev-cat|2|D→B|16:11:50 / 16:12:06|179.00|471.00|
|dev-cat|3|B→D|16:12:14 / 16:12:21|177.00|465.00|
|dev-watch|1|B→D|16:12:56 / 16:13:00|110.50|257.00|
|dev-watch|2|D→B|16:13:09 / 16:13:17|110.00|258.00|
|dev-watch|3|B→D|16:13:21 / 16:13:26|111.00|311.00|
|heldout|1|B→D|16:13:44 / 16:13:54|174.50|567.00|
|heldout|2|D→B|16:14:20 / 16:14:41|161.50|448.00|
|heldout|3|B→D|16:14:51 / 16:15:01|163.00|451.00|

上の時刻欄は各方式の**開始時刻**を順に示す。測定全体は16:11:26〜16:15:22 JST（replay）、components終了はその後。温度／省電力の履歴はMac内private/environment-after.txtに保管。

## 旧値と新値：精度とfull pipeline

精度はpass1の全画像。時間は3反復をpoolし、nearest-rank p95/p99。入力bitmap decode・ADB転送・APK install・engine constructorはpipeline時計外。時計内にはcoarse→候補選択→crop→refine→文字選別→consensus/順序処理と診断構築を含む。実際のcapture待ちとTTS待ちは含まない。

|dataset|方式|CER（旧=新）|Raw CER|SER|完全一致|旧warm中央値ms|新warm中央/p95/p99 ms|warm n|
|---|---|---|---|---|---|---|---|---|
|dev-cat|B|12.84%|19.79%|50.00%|14/28|193.50|178.00 / 242.00 / 275.00|81|
|dev-cat|D|0.94%|12.03%|21.43%|22/28|468.50|466.00 / 600.00 / 828.00|81|
|dev-watch|B|8.54%|8.46%|7.14%|26/28|113.00|111.00 / 137.00 / 141.00|78|
|dev-watch|D|8.54%|8.46%|7.14%|26/28|249.00|258.00 / 335.00 / 386.00|81|
|heldout|B|10.32%|16.88%|39.53%|26/43|351.50|166.00 / 211.00 / 356.00|126|
|heldout|D|7.67%|14.92%|30.23%|30/43|1139.00|471.00 / 643.00 / 680.00|126|

全体完全一致はB 66/99、D 78/99。完全見逃しは両方2/87字幕画像、空正解の余計な出力は両方3/12。全99画像の総合中央値より動画別値を優先する。heldoutの旧B/D中央値351.5/1139msに対し新166/471msは大きく変わった。画像・モデル・出力・呼出回数は再現したが、同じM4/16GiB/4CPU/4GiBでも新規AVD・新APK・実行状態が異なる。旧runごとの温度／ホスト負荷を復元できず、時間差の原因は未確定。約0.8秒や新差305msをGalaxyの追加待ち時間と呼ばない。新run間にもD heldoutの負荷変動がある。

|dataset|方式|cold初回 ms（3反復）|PSS peak KiB（3反復）|RSS HWM KiB（3反復）|
|---|---|---|---|---|
|dev-cat|B|400, 410, 427|336038, 336545, 350951|482252, 482484, 482140|
|dev-cat|D|625, 641, 629|299078, 299180, 299336|465076, 465452, 465272|
|dev-watch|B|173, 179, 166|362603, 362983, 362597|480992, 481324, 480800|
|dev-watch|D|271, 266, 285|334952, 335159, 335245|501220, 501512, 501588|
|heldout|B|309, 311, 287|362175, 353086, 361832|484032, 483612, 483492|
|heldout|D|357, 339, 339|282787, 282396, 282557|472108, 471716, 471952|

dev-watch Bはframe0の他にlazy recognizer初期化frame `00000001791807269336`（240/223/235ms）もwarmから除外。初期化nsと全画像時間はJSONへ保存。PSSは画像間サンプリングで、瞬間最大値を保証しない。

## 行数 × 背景文字・名札

モデル出力を見る前に、既存GT改行・caption_linesと原画contact sheetを照合して行数を固定し、背景文字の有無／種別を付与した。GT・ラベルともCodex画素監査で、人手監査待ち。時計のmotion blurなど3画像は背景不明。0行には本文から除外した章切替／句読点カードを含む。字幕位置は猫／移行が上、時計が下。4行以上は0枚。表示の正確な持続時間と短時間／常時3行分類は未測定。イベントは同一動画で隣接サンプルのGT全文が同じrunをまとめた**観測イベント**で、動画全フレームのイベント監査ではない。

|dataset|GT行数|背景|画像n|字幕event n|B CER|D CER|D−B pp|完全一致 B/D|既知混入 B/D|B中央/p95 ms|D中央/p95 ms|
|---|---|---|---|---|---|---|---|---|---|---|---|
|dev-cat|2|無|3|1|0.00%|1.15%|1.15|3/2|0/0 (監査1)|136.00/170.00|342.00/345.00|
|dev-cat|3|無|3|1|0.00%|0.00%|0.00|3/3|0/0 (監査1)|178.00/186.00|467.00/471.00|
|dev-cat|3|有|22|9|16.59%|1.07%|-15.53|8/17|4/0 (監査9)|182.00/245.00|485.00/605.00|
|dev-watch|0|無|1|0|未評価|未評価|未測定|1/1|0/0 (監査1)|7.00/25.00|97.00/99.00|
|dev-watch|0|有|6|0|未評価|未評価|未測定|6/6|0/0 (監査3)|8.00/41.00|95.00/146.00|
|dev-watch|0|不明|1|0|未評価|未評価|未測定|1/1|0/0 (監査1)|5.00/6.00|92.00/104.00|
|dev-watch|1|無|3|1|33.33%|33.33%|0.00|2/2|0/0 (監査1)|81.00/91.00|211.00/249.00|
|dev-watch|1|有|15|4|6.67%|6.67%|0.00|14/14|0/0 (監査4)|116.00/132.00|276.00/336.00|
|dev-watch|1|不明|2|1|0.00%|0.00%|0.00|2/2|0/0 (監査0)|135.50/139.00|282.50/335.00|
|heldout|0|有|4|0|未評価|未評価|未測定|1/1|2/1 (監査3)|61.00/93.00|187.00/296.00|
|heldout|3|無|29|12|9.77%|7.03%|-2.74|16/19|0/0 (監査12)|168.00/223.00|465.00/602.00|
|heldout|3|有|10|4|2.37%|0.00%|-2.37|9/10|0/0 (監査4)|170.50/200.00|526.00/670.00|

層をまたぐ同一イベントがあるため、event nを単純に足さない。0行CERは分母0なので未評価。存在しない各0/1/2/3/4+ × 有/無/不明のセルはsummary.jsonにn=0・nullで明示。

|dev-catの名札 × 行数|画像n|字幕event n|B CER|D CER|
|---|---|---|---|---|
|名札なし × 1〜2行|3|1|0.00%|1.15%|
|名札なし × 3行|3|1|0.00%|0.00%|
|名札あり × 1〜2行|0|0|未評価|未評価|
|名札あり × 3行|22|9|16.59%|1.07%|

dev-watch / heldoutには名札なし。時計銘／刻印、章切替、図中文字を名札と呼ばない。**名札なし3行でもBが完全一致する反例があり、名札あり3行に改善が集中**している。一方heldoutの名札・背景なし3行29枚ではB 9.77%→D 7.03%なので、利点を名札除外だけに還元できない。背景なし1〜2行と背景あり3行は内容・場面も異なり、名札あり1〜2行セルが空であるため独立した原因は要因未確定。

旧text_boxesがある40枚だけの既知背景文字混入はB 6/40→D 1/40（猫4→0、移行2→1、時計0→0）。未アノテーション59枚の混入を0件とみなさない。全字幕の完全見逃し2件は時計1行でB/Dとも同一画像。数字トークン不一致はB 6 / D 3、否定語トークン不一致0/0、行の厳密な並べ替えだけの不一致0/0。これらは**字面の診断指標**で、数値／否定の意味を人手採点した誤読件数ではない。欠落・句読点崩れ・部分文字の順序はCER/Raw CERへ含まれる。層ごとの全指標・優位IDはsummary.jsonに保存。

|反例／異常例|画像ID|B/D edits|確認|
|---|---|---|---|
|名札なし3行|00000001791806939890|0 / 0|Bでも全3行を読む|
|名札なし2行・B優位|00000001791806939962|0 / 1|Dの1文字置換|
|名札あり3行・D優位|00000001791806940125|9 / 0|Bの文字／句読点崩れ、ML公開行は4行|
|名札混入除去|00000001791806940344|8 / 0|選別結果への名札混入をDで除外|
|背景なし3行・部分見逃し|00000001791807506186|21 / 15|Bは1行のみ、Dでも3行全ては揃わない。ML粗出力は3行|
|背景なし3行・B優位|00000001791807506140|0 / 1|Dの字形置換。ML粗出力は3行|

差の不確実性はフレーム独立bootstrapを使わず、動画を固定した字幕イベントcluster bootstrap（seed53、2000回）で算出した。D−B CER差の探索的95%区間：猫 −19.34〜−4.95pp、時計 0〜0pp、移行 −5.20〜−0.66pp。1イベントのセルは区間を出さない。3動画だけなので未知動画の母集団への優越や有意差を主張しない。

## コスト内訳：呼出回数とstage時間

下表の回数は**全画像を1反復したとき**の値（3反復とも同じ）。時間はwarm 3反復の**1画像あたり累計stage時間**の中央値/p95。ML Kitは非公開detectorだけの時間ではなく認識・callback込み、v6 Detはpreprocess・ONNX・DB postprocess、v6 Recはpreprocess・ONNX・CTC decode込み。selection時計はflushBefore＋tracker selectAll＋refinement selectorで、回数はtrackerとselectorを数える。crop時計はcoarse preparation＋refinement band cropで、strip recovery crop・engine内部の行cropは残差に含む。consensus、JSON、dispatch、callback等も残差。個別stageの中央値を足してfull中央値に一致させない。

|dataset|方式|stage|全画像call/反復|warm call/画像平均|中央/p95 ms/画像|p99|全処理ms/call（cold込み）|
|---|---|---|---|---|---|---|---|
|dev-cat|B|mlkit|66|2.37|32.45/67.71|91.02|18.67|
|dev-cat|B|detector|0|0.00|0.00/0.00|0.00|未測定|
|dev-cat|B|recognizer|91|3.26|128.08/149.97|171.92|38.18|
|dev-cat|B|selection|66|2.37|6.80/7.61|7.71|2.78|
|dev-cat|B|crop|66|2.37|6.30/7.66|8.47|2.75|
|dev-cat|D|mlkit|0|0.00|0.00/0.00|0.00|未測定|
|dev-cat|D|detector|56|2.00|154.14/163.02|259.54|77.83|
|dev-cat|D|recognizer|188|6.74|294.69/419.06|538.63|45.01|
|dev-cat|D|selection|56|2.00|6.52/8.35|8.60|3.17|
|dev-cat|D|crop|56|2.00|5.78/6.18|6.56|2.86|
|dev-watch|B|mlkit|46|1.65|17.53/23.83|39.30|12.85|
|dev-watch|B|detector|0|0.00|0.00/0.00|0.00|未測定|
|dev-watch|B|recognizer|18|0.65|83.96/108.33|115.18|91.89|
|dev-watch|B|selection|46|1.65|1.04/1.89|2.31|0.64|
|dev-watch|B|crop|46|1.65|1.79/2.17|3.19|1.01|
|dev-watch|D|mlkit|0|0.00|0.00/0.00|0.00|未測定|
|dev-watch|D|detector|46|1.67|118.10/128.68|156.17|68.12|
|dev-watch|D|recognizer|26|0.93|132.07/201.26|247.88|113.80|
|dev-watch|D|selection|46|1.67|0.87/1.94|2.64|0.59|
|dev-watch|D|crop|46|1.67|2.07/2.89|4.43|1.16|
|heldout|B|mlkit|90|2.10|30.35/48.12|75.51|16.52|
|heldout|B|detector|0|0.00|0.00/0.00|0.00|未測定|
|heldout|B|recognizer|122|2.88|106.30/153.25|276.11|38.38|
|heldout|B|selection|88|2.05|6.03/7.67|8.75|2.67|
|heldout|B|crop|88|2.05|6.17/7.14|7.53|2.88|
|heldout|D|mlkit|0|0.00|0.00/0.00|0.00|未測定|
|heldout|D|detector|89|2.07|153.39/171.46|198.98|74.77|
|heldout|D|recognizer|258|6.12|292.48/457.32|486.58|48.60|
|heldout|D|selection|89|2.07|6.10/7.81|9.15|2.71|
|heldout|D|crop|89|2.07|5.71/6.18|6.40|2.67|

フルreplay合計：B ML Kit 606 / v6 Det 0 / v6 Rec 693回、D ML Kit 0 / v6 Det 573 / v6 Rec 1416回。Dで字幕候補が変わり、認識の回数が約2倍になる。字幕選別はB 600 / D 573回、測定対象cropはB 600 / D 573回。詳細stage ns増分、init ns、OCR時間、call、PSSを各画像×3反復でframes.numeric.jsonへ保存。

**独立component診断**はGT矩形のある40画像でML Kit公開行とv6 Detを各画像3回、GT手動crop218行でv6 Recのみ各crop3回測定。各画像/cropの2・3回目を集計した値で、full replayの1画像当たり累計と混同しない。モデルconstructorは別欄。GT矩形や全文は公開していない。

|dataset|ML call|ML中央/p95 ms|v6Det call|Det中央/p95 ms|字幕IoU≥.5検出 B→D|v6Rec call|Rec中央/p95 ms|crop字幕CER|
|---|---|---|---|---|---|---|---|---|
|dev-cat|33|10.47/13.03|33|87.85/90.40|32/32 → 32/32|243|62.90/64.31|2.24%|
|dev-watch|30|7.96/11.05|30|87.81/90.76|5/5 → 5/5|60|84.88/87.69|5.16%|
|heldout|57|10.04/13.54|57|87.57/94.35|48/48 → 48/48|351|64.01/82.91|2.20%|

独立診断の字幕85矩形はML Kit/v6とも85/85検出。前回と同じで、「3行目を初めて見つけたからDが改善した」とは説明できない。heldout独立検出の旧40.3/220.1msに対し新10.04/87.57ms（差+77.53ms）。これもGalaxyや可聴遅延ではない。時計は下字幕ROIのみ、図中文字のignore regionは旧GTの定義通り。全box precision/recallとfirst inference、constructorはJSONへ保存。

## 条件付きD：オフライン机上比較

真値oracleはGTが3行以上の64画像だけDを選ぶ。本番で使えない判定。`oracle_direct`はBを事前実行せずD/Bを選べる上限試算、`oracle_B_first`は判定前にfull Bを払う試算。可実装候補はB粗出力のraw_rowsだけを入力にする`trigger.py`：日本語2文字以上・幅≥.15・高さ.015〜.20の行から、中心が上部<.50または下部>.65の3行で、隣接中心の間隔が中央値行高の.5〜2.5倍なら発動。新出力を見る前に固定し、GT/D出力をコードにも閾値探索にも使っていない。保存済み既知動画の文脈は既に知られているので独立blind testとは呼ばない。

|方式|CER|完全一致|平均ms|中央/p95/p99 ms|追加v6Det/画像|ML/Det/Rec call/画像|
|---|---|---|---|---|---|---|
|B_fixed|10.77%|66/99|147.59|160.00/225.00/353.00|0.00|2.05/0.00/2.38|
|D_fixed|5.62%|78/99|407.71|451.00/602.00/680.00|0.00|0.00/1.94/4.86|
|oracle_direct|5.58%|79/99|357.43|451.00/602.00/680.00|0.00|0.57/1.37/4.74|
|oracle_B_first|5.58%|79/99|477.28|611.00/824.00/930.00|1.37|2.05/1.37/6.85|
|B_output_trigger|5.58%|79/99|477.28|611.00/824.00/930.00|1.37|2.05/1.37/6.85|

時間は両方式のcold除外を合わせた共通warm285観測で比較。conditionalの時計は実測時計を足した**算術推定**で、Android上のtrigger自体・switch・初回Dロード・共有tracker状態・メモリ保持コストは未測定。B/D独立の連続replay出力を画像ごとに選んだだけなのでライブ分岐の出力も保証しない。

固定ルールは64/99画像で発動、真の3行以上64/64検出、precision/recall=100%、false positive=0/35、false negative=0/64。ただし既知3動画だけで、見逃した3行目をBの2行から推測する能力や、別動画の名札誤発動は未評価。背景リスク検知と3行検知を同一指標にしていない。

B後にDを足す方式の平均477.28msは、D固定407.71msより**69.57ms（17.1%）重い**。p95も824ms対602ms。v6 Det追加390回/285warm観測（1.37回/画像、refine込み）。一方oracle_directは357.43msだが、真値判定を無料で事前に知れる非現実的な条件。この99枚では条件付き精度5.58%とD固定5.62%の差は1 edit/1画像だけで、背景混入は2/40対1/40と条件付きが悪い。したがって**オフライン探索はできたが、条件付きの採用根拠は不足**。本番への分岐追加、実機試作、P1〜P4改善は行っていない。

## 再現手順・検査・失敗履歴

実際に使用した入口はworkspaceの`runtime/env.sh`。SDK/JDK/GradleはMac既存パスを確認して読み取り使用し、Gradle cacheをworkspaceへAPFS copy、署名鍵と新AVDはprivate/runtime内で新規作成。元データは `/Users/itohidetaka/Documents/Codex/2026-10-12-github-pr-33-4-ocr-https/datasets/{dev-cat,dev-watch,heldout}`。原画アクセス権を持つ再現者は自身のパスへ置換する。

```bash
# 空の専用workspaceで実施（既存作業treeに書き込まない）
git clone --bare --no-hardlinks "$EXISTING_REPO" source.git
git --git-dir=source.git remote set-url origin https://github.com/eltnegcellist/burned-caption-reader-android.git
git --git-dir=source.git fetch origin main codex/lock-ocr-v6-small-20261013 codex/strict-bd-retest-protocol-20261013
git --git-dir=source.git worktree add -b codex/bd-detection-retest-results-20261013 repo 294536fc3a8ced4d69ff3a641f295d93196d43bc
# 結果ブランチの診断変更を適用後、確認済み環境変数を設定
source runtime/env.sh
java -version
adb devices -l
python3 repo/tools/models/prepare_v6.py
(cd repo && gradle --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest)
"$ANDROID_HOME/emulator/emulator" -avd BD_Retest_API36 -port 5580 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
python3 repo/tools/four-way-evaluator/run.py \
  "$DATASETS/dev-cat" "$DATASETS/dev-watch" "$DATASETS/heldout" \
  --output private/results --adb "$ANDROID_HOME/platform-tools/adb" \
  --device emulator-5580 --label 294536fc-bd-retest-diagnostic \
  --passes 3 --modes v6_rec v6_both --component-mode bd-components
python3 repo/tools/bd-retest/analyze.py \
  --datasets "$DATASETS" --results private/results \
  --labels private/strata-labels.json \
  --output repo/docs/experiments/bd-detection-retest-20261013
python3 -m unittest discover -s repo/tools/bd-retest -p 'test_*.py'
```

既存repoにはgradlewがないため固定Gradle 8.13を使用。Pythonは既存venv（PyYAML/Pillow検査済み）を実行。runnerは各mode前にforce-stopし、image-evaluatorが同じapp/test APKをinstall -rして全画像hashを確認後にreplayを実行。warmup追加なし。人手監査待ちのGTを変更せず、ラベル不明はunknown、n=0はnullにした。

- 最初のgit fetchはsandbox DNS失敗、ネットワーク許可を伴う再実行で成功。最初のgh認証確認失敗もネットワーク可能な再確認で有効な認証を確認。
- 最初のADB/Gradleはsandboxのsocket禁止で失敗。許可された再実行でADB開始・offline build成功。データ・モデル不一致はなく、誤った途中結果を採用していない。
- avdmanagerは既存SDKのread-only / emulator package認識で失敗。既存API36の確認済みconfigを専用AVD名にコピーし、**ユーザーデータをコピーせず**fresh AVDを作成。設定は本レポートとenvironment.jsonに保存。
- 集計をcomponents終了前に呼んだ1回はmissing fileで停止。終了後に再集計し、欠損値を実測として埋めていない。
- Android JUnit **237件成功（失敗/skip 0）**、B-only trigger／集計回帰 **10件成功**。全18replayと3component instrumentation成功。全594画像のGT ID/時刻/文字列・hash・edit distance・同一APK・engine・call gateを検査。旧summary完全一致と新3反復出力一致を確認。

## 判断

|候補|判断|
|---|---|
|D固定|既知99枚の精度優位を再現。通常アプリの方針を維持。ただしGalaxyでの可聴遅延／ライブ取り逃しは未評価|
|B固定|静止replayで速いがCERと背景混入が増える。通常方針を変更する根拠はない|
|条件付きD|oracle/B出力ルールの探索は成立。full B後DではD固定より高負荷。未知動画・live state・音声未評価のため採否保留|
|情報不足|3行と名札の独立因果、未使用素材への一般化、Galaxy実時間／音声の採否は判断保留|

公開出力は本レポート、比較コード、回帰テスト、数値・ID・hashのみ。全文を含むprivate/results、baseline再集計、PNG contact sheets、build/emulator logs、署名鍵とAPKは今回Mac workspaceのprivate/runtime/repo-buildに保管する。静止画像CERや即時完了TTS replayから実音声の取り逃し率を導いていない。

## 追補：検出100%でもCER差が生じる理由とB/Dの速度比較

**100%は「字幕の位置が見つかった割合」であり、最終字幕文字列の正解率ではない。** 独立検出診断の40画像・85字幕矩形で、IoU≥0.5の一致が両者85/85だった。99画像の全字幕を最終出力で漏れなく読めたという意味ではない。異なる矩形でもIoU≥0.5なら成功になる。字幕以外の検出、文字認識、字幕候補の選別、修正文の採否はこの指標へ含まれない。

精度差を文字の編集数で分解すると、猫109→8（差101）、時計48→48（差0）、移行117→87（差30）、合計274→143（差131）。全体CERは10.77%→5.62%、差5.15pp、編集誤りは47.8%減る。差の77.1%は猫動画に集中している。以下は保存済みstage出力の追跡で確認した原因で、追加の推論や改善実装ではない。

### 1. 名札をまとめる違いが字幕選別に影響した

猫ではBが下部の名札2個を1行へまとめ、Dが2行（横並びの独立box）として返している。例えば`00000001791806940344`のB下部行は幅約0.418、Dの2行は各約0.227と0.168、間隔約0.032だった。名札除外には別のSceneTextFilter経路もあるため、この例の除外を`isParallelCompactLabel`のgap≥.08だけに帰属させない。この例のraw_rowsではseparated_partsはなく、Dの二つの幅は強い字幕形状の単行条件width≥.35を下回り、文字数3/5も代替条件length≥9を下回る。Bの結合行は幅.418で幅条件を満たす。保存bandと既存選別条件がこの違いに対応する。ただし候補・laneの内部決定理由ログはないため、すべての下部候補の棄却を一つの条件だけで完全再現したとまでは主張しない。同じ選別コードでも受け取る幾何と文字構造が違う。

保存結果では、Bに字幕bandとは別の下部名札bandが10画像で残り、各8文字、計80文字が最終字幕へ余分に入っていた。Dではその下部bandは残っていない。GTで上部字幕と分かっているcatに限り、保存済み最終出力から下部bandだけを除く事後診断をすると、Bの109 editsは29へ減る。これは**80文字分が背景混入だったという出力上の会計**で、名札を消した原画で再推論した因果実験ではない。

先の「猫の混入4件」は旧text_boxesのある11画像だけの照合。今回の10件は全28画像の原画／GT位置と保存bandを使った追跡なので、採点範囲が違う。全体131文字の差に対し、この名札80文字は約61.1%の大きさを占める。字幕そのものの認識に失敗しなくても、正しく読んだ名札を字幕へ足せばCERは上がる。

### 2. Bはv6で正しく読み直しても、古いML Kit文へ戻す場合がある

BのcoarseはML Kitの認識文。v6はrefineで使用されるが、既存の受理ポリシーとOcrRefinementSelectorを通して最終文を選ぶ。Dはcoarseからv6認識文を使う。したがってPP recognizerの同一モデル固定だけでは、両経路の最終認識源やcropが同じにはならない。

`00000001791806940101`ではBの粗文に6 editsあり、v6 refineは0 editsだったが、最終selectedは粗文のままで6 edits。`...`に似た点がML Kitで英字等へ化けるとcomparisonKeyでも残る。修正後にその余計な文字が消えると、選別は「文字数が減りすぎた」とみなす場合がある。

同例はcoarse 30文字→refine 25文字に対し、既存の必要最小文字数ceil(30×.85)=26を下回る。`00000001791806940115`も30→25、`00000001791806940142`は25→21（必要22）。`00000001791806940125`は粗文とrefineの類似度0.714で閾値0.80を下回る。実装のtrustedPpはbackend `ppocrv5`だけを判定し、Bの`ppocrv6_small`はその緩和を受けない。**v6を実行したことと、その修正を採用したことは別**である。

この4画像では既に存在するrefine文の編集数は計3、実際のselectedは計25で、22 editsを選別段階で残していた。猫の名札band80文字とこの4画像は重複しない。109−80−22=7は事後的に二つの選択を変えた文字会計で、改修済みBの精度を実測した数字ではない。Dにも選別やcrop由来の残る誤りがあり、猫Dは8 editsだった。P1〜P4の改善は再開せず、コードは変更していない。

### 3. 検出後に字幕候補から落ちる例もある

移行動画`00000001791807506186`はB/Dのraw_rowsに3字幕行が存在するが、Bのselected bandは1行、Dは2行で、最終21/15 editsとなった。独立検出100%とは矛盾しない。検出後の候補生成／追跡／refine選択でも行が落ちる。時計`00000001791807269494`も粗OCRには字幕行があるが両者bandsが空で最終19 editsになる。B/Dそれぞれの矩形、文字、信頼度が後段へ影響するため、検出率だけから最終CERを推定できない。

共通cropに同じrecognizerを入力した厳密なB/D置換実験は未実施なので、全131文字を検出器ネットワークだけの因果効果へ配分することはできない。一方、**名札の追加・正しいrefineの不採用・候補選別での行落ち**という差の発生場所は保存stageから具体的に確認できた。

### B/Dの実行スピード

同じMac M4上のAPI36 ARM64エミュレーター、同一99画像、3反復、初回／初期化を除いたfull pipeline。

|動画|B中央値 / p95|D中央値 / p95|D−B中央値|D/B時間比|
|---|---:|---:|---:|---:|
|猫・名札|178 / 242ms|466 / 600ms|+288ms|2.62倍|
|時計・小字幕|111 / 137ms|258 / 335ms|+147ms|2.32倍|
|移行・3行|166 / 211ms|471 / 643ms|+305ms|2.84倍|

**今回のエミュレーターではBが速く、Dは全体処理に約2.3〜2.8倍の時間を要する。** 初回を含めると別の分布になる。Galaxyの動画処理・可聴遅延の数字ではない。旧移行D中央値1139msから今回471msへの違いは実行環境の状態等の原因未確定であり、今回の同時条件での比較を採用する。

Dの重さは検出だけでなく認識処理も増えるため。Bは粗処理にML Kit、選んだbandのrefineにv6を使う。Dは粗処理からv6で全候補を読み、refineでもv6で読み直す。全99画像を1反復するv6 Rec呼出はB231回、D472回（2.04倍）。3反復の合計ではB693 / D1416回。字幕選別の処理時間自体は両者ほぼ同じ。

中央値は足せないので、時間差の内訳は**共通warm画像の平均時間**で示す。検出欄はBのML Kit公開OCR時間対Dのv6 Det時間（単体networkの比較ではない）。

|動画|B→D full平均|検出段階の増分|v6認識段階の増分|選別・crop・その他の増分|
|---|---:|---:|---:|---:|
|猫|186.59→481.01ms|+115.07ms|+178.41ms|+0.94ms|
|時計|79.46→222.03ms|+95.90ms|+45.70ms|+0.96ms|
|移行|164.70→475.53ms|+123.05ms|+186.80ms|+0.98ms|

数字は[explanation.numeric.json](bd-detection-retest-20261013/explanation.numeric.json)へ保存。保存結果から同じ説明を再生成するコマンド：

```bash
python3 tools/bd-retest/explain.py \
  --results "$PRIVATE_RESULTS" \
  --numeric-frames docs/experiments/bd-detection-retest-20261013/frames.numeric.json \
  --output docs/experiments/bd-detection-retest-20261013/explanation.numeric.json
```
