# MacのCodex実験：実動画99枚でIndra OCR 1.1.2と現行Eを直接採点

**状態：実験の実行準備と入力検証コード。Macの非公開99PNGへのアクセス・Indra推論実測はこのGitHub PR上では未実施。** 一般向け字幕リーダーのOCR実装・設定・メインブランチは変更しない。

## 実行環境

参照素材：過去の4方式B/D/E比較で使用した元動画フレームをMacに保持した
`datasets/{dev-cat,dev-watch,heldout}` から厳密に同じ99枚を使用する。
公開GitHub `docs/experiments/bd-detection-retest-20261013.md` に元データの保存workspace名がある（環境に合った実在パスを確認すること）。

以前のE比較の保存JSONは同一workspaceの
`private/e-experiment/results/{dev-cat,dev-watch,heldout}-e_mlkit_v6-1.json` を指定する（見つからなければE再計測か、保存JSONの復元が必要）。
実際にMacのCodexが `find` 等で確認し、ファイルがないときは画像や保存結果を推測・再構成しない。

ユーザーが確定した基準は現行**E方式＝ML Kit粗検出・PP-OCRv6 Small認識＋E選別**。
Android E baseline 99画像 **180 edits / 2545 chars = 7.07%**。
この比較コードはEの各フレームの `edit_distance` /`reference_characters` と同一入力PNGのSHAを確認し、全99枚の合計がこの基準に一致しなければ停止する。

## Mac Codex向けコマンド

```bash
# 使っている開発workspaceのrepoで開始。既存のworktree・作業を破壊しない
git fetch origin
git worktree add ../indra99-mac-check origin/codex/indra-e-private99-mac-20261020
cd ../indra99-mac-check

python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install "indra-ocr==1.1.2" "pillow>=11,<13" "numpy>=1.26,<3"
.venv/bin/python tools/indra-ocr-research/test_private99_compare.py -v

# 実際に存在するMacプライベート元データとE保存結果を確認した後だけ設定。
export DATASETS="/absolute/path/to/previous-workspace/datasets"
export E_RESULTS="/absolute/path/to/previous-workspace/private/e-experiment/results"
export PRIVATE_OUT="$HOME/Documents/Codex/private-indra99-evaluation"
mkdir -p "$PRIVATE_OUT"

.venv/bin/python tools/indra-ocr-research/private99_compare.py \
  --datasets "$DATASETS" \
  --e-results "$E_RESULTS" \
  --private-output "$PRIVATE_OUT/raw" \
  --public-summary "$PRIVATE_OUT/summary-only.json" \
  --threads 4 --repeat 3
```

スクリプトの `--private-output` はGit管理の外に限定。ここに正解字幕、Indra全文、元フレームID、ハッシュを保存。**GitHubへアップロード禁止。** `summary-only.json` は匿名化した数値だけだが、ユーザーが確認してから公表する。

## 比較の解釈

|方式|実際に測ること|
|---|---|
|E saved|既存AndroidエミュレーターでのE字幕選別結果。全99枚の元画像ハッシュと参照字幕を照合|
|Indra default|元動画の全フレーム画像をそのままIndra v1.1.2でOCR。自動回転ON|
|Indra known-upright|同じ99枚を `turn=False` でOCR。動画の画像が正立であることを利用|

Indraは元画像の全可視文字を読み、Eは字幕らしい行だけを選別している。元画像の名札・章題などがIndra結果に混入する場合、*字幕リーダーにそのまま採用したときの負担*としてCERの挿入誤りに含める。**検出器または文字認識器単体の優劣だと誤認しない。**
単体認識性能も評価したい場合は、字幕ROIを人手で同一矩形に固定して両方式に与える別実験が必要。GTで後から最も良いIndra行だけを選ぶことは禁止。

以前の日本語3行合成画像におけるIndraの180度回転誤判定を踏まえ、標準と回転OFFを**予め両方**測定して、最終判断に両数値を使う。どちらか良い方を画像ごとにoracle選択して評価しない。

品質の主要指標：全99枚の正解2,545文字に対する文字誤り率（CER）、完全一致フレーム数、字幕全体の空出力、字幕のない画像での誤出力。各動画区分の結果も出す。合成5枚のCERと混合しない。

IndraはMacネイティブCPU、EはAndroid ARM64エミュレーターの既存値なので**単純な速度倍率比較は禁止**。IndraのMac実測処理時間中央値・p95と、Eの既存warm中央値・p95を独立表示する。IndraのGalaxy S25上での所要時間を推定だけで確定しない。

## 成功基準と報告

1. 3つの元 `dataset.json` のSHAが凍結manifestと完全一致し、各PNGのSHAも一致。
2. 99枚すべてで、E保存JSONの参照字幕・frame ID・時刻が元manifestと一致。
3. Python側の比較キーが各E frameのAndroid結果と一致（1つでも違えば停止）。
4. E baseline=180 edits/2545文字を復元。Indra standard/known-uprightの**実推論99枚×3反復**を完了。
5. 各データセットと合計のCER/完全一致/見逃し/背景混入の例/時間を出す。
6. 完了報告は実測のみ。元PNG、動画、字幕全文、診断ZIP、学習済みモデル、秘密情報をGitHubにアップロードしない。
7. 優位性を確認できても実機Galaxy S25での低遅延を証明したことにはならないので、正式アプリへIndraを組み込まない。ライセンス条件の確認は別途必要。

**出力を返す際はまず匿名化 `summary-only.json` と実測表（E / Indra自動 / Indra回転OFF）だけを共有。**
