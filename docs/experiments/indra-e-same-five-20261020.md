# 2026-10-20: Indra OCR 1.1.2 vs 現行E（同一5合成日本語字幕画像）

**検証完了：同一合成5画像の文字精度。実動画99画像、Galaxy S25、同一環境の速度比較は未測定。**

## 検証実行

- Draft PR #63: https://github.com/eltnegcellist/burned-caption-reader-android/pull/63
- **成功**: https://github.com/eltnegcellist/burned-caption-reader-android/actions/runs/39388900750
- Git commit: `7ba9b3470e6cef25f9ef4d10b5e340284096efe8`。
- Indra: `indra-ocr==1.1.2`、Python 3.11、Linux x86-64、ONNX CPU、4 threads、`conv.ocr(gray)` と `conv.ocr(gray,turn=False)`。
- E: `tools/image-evaluator/run.py --mode player-replay --ocr-mode e_mlkit_v6 --subtitle-recheck off`、実際のAndroid製品用 OCRエンジンと字幕選別をAPI 36 x86_64エミュレーター上で実行。Eは通常のML Kit検出 + PP-OCRv6 Small refine + E選別（PP-OCRv6 Detectionは使わない）。
- 入力: `synthetic_japanese_smoke.py:make_cases` が作るPNG（1100×618、Noto CJK、字幕あり4種、字幕なし1種）。`make_same_pixel_dataset.py` で同じ画像を厳密再生成し、**各画像PNG SHA256がIndraとAndroid Eで一致**することを集計スクリプトで強制。
- Androidリプレイの事前固定 `mono_ms` は各ケース2,500ms間隔。この試験は5つの静止画像に対する順次replayで、実動画の480msポーリング、busy skip、実音声、画面回転、動画ごとの未知条件を測っていない。
- 評価：字幕本文だけを独立正解として固定。両方式に同一のUnicode NFKC／空白・句読点除去を適用し、Levenshtein距離からCER算出。名札は正解字幕に含めない。Indraは画像全体のOCR出力、Eは製品の字幕選別後の出力。**工程が違うため純粋なOCR認識器比較ではない**。

## 同一画像での実測

|ケース|E CER|Indra標準 CER|Indra回転なし CER|現行Eの出力|
|---|---:|---:|---:|---|
|日本語1行|0%|0%|0%|今日はいい天気ですね|
|日本語3行|0%|100%|35.90%|3行全文を正しく取得|
|名札混在|100%|63.64%|63.64%|空（字幕選別後の取り逃し）|
|小さい字幕|100%|0%|0%|空（字幕選別後の取り逃し）|
|字幕なし|誤出力なし|誤出力なし|誤出力なし|空|
|**合計72正解文字**|**23 edits / 72 = 31.94%**|**46 / 72 = 63.89%**|**21 / 72 = 29.17%**|正規化完全一致3/5（空を含む）|

Indraの3行画像は通常モードで180度と誤判定し「4722240」「417,4924」「#1270」を出力。画像が正立していることを指定しても3行目が `ROTTOST LIS` に化けた。Eはこの合成3行を全文正しく取得。ただしEは別の2ケースで文字列が空なので、合成5枚の総CERだけで採用方針を変えるべきではない。

名札画像でIndraが出した「山田 太郎」「解説者」は文字として正しいが、読み上げ対象ではないためCER計算では挿入誤りになる。Indraに現行Eの字幕選別を接続していないため、Indraの出力を「字幕読み上げアプリ完成形」とは扱わない。

## 速度を結論しない理由

GitHub同一runner上でも、IndraはネイティブLinux x86-64のページOCR、EはAndroid x86-64エミュレーター内の字幕候補選択／複数OCRからなる製品経路。E/Indraの処理時間数値を倍率比較することは不適切。計測時間は各raw JSONに保存されるが、性能比較表の共通ランキングには用いない。

## 完全未実施・次の必要条件

1. 過去の実動画由来99画像はMacの非公開 `private/` に保存されており、公開GitHubからは得られない。**99枚での Indra vs E 比較は未実施。** この5画像の値をEの既存99画像CER 7.07%と直接比較しない。
2. Macで非公開の99PNG・参照GT・画像SHAを検証して、IndraとEに同じ入力を渡す。Indraの `turn=False` と標準モードを固定し、字幕候補選別の有無／名札混入も明記する。学習済みモデルのライセンスを満たした研究用途に限定し、アプリのモデルを差し替えない。
3. 実機Galaxy S25のライブOCR処理時間、字幕出現→可聴開始、短時間字幕の見逃し、電力・熱は未測定。
4. E側の名札混在・小字幕の空出力の原因がモデル検出なのか字幕選別なのか、公開したraw JSONのstagesを見て切り分ける（通常製品への修正は別指示）。

## 証跡

- GitHub Actions artifact: https://github.com/eltnegcellist/burned-caption-reader-android/actions/runs/39388900750/artifacts/12249280099 （7日で期限切れ）
- 実行コード: `tools/indra-ocr-research/make_same_pixel_dataset.py` / `synthetic_japanese_smoke.py` / `compare_e_indra_same_pixel.py`。
- 本報告の数値は成功runの `SAME_IMAGE_COMPARE` および `CASE` ログを転記。過去の失敗run（SDK設定、シェル改行）は環境構築失敗でありOCRスコアに含めない。
