# Android MVP 実装状況

## 実装済み

- MediaProjectionの許可取得とForeground Service
- ImageReaderによる約3fpsの画面フレーム取得
- Android 14以降の1セッション1回の `createVirtualDisplay` 制約に対応
- 画面回転・共有対象サイズ変更時のVirtualDisplayリサイズ
- 通知から直前のブラウザ画面を開くROI選択
- バンドル版ML Kit日本語OCR（交換可能な `OcrEngine`）
- OCR中の次フレーム破棄、ROIのみのOCR、入力画像サイズ制限
- 正規化、Levenshtein類似度、prefix成長、安定待ち、重複除去
- 空白期間後に同じ字幕が再登場するケースへの状態リセット
- Android標準TTS（交換可能な `SpeechEngine`）
- 連続読み上げ / 最新字幕優先、読み上げ速度変更
- OCR結果、確定字幕、内部状態のデバッグ表示
- Subtitle StabilizerのJUnitテスト
- GitHub Actionsによるユニットテスト・Debug APK生成

## 検証状況

- 既存PC版のロジックテスト: 10件すべて成功
- Androidの純粋ロジック: PC版テストを移植し、追加ケースを収録
- Android APK: この作業環境にAndroid SDK・Gradle・Javaコンパイラがないため、ローカルビルドは未実行

APKのコンパイル検証はGitHub Actionsの `Build Android APK` を実行して行います。成功後、artifactの `burned-caption-reader-debug-apk` にインストール用APKが入ります。

## 実機で優先して確認すること

1. Galaxy S25で画面共有許可後もForeground Serviceが継続するか
2. Chrome / Samsung InternetのYouTube画面が通知経由のROI選択に正しく残るか
3. 1080p動画でOCR確定から読み上げ開始まで概ね1秒以内か
4. 白文字・黒縁字幕で同じ字幕を繰り返し読まないか
5. 10〜20分再生時の発熱、電池消費、TTS遅延
