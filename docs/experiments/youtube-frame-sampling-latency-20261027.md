# YouTube表示フレームを使う字幕読み上げ遅延のA/B実験（2026-10-27）

状態: **試験コード／本番には未反映。Galaxy S25の実動画・可聴音声は未測定。**

## 制約

- YouTubeの動画ファイル、ストリームURL、字幕トラック、未来フレームには一切アクセスしない。再生中の画面に既に表示されている映像に対するPixelCopyのみ。
- 動画のseekによる先読み、別の非表示YouTubeプレーヤー、コンテンツ保存をしない。
- モデル／E方式／字幕選別／TTS設定は固定する。実験するのは画面サンプリング起動方法と、**上段字幕待機（700/300/0ms）**。既定値は従来の700ms。
- 追加の機能は**デバッグAPKかつ明示的な起動extra**だけで利用可能。extraなし・releaseでは既定の480ms周期／700ms待機。
- 外部権利の動画のダウンロード・再配布は不要。
- requestVideoFrameCallbackはフレームがcompositorへ送られる際の通知で、表示前の字幕や未来動画へのアクセスではない。WebView版・YouTube DOMによっては機能しないので480msへのfallbackがある。

## 比較する3方式

|モード|起動extra|動作|
|---|---|---|
|従来480ms|poll480|480ms周期・処理中ならbusy skip|
|短周期240ms|poll240|240ms周期・処理中ならbusy skip。OCRを並列化しない|
|フレーム連動|frame|requestVideoFrameCallbackからJS→Android WebMessageListenerへ**フレーム時刻等の数値のみ**送信。180ms以上の間隔に制限し、busy時はskip。フレーム通知が1000ms届かないと480ms周期へfallback|

WebMessageListenerはYouTubeの3つのHTTPS originかつmain frameに限定、受信データは固定形式の数値のみ。任意のJS→Androidコマンド実行口は作らない。

## 追加：上段字幕待機を700ms／300ms／0msで比較

| 起動extra `caption_order_wait_ms` | 動作 | 主な検証 |
|---:|---|---|
|700（既定）|従来どおり。上に未確定の行がある場合だけ最大700ms待つ|3行の読み順・完全一致|
|300|同じ規則のまま300msで期限解除|短縮できた待ち時間と上段欠落|
|0|上段未確定でも即時解除（比較用極端条件）|速さの理論上限と下→上・重複の危険|

**通常字幕は待機しない。** 「最大700ms」は字幕すべてへ毎回加算される固定遅延ではない。
新規orderイベント:
- `sampling_order_pending` 上段待ち等によりバッファに残った行数
- `sampling_order_release` 解除理由（`no_upper` / `upper_cleared` / `deadline`）と`held_ms`（**実際にバッファへ入ってから解放されるまでの経過時間**）、上段ブロック歴
- `sampling_experiment.order_wait_ms` 起動時の実際に選択された上限。未指定／不正値は700ms。

**互換性:** 既存の締切計算（元フレームのタイムスタンプ＋最大待機）は変えない。OCR終了後にバッファへ登録した実時刻を別に持ち、解放までの実経過を記録する。従って`held_ms`と最大上限の差を単純に「未使用時間」として解釈しない。ブラウザ側は未来フレームを取得せず、JavaScript画面キャプチャもしない。

## Galaxy S25だけで設定を切り替える（ADB不要）

研究用のデバッグAPK `動画内字幕リーダー（速度実験）` をインストールする。通常版とは別アプリのため、通常版の設定・データは変わらない。

1. 試験版を開き、トップ画面の **「読み上げ設定」** をタップする。
2. 下へスクロールして **「速度実験（試験版のみ）」** のカードを開く。
3. **「字幕画像の取得方式」** で `480ms周期（従来）` / `240ms周期` / `フレーム更新に連動` を選ぶ。
4. **「上段字幕の最大待機時間」** で `700ms（従来）` / `300ms（短縮）` / `0ms（待たない）` を選ぶ。
5. 戻って動画を再生する。値は試験版の端末内に保存され、次に開く動画でも保持される。再生中の **「設定」** から変更した場合も新しい字幕から反映され、既に待機中の字幕の期限は変えない。
6. **「診断データ」** の記録をオンにしてテストし、各条件の終了後にZIPを書き出す。元ZIPに動画フレームやOCR文面が含まれるため**公開GitHubへアップロードしない**。

追加の権限やYouTube動画ファイルへのアクセスは使わない。**ADBを使う比較は任意**で、起動時のコマンドextraは保存済み画面設定より優先される。ただし動画内の設定画面で値を変えた後は新しい設定が有効になる。

`0ms` は上段の字幕が読まれる前に下段を読み始める可能性がある。実験開始時にはまず `480ms + 700ms` の既定値から比較する。

## MacとGalaxy S25で手動比較する方法

