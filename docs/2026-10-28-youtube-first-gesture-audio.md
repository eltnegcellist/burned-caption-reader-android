# YouTube埋め込みプレーヤー継続・音声付き自動再生実験

日付: 2026-10-28。**Galaxy S25での最終検証前。PR #68はDraft。**

## Galaxy S25からのフィードバック

1. `m.youtube.com/watch`上でJavaScriptから`video.muted=false`にした最初の試験は**失敗**。アプリ誘導モーダル「YouTube の機能を最大限利用しましょう」も字幕を隠した。
2. 次の公式埋め込み試験版は、**▶を一度タップすればミュート解除なしで音声付き再生できた**。ただし初期読み込み時に▶が必要で、YouTubeのおすすめ動画を選択すると`/watch`画面へ遷移し、再びミュート解除が必要になった。

## 今回の修正

- `https://www.youtube.com/embed/VIDEO_ID?playsinline=1&autoplay=1&controls=1` で**音声付き自動再生を試みる**。`WebSettings.setMediaPlaybackRequiresUserGesture(false)`を**検証用APKのみ**で指定する。`mute=1`は指定せず、音声をオフにして「見かけ上の自動再生」を成立させることはしない。ブラウザの自動再生ポリシーは優先されるため、音声付き自動再生は失敗する場合がある。この場合は**YouTubeの通常の▶を一度タップ**する。
- YouTubeのおすすめ動画が`/watch?v=...`、`/shorts/...`、`/live/...`または`youtu.be/...`へ遷移したとき、`WebViewClient.shouldOverrideUrlLoading`、`onPageStarted/onPageFinished`、通常フレーム処理でのURL検査により新しいIDを認識し、**公式埋め込みURLへ戻す**。これによりモバイル視聴ページの「アプリで視聴」案内やミュート表示へ遷移しないように試す。
- 旧`/embed/` URL、チャンネル画面、アカウント/利用規約などの非動画URLはリダイレクト対象外にして、無限リダイレクトを防ぐ。選択動画が変わると、OCRトラック、TTS予約/行重複防止記録は動画ごとにリセットする。
- 埋め込みエラー153/150、YouTubeが許可しない動画、動かないWebView向けに、**「従来のYouTube表示に切り替える」**ボタンを維持する。その表示に戻した場合はYouTube独自のミュート操作が発生し得る。
- Android WebViewから公式プレーヤーへ移る時はアプリ固有のReferer `https://<実際のapplicationId>/`を送る。信頼された操作の偽装、YouTube標準UIの削除、動画ファイルへのアクセスは行わない。
- `requestVideoFrameCallback`→画面PixelCopy→E方式OCR→TTS、および上段待ち300msはmainの実装を維持。debug APKのみ別ID `jp.hidemaru.burnedcaptionreader.audioresearch`、**mainは変更しない**。

## Galaxy S25実機チェック

1. 同じ公開YouTube動画のURLを開き、**▶を押さなくても音声付きで再生が始まるか**、それとも初期の▶が残るかを確認する。自動再生の制約により残る可能性がある。
2. 再生された（または▶をタップした）あと、**動画音と読み上げ音**が出ているかを確認。
3. YouTube埋め込みプレーヤーのおすすめ動画をタップして、**次の動画も埋め込み画面のままで、ミュート解除の画面に戻らないか**確認。おすすめタップ後に▶が必要かも見る。
4. 全画面、動画終了後のおすすめ、動画間の戻る、縦動画、広告、字幕OCR精度・行順と負荷を観察する。
5. 埋め込み禁止や動画再生失敗では、下のボタンで従来表示に戻して再生できるか確認する。
6. 読み上げの下に「動画が画面外です」が残る場合、動画再生中か／字幕を読んでいるかを併記する。これはDOM動画矩形認識の別問題で、今回のURL移動改善だけで解消を保証しない。

## 採否

**実機で自動再生、音声とおすすめ遷移、字幕OCRの挙動を確認するまでmainへマージしない。** 公式APIの`autoplay=1`が許可されないことは正常なブラウザの動作であり、無断ミュート、自動クリック、第三者の制限回避で押し通さない。

- [YouTube埋め込みプレーヤー パラメータ](https://developers.google.com/youtube/player_parameters)
- [YouTube IFrame Player API / onAutoplayBlocked](https://developers.google.com/youtube/iframe_api_reference)
- [WebViewのメディア再生設定](https://developer.android.com/reference/android/webkit/WebSettings#setMediaPlaybackRequiresUserGesture(boolean))
