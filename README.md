# 焼き付け字幕リーダー Android MVP

ブラウザで再生中のYouTube画面をMediaProjectionで取得し、指定した字幕領域をML Kit日本語OCRで認識してAndroid標準TTSで読み上げる試作版です。動画や画像は端末外へ送信しません。

## 対応範囲

- Android 8.0以上（Galaxy S25 / Android 16を主対象）
- ブラウザを含む画面上の日本語横書き字幕
- 手動の字幕領域選択
- 約3fpsのOCR、字幕安定化、類似重複除去
- 連続読み上げ / 最新字幕優先
- 読み上げ速度と安定待ち時間の変更

## 使い方

1. アプリで「画面共有を開始」を押し、Androidの確認画面で許可します。
2. 「ブラウザへ移動」を押すか、普段のブラウザに切り替えてYouTube動画を再生します。
3. 通知を開き「字幕領域を選択」を押します。
4. 表示された直前の画面で、字幕の左上から右下へドラッグして保存します。
5. ブラウザへ戻ると、新しく確定した字幕だけを読み上げます。

画面を回転した場合もキャプチャ面をリサイズします。字幕の位置が変わったら通知から領域を選び直してください。

## ビルド

JDK 17、Android SDK 36、Gradle 8.13を使用します。

```sh
gradle :app:assembleDebug
```

生成物は `app/build/outputs/apk/debug/app-debug.apk` です。リポジトリのGitHub Actionsを手動実行してAPK artifactを取得することもできます。

## 制約

- 初回モデルダウンロードを避けるため、ML Kit日本語OCRモデルをAPKへ同梱しています。その分APKサイズは大きくなります。
- DRMや `FLAG_SECURE` で保護された画面は黒くなることがあります。
- YouTubeの再生・停止は操作しません。
- 縦書き、字幕領域の自動検出、翻訳は対象外です。
- OEMの省電力設定によって長時間のForeground Serviceが停止される場合があります。

## ライセンス

- Google ML Kit Text Recognition Japanese: Google Play services terms / ML Kit termsに従います。
- アプリ本体: リポジトリのライセンスに従います。
