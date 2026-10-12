# Galaxy S25 A/D比較：端末診断・ログ集計手順（実測前）

対象はPR #33のD方式。比較支援用ブランチ `codex/d-galaxy-s25-ad-compare-20261013` では、モデル・字幕選別・TTS動作・既定方式Aを変更せず、**診断記録がONのときだけ**サンプラーの状態を記録する `capture_poll` イベントを追加する。通常利用では追加記録は行わない。mainへはマージしない。

## 1. 使用APKと証拠の固定

- 最小限の目視試験は[PR #33](https://github.com/eltnegcellist/burned-caption-reader-android/pull/33)の既存APKで可能（検出ON・認識ON＝D）。比較のための特別な既定D APKは作らない。
- **busy skip件数まで機械集計する場合**は本ブランチのCIから生成したAPKを使用。これ以前のAPKの診断ZIPでは `capture_poll` がないので件数は `null`（未測定）になる。
- A/Dは必ず**同一APK**で測定。A＝両スイッチOFF／D＝両スイッチON。保存設定と切戻しを確認する。
- Actions run ID、APK SHA-256、アプリversion、署名fingerprint、OSビルド、端末、画質、向き、TTS設定、動画IDと区間を[実機記録](2026-10-13-galaxy-s25-ad-test-record.md)へ記載。APKのdebug署名が既存アプリと異なる場合があるので、設定データを保護せずアンインストールしない。

## 2. A/Dを各2回再生

- 既知動画：`2MEgE9xZsGs`（60～120秒）、`X115n3Sn5pc`（60～120秒）。
- 追加の未使用動画は**結果を見る前**にIDと再生区間を固定。既存99画像で使った `mfsi8GfAvEA` は新規扱いにしない。
- それぞれA→D、D→Aの順に交代し、区間・画質・向き・字幕表示とTTS設定を揃える。
- Androidアプリの「不具合の診断」で記録をONにして実行し、各モード・各回のZIPを別々にエクスポートする。**通常の診断記録は最大120秒・64MiB・2000件**で古い記録が失われる。2～3分の目視評価をすべて数値で残すには、90秒以下の記録区間に分けて書き出すか、事前に別途容量の大きい検証方法を準備する。書き出したら次のrunを新たに記録する。診断記録が負荷に影響しうる点も残す。
- `capture_poll.state=requested` はサンプラーが取得を要求したtick、`busy_skip` は前処理中のため取得しなかったtick。**実際にデコードされた動画フレームの欠落数とは違う**。実際の画像取得は `video_frame`、処理完了は `frame_done` で数える。どちらも記録省略を確認する。

## 3. MacでZIPから自動集計

追加のPythonライブラリは不要。

```sh
python3 tools/device-ad-compare/test_analyze.py
python3 tools/device-ad-compare/analyze.py A-run1.zip --expected-mode hybrid --output A-run1.json
python3 tools/device-ad-compare/analyze.py D-run1.zip --expected-mode v6_both --output D-run1.json
```

- `frame_done.processing_ms` を、全完了フレームの中央値/p95と、**最初の完了フレーム1件を除いた値**で記録する。p95はnearest-rank。最初の完了フレームは参考cold欄であり、必ずしもエンジン初期化直後・lazy recognizer初回を表さない。cold厳密測定には事前の初期化ログ／フレーム対応づけが必要。モード変更直後や異常終了フレームも別に監査する。
- TTSは `tts_request` → `tts_start`（**OSのonStart callback**）間の中央値/p95、完了・stop・error・discard件数を集計する。字幕初出→可聴開始の遅延は**このJSONから計算しない**。画面＋音声を同時録画し、聞こえる音の開始を人が別に採点する。収録不可なら「可聴開始未測定」と書く。
- OCRとTTSの混在を避けるため1ZIP＝1方式・1再生回を推奨。モード混在ZIPのTTS値はモードに帰属できない。
- 集計JSONは**文字列・動画画像・動画IDを含めず、集計値のみ**。元ZIPには実動画の画像や発話文が入るため、公開GitHubへ無断アップロードしない。 `dropped_records_or_images` がゼロでない場合は集計の不完全性を報告する。
- CERや字幕混入はこのスクリプトでは計算しない。新規動画20～30枚の同一画像の正解を、人の監査で先に固定した上で[既存の画像評価系](../tools/image-evaluator/README.md)で別に比較する。

## 4. 判断

[計画](2026-10-13-d-implementation-plan.md)の既定条件を使用。字幕精度、重要文の欠落・混入、busy skip、実際の可聴遅延、seek・pause/resume・切替の安定性を**すべて**確認するまで結論は「判断保留」。CI成功や旧99枚のCERだけでDの既定化・PRのmainマージを行わない。

Galaxy S25上の実測・録画・人による正解監査・採否は**まだ行っていない**。
