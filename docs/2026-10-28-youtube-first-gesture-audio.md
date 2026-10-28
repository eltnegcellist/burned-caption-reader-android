# YouTube公式埋め込みプレーヤー正式採用（v1.0.7）

2026-10-29。利用者のGalaxy S25実機フィードバックに基づき、PR #68を通常版へ統合。

## 背景と採用判断

- 最初の「YouTubeモバイル視聴ページ + JSで最初のタップ時にミュート解除」方式は、ミュート解除に失敗した。また「YouTube の機能を最大限利用しましょう」というアプリ誘導モーダルが焼き込み字幕を隠した。
- 次のYouTube**公式埋め込みプレーヤー**は、再生ボタンを一度押すと音声付きで再生できた。ただし初回ボタンが必要で、おすすめ動画選択後にモバイル視聴ページへ戻る問題があった。
- それらを改善した**自動再生の試行＋おすすめ動画の埋め込み維持**について、利用者から「良いです。これを採用して、今までの表示はやめよう」との実機承認を受けた。

## 通常版での動作

1. URL入力またはYouTubeリンクの共有では、`https://www.youtube.com/embed/VIDEO_ID?playsinline=1&autoplay=1&controls=1` でYouTube公式プレーヤーを表示する。
2. Android WebViewは、実際のAndroidアプリケーションIDを元にした`Referer: https://<application-id>/`を送る。`WebSettings.setMediaPlaybackRequiresUserGesture(false)`により音声付き自動再生を**試みる**が、YouTube/Chromiumによって阻止される場合は公式の再生ボタンを利用する。音声を強制ミュートしてまで自動再生はしない。疑似クリックはしない。
3. おすすめ動画を選択した際、`/watch`、`/shorts`、`/live`、`youtu.be`への遷移を捕捉し、IDを引き継いで公式埋め込みプレーヤーを再度開く。動画変更時にはOCRトラック・読み上げ重複防止情報をリセットする。
4. **従来の`m.youtube.com/watch`表示と切り替えボタンを削除**。WebView主文書で`/watch`などの画面を開かず、公式`/embed/VIDEO_ID`のみ許可する（許可されない動画はそのままエラー/再生不可として扱う）。
5. `requestVideoFrameCallback`→可視画面の`PixelCopy`→E方式OCR→TTS、上段字幕の最大待機300msを維持する。動画ファイル/ストリームを直接取得しない。
6. プロダクションのアプリケーションID`jp.hidemaru.burnedcaptionreader`に復帰。実験用の`.audioresearch`サフィックスとデバッグ専用の別名ラベルは削除した。

## 留保・確認項目

- 音声付きのゼロタップ再生は環境やYouTubeのポリシーで制限されるため、常に保証されない。
- 埋め込み禁止動画・WebView内部のエラー153/150などは、従来視聴ページへの強制切り替えでは回避しない。画面にエラーが出た場合は別の再生可能な動画を利用する。
- おすすめ動画の遷移は通常ページリンクのほか、YouTubeがページ内部で変更する形式にも対処するが、YouTube側の将来の仕様変更では再検証が必要。
- Galaxy S25で確認したのは**利用者による体感・操作の成功**。この版のAndroid CIテストと実機での定量的なレイテンシ計測は別物。

## 参照

- https://developers.google.com/youtube/iframe_api_reference
- https://developers.google.com/youtube/terms/required-minimum-functionality
- https://developer.android.com/reference/android/webkit/WebSettings#setMediaPlaybackRequiresUserGesture(boolean)
