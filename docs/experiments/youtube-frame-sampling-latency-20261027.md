# YouTube表示フレームを使う字幕読み上げ遅延のA/B実験（2026-10-27）

状態: **試験コード／本番には未反映。Galaxy S25の実動画・可聴音声は未測定。**

## 制約

- YouTubeの動画ファイル、ストリームURL、字幕トラック、未来フレームには一切アクセスしない。再生中の画面に既に表示されている映像に対するPixelCopyのみ。
- 動画のseekによる先読み、別の非表示YouTubeプレーヤー、コンテンツ保存をしない。
- モデル／E方式／字幕選別／TTS設定／読み順待ち最大700msは固定する。実験するのは画面サンプリング起動方法。
- 追加の機能は**デバッグAPKかつ明示的な起動extra**だけで利用可能。extraなし・releaseでは既定の480ms方式。
- 外部権利の動画のダウンロード・再配布は不要。
- requestVideoFrameCallbackはフレームがcompositorへ送られる際の通知で、表示前の字幕や未来動画へのアクセスではない。WebView版・YouTube DOMによっては機能しないので480msへのfallbackがある。

## 比較する3方式

|モード|起動extra|動作|
|---|---|---|
|従来480ms|poll480|480ms周期・処理中ならbusy skip|
|短周期240ms|poll240|240ms周期・処理中ならbusy skip。OCRを並列化しない|
|フレーム連動|frame|requestVideoFrameCallbackからJS→Android WebMessageListenerへ**フレーム時刻等の数値のみ**送信。180ms以上の間隔に制限し、busy時はskip。フレーム通知が1000ms届かないと480ms周期へfallback|

WebMessageListenerはYouTubeの3つのHTTPS originかつmain frameに限定、受信データは固定形式の数値のみ。任意のJS→Androidコマンド実行口は作らない。ブラウザ側は未来フレームを取得せず、JavaScript画面キャプチャもしない。

## MacとGalaxy S25で手動比較する方法

1. 安全のため、まずGitHub ActionsのPR側ビルドとunit testを確認。試験APKはデバッグ署名なので、**既存アプリを消さずに署名の互換性を事前確認**。必要なら別端末／エミュレーターで実験。既存データが消える可能性がある場合はインストール中止。
2. Mac側で `adb devices -l` にGalaxy S25の**許可済みシリアル**が現れることを確認。adbがない場合はGitHub上のソース検証までで止め、実機値を捏造しない。
3. Galaxy S25のAndroid WebViewバージョン、APK SHA、電源・発熱・画質・画面向き、Eモード、TTSエンジン/速度/音量を記録する。動画URL・再生区間は3方式で揃える。
4. 各方式は、Macの `adb shell am start` で既存の公開YouTube動画URLを共有する方法で起動できる。デバッグAPKのMainActivityは `ACTION_SEND` を受け、extraをSharedPlayerActivityに渡す。

```bash
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL="YOUR_AUTHORIZED_DEVICE_SERIAL"
VIDEO_URL="https://www.youtube.com/watch?v=YOUR_PUBLIC_VIDEO_ID"
PACKAGE="jp.hidemaru.burnedcaptionreader"
# それぞれ poll480, poll240, frame に置換して3種類を測定。
MODE="poll480"
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$PACKAGE/.MainActivity" \
  -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$VIDEO_URL" \
  --es caption_sampling_mode "$MODE"
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

再生順による機器負荷の交絡を抑えるため、最低3周、順序は `480→240→frame`、`240→frame→480`、`frame→480→240` と循環。各周のウォームアップ・再生区間・pause/seekの有無を固定し、イベントごとと周ごとの集計を混同しない。

## 出力項目と解釈

- `sampling_trigger`: キャプチャ要求タイミング、poll/VideoFrame/fallbackの区別
- `sampling_captured`: PixelCopy結果を受けた時刻／要求から受信まで／PixelCopy所要時間
- `sampling_ocr_completed`: 画面取得後のOCR+字幕選別完了まで
- `sampling_tts_requested`: OCR開始からTTSへ渡すまで（並び待ちは `frame_id=-1` の場合あり）
- `sampling_tts_onstart`, `tts_start`: Android TTSエンジンのonStart、実際の**可聴開始ではない**
- `sampling_stats`, `capture_poll`: 処理中スキップ・frame callbackの過剰通知・fallback
- 各モードの画像処理数、忙しいときの見逃し、音声の重複／キャンセル・3行の順番を**併記**する。

**重要:** 現状のログだけでは、実際の字幕が初めて画面に映った瞬間と、スピーカーから最初の音が出た瞬間は確定しない。TTS onStartは音の発生の代用にしない。実機測定でユーザー自身の観察により字幕イベント・実音声を比較するか、適切な権限の範囲で端末／外部マイク測定を追加し、「観測できなかった場合は未測定」とする。

**導入判定の優先順位:** 新方式が短い字幕・3行の順序・誤読・重複・割込みを悪化させず、実機での字幕出現から音声開始が早まった場合に限り候補とする。OCR中央値だけで採否を決めない。未実測の条件を実測済みと報告しない。mainへマージ・既存APK上書き配布は別判断。

公式API参考:
- https://developer.mozilla.org/en-US/docs/Web/API/HTMLVideoElement/requestVideoFrameCallback
- https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener)
- https://developers.google.com/youtube/terms/developer-policies
