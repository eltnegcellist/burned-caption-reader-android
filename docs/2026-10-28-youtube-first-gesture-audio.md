# YouTube公式埋め込みプレーヤー実験：音声とアプリ誘導画面

日付: 2026-10-28。**Galaxy S25では実機未検証。PR #68はDraftのまま。**

## ユーザーの実機報告

前の試験版（WebViewに`m.youtube.com/watch`を開き、最初のタップで`video.muted=false`を設定する方式）では音声のミュート解除に失敗。さらにYouTube側の「YouTube の機能を最大限利用しましょう」「YouTube アプリで視聴」というアプリ誘導モーダルが表示され、焼き込み字幕を覆った。**前のJavaScript音声補助方式は撤回**し、テスト用ブランチから除去した。

## 今回の実験実装

- 通常のYouTube**モバイル視聴ページ全体**を表示せず、公式プレーヤーのURL `https://www.youtube.com/embed/VIDEO_ID?playsinline=1&autoplay=0&controls=1` をWebViewのメイン画面に表示する。
- YouTubeの公開仕様に従い、Android WebViewの`loadUrl(url, headers)`で`Referer: https://<Android application id>/`を送る。省略すると Error 153 が表示される場合がある。参照元IDの偽装はしない。
- autoplayをせず、**ユーザーがYouTubeの通常の再生ボタンを一回タップする**。音声を強制的にONにするための疑似クリックや`video.muted`の手動上書きはしない。YouTube標準コントロールは表示したまま。
- この埋め込みプレーヤー方式で、視聴ページ固有の「YouTubeアプリで視聴」誘導画面を回避できるか実機で観察する。YouTubeの仕様変更や動画ごとの埋め込み不可により、**必ず表示されないとは保証しない**。
- 埋め込み再生できない場合に備え、アプリ下部の「再生できない場合：従来のYouTube表示に切り替える」ボタンで元の`m.youtube.com/watch`に戻せる。再度埋め込みへ戻すことも可能。
- WebView内に現在表示中のHTML video要素がある場合、`requestVideoFrameCallback`→`PixelCopy`→既存E方式OCR→TTSのパイプラインを維持する。YouTube動画ファイル・ストリーム・字幕トラックにはアクセスしない。
- debug APKのIDは`jp.hidemaru.burnedcaptionreader.audioresearch`のままで通常版と併存。PR #67でmainにマージしたフレーム連動と300ms待機は変更しない。

## Galaxy S25での試験項目

1. 新しい埋め込み試験APKをインストールして、**前の試験版と同じ公開YouTube動画**をURLから開く。
2. 初期画面がYouTubeの通常の再生ボタン付きプレーヤーになり、**アプリ誘導モーダルが表示されないか**を確認する。
3. 再生ボタンを一回タップする。**追加でミュート解除を要求されず、動画音が出るか**と、読み上げ音声が適切に聞こえるかを確認する。
4. 数分再生して字幕OCR/TTSが引き続き動くか、3行字幕の読み順が崩れないかを確認する。フレームイベントが届かない場合は480ms fallbackが働く。
5. **再生エラー（153, 150等）、黒画面、音が出ない、ショート・ライブなど**があれば、下の「従来のYouTube表示」ボタンに切り替えて再生できるか確認する。元のWebページにはアプリ誘導モーダルが出る可能性が残る。
6. ユーザーが明示的に音声をミュートした後、勝手に解除されないかも確認する。

## 公開仕様

- [YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference)
- [YouTube 必要な最低限の機能：WebView Referer](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [Android WebSettings user gesture](https://developer.android.com/reference/android/webkit/WebSettings#setMediaPlaybackRequiresUserGesture(boolean))

## 採否

**まだ本番mainへマージしない。** YouTube側表示の解消・音声付き再生・字幕OCRの動作がGalaxy S25で確認できた場合にのみ統合を検討する。埋め込みが拒否された動画を強制的に再生したり、YouTubeの表示要件を迂回したりしない。
