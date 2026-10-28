# フレーム連動型の読み取り（標準動作）

2026-10-28: PR #66のGalaxy S25体感検証を受け、本番アプリへ必要部分だけ移植。

## 標準動作

- WebViewの `HTMLVideoElement.requestVideoFrameCallback()` で**実際に提示されたフレーム**の通知を受け、画面の可視領域に `PixelCopy` を実行する。未取得の未来フレーム・動画ファイル・動画ストリーム・非表示プレーヤーにはアクセスしない。
- 通知は約180ms以上の間隔で間引き、既存の `busy` がtrueのとき新規のPixelCopy/OCRは重ねない。OCRエンジンEは不変。
- `androidx.webkit.WebViewCompat.addWebMessageListener` はYouTubeの許可済みHTTPS origin、main frameのみに限定する。本文ではなくフレーム時刻の数値だけを渡す。
- Android WebViewが通知APIに未対応、またはフレーム通知が1000ms来ない場合、従来の**480ms**周期でPixelCopyを実行する。フレーム通知が届けば周期サンプリングを抑制する。再生再開、ページ移動、シーン切替、全画面切替は既存のgeneration/busy制御を使う。
- `SubtitleSpeechOrderBuffer.MAX_WAIT_MS` の標準値を **700ms→300ms** とする。待機するのは上段に未確定の近接字幕がある場合だけ。上段が確定すれば上下順に解放し、期限でも解放する。
- 実験用のモード選択UI、ADB経由のexperiment extras、実験版別パッケージIDは**本番に持ち込まない**。有効な動作は常時フレーム連動＋300ms待機、非対応時は480ms fallback。

## 検証と留保

- PRのJVM unit tests・ビルド、実験PR #66でのGalaxy S25体感比較を区別する。ユーザーは**フレーム連動による読み上げ体験の改善**を報告しているが、字幕出現→可聴音声開始のms値は未計測。
- 3行字幕の上段抜け、読み上げ順逆転、重複、短字幕の見逃し、バッテリー・発熱は継続監視する。
- 診断イベント `sampling_policy`、`sampling_video_frame`、`sampling_captured`、`sampling_order_release` により、フレーム通知の使用状況と順番待ちの時間を確認できる。記録はユーザーがオンにしたときだけ。