1. ADBで自動比較したい場合にのみこの節を使う。通常は上記の設定画面で操作できる。まずGitHub ActionsのPR側ビルドとunit testを確認。試験用debug APKはパッケージID `jp.hidemaru.burnedcaptionreader.samplingresearch` を使用し、通常Eアプリと別のアイコン名で併存できる。既存の通常アプリをアンインストール／上書きする必要はない。ダウンロードしたZIPから `app-debug.apk` を取り出し、試験版としてインストール。
2. Mac側で `adb devices -l` にGalaxy S25の**許可済みシリアル**が現れることを確認。adbがない場合はGitHub上のソース検証までで止め、実機値を捏造しない。
3. Galaxy S25のAndroid WebViewバージョン、APK SHA、電源・発熱・画質・画面向き、Eモード、TTSエンジン/速度/音量を記録する。動画URL・再生区間は3方式で揃える。
4. 各方式は、Macの `adb shell am start` で既存の公開YouTube動画URLを共有する方法で起動できる。デバッグAPKのMainActivityは `ACTION_SEND` を受け、extraをSharedPlayerActivityに渡す。

```bash
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="YOUR_AUTHORIZED_DEVICE_SERIAL"
VIDEO_URL="https://www.youtube.com/watch?v=YOUR_PUBLIC_VIDEO_ID"
PACKAGE="jp.hidemaru.burnedcaptionreader.samplingresearch"
# モードは poll480, poll240, frame、待機は700, 300, 0 に変更可能。
# まずpoll480を固定して3つの待機を比較し、次に勝者を240/frameで追試する。
MODE="poll480"
ORDER_WAIT_MS=700
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$PACKAGE/jp.hidemaru.burnedcaptionreader.MainActivity" \
  -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$VIDEO_URL" \
  --es caption_sampling_mode "$MODE" \
  --ei caption_order_wait_ms "$ORDER_WAIT_MS"
```

5. 記録したい同じ動画区間の直前で設定の「**動画画像と読み上げログを記録**」をオンにし、各方式の再生区間が終わったら「診断ZIPを書き出す」で端末内に保存する。**開始すると以前のログは消える**ので、方式ごとにZIPを別名保存。記録は直近約2分・最大64MB・2,000件までで、負荷時は欠落することがある。長い実験は短区間に分ける。
6. 元の診断ZIPは動画画面や字幕本文を含む場合があるので**GitHubにアップロードしない**。Macのローカルで数値だけ集計する。

```bash
python3 tools/youtube-sampling/summarize_diagnostics.py "/private/path/poll480.zip" \
  --out "/private/path/poll480-numeric.json"
python3 tools/youtube-sampling/summarize_diagnostics.py "/private/path/poll240.zip" \
  --out "/private/path/poll240-numeric.json"
python3 tools/youtube-sampling/summarize_diagnostics.py "/private/path/frame.zip" \
  --out "/private/path/frame-numeric.json"
```

**段階的比較：** まず`poll480`のまま順序を700→300→0、300→0→700、0→700→300と3周循環し、字幕読み順・欠落・TTSの重複を観察する。問題がない待機時間だけを候補として`poll240`／`frame`でも試す。画像取得方式の比較は480→240→frame、240→frame→480、frame→480→240と最低3周。各周のウォームアップ・再生区間・pause/seekの有無を固定し、イベントごとと周ごとの集計を混同しない。0msで上段の読み順が壊れた場合は即時候補外。

## 出力項目と解釈

- `sampling_trigger`: キャプチャ要求タイミング、poll/VideoFrame/fallbackの区別
- `sampling_captured`: PixelCopy結果を受けた時刻／要求から受信まで／PixelCopy所要時間
- `sampling_ocr_completed`: 画面取得後のOCR+字幕選別完了まで
- `sampling_tts_requested`: OCR開始からTTSへ渡すまで（並び待ちは `frame_id=-1` の場合あり）
- `sampling_tts_onstart`, `tts_start`: Android TTSエンジンのonStart、実際の**可聴開始ではない**
- `sampling_stats`, `capture_poll`: 処理中スキップ・frame callbackの過剰通知・fallback
- `sampling_order_release`: 上段による追加待機の中央値／p95、上段解除数、締切解除数（`order_buffer_blocked_held_ms`）
- `sampling_order_pending`: 待機中の字幕断片の記録数
- 各モードの画像処理数、忙しいときの見逃し、音声の重複／キャンセル・3行の順番を**併記**する。

**重要:** 現状のログだけでは、実際の字幕が初めて画面に映った瞬間と、スピーカーから最初の音が出た瞬間は確定しない。TTS onStartは音の発生の代用にしない。実機測定でユーザー自身の観察により字幕イベント・実音声を比較するか、適切な権限の範囲で端末／外部マイク測定を追加し、「観測できなかった場合は未測定」とする。

**導入判定の優先順位:** 新方式が短い字幕・3行の順序・誤読・重複・割込みを悪化させず、実機での字幕出現から音声開始が早まった場合に限り候補とする。OCR中央値だけで採否を決めない。未実測の条件を実測済みと報告しない。mainへマージ・既存APK上書き配布は別判断。

公式API参考:
- https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/requestVideoFrameCallback
- https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener)
- https://developers.google.com/youtube/terms/developer-policies
