# YouTube再生開始のミュート解除補助（実機検証前）

## 解決したい問題
Galaxy S25でアプリ内YouTube動画を再生したとき、左上に「ミュートを解除する」操作が現れて字幕が隠れる。動画をタップして再生した後、音声のために追加でもう一回タップする必要をなくしたい。

## 試験実装
- WebView内のYouTube視聴ページに、実際のユーザー操作（`event.isTrusted`）の `pointerup` / `click` を**capture**段階で受ける小さなJavaScriptを挿入する。
- タップ位置が現在の動画矩形に入っていた場合だけ、既存の動画再生操作と同じタップを利用して `video.muted=false`、`video.defaultMuted=false`、必要時 `video.volume=1` を試行。再生イベント直後も短時間・最大3回だけ再試行する。
- 他の場所へのタップでは動作しない。YouTubeのボタンを `click()` で疑似タップしたり、合成イベントでユーザー操作を偽装したり、YouTube標準音量UI・字幕などをCSSで非表示にすることはしない。
- 一度正常に解除された動画について、ユーザーのその後のミュート操作を上書きしない。
- WebViewの「メディア再生にはユーザー操作が必要」は**trueのまま**で、初回の再生を無断で始めたり、非表示で自動再生したりしない。
- 通常版と別パッケージID `jp.hidemaru.burnedcaptionreader.audioresearch` のdebug APKで試験する。フレーム連動および上段待機300msは統合済みのmainから継承。

## 限界
**自動クリックだけでミュートを解除する保証はない。** Android WebView/ChromiumやYouTubeが、音声付き再生の許可を実際のユーザー操作に限定する場合がある。JSの自動 `click()` はその操作として認められない。この試験では「従来の最初の再生タップと同じ操作で音声を有効にする」ことを目標にする。YouTubeページがネイティブ動画と分離したmute状態を保持する場合、左上のUIが残る可能性がある。実機未検証のため「表示が消える」とは報告しない。

## Galaxy S25検証

1. テストAPKを通常版と**別アプリ**としてインストールする。普段使うメインアプリはアンインストールしない。
2. 同じ公開YouTube動画（1本は長尺、可能ならもう1本は短い動画）を通常版・試験版でそれぞれ開き、最初の画面の「タップしてミュート解除」表示の有無、動画の再生可否、動画音が実際に鳴るかを記録。
3. 動画の中央や標準再生ボタンを1回だけタップして、**追加の左上タップが不要か**確認。前の動画→別動画→戻る、全画面、縦横切替、再生一時停止／再開、YouTube広告のケースも試す。
4. 動画音をユーザーが明示的にミュートしたとき、勝手に戻らないことを確認。TTSの字幕読み上げが止まらず、フレーム連動・300ms待機にも劣化がないか確認。
5. デバッグ診断をオンにしたときの `youtube_audio_start_assist` イベントは「JSの注入が成功したか」だけを記録し、**実際にミュート解除された証明ではない**。

成功条件: **初回再生タップのみ**でYouTube音声が出て、追加のミュート解除タップが不要。失敗時やミュートが解除できない環境では既存YouTube操作を残し、再生や字幕認識を壊さないこと。

参考：<https://developer.chrome.com/blog/autoplay/>、<https://developer.android.com/reference/android/webkit/WebSettings#setMediaPlaybackRequiresUserGesture(boolean)>、<https://developers.google.com/youtube/terms/developer-policies-guide>。
