# 公開映画と日本語字幕の確認（2026-10-12）

Elephants DreamとSintelの配布動画・日本語SRTをMacへ取得し、字幕をローカルで焼き付けた代表場面を目視確認した。2作品とも比較入力の候補に使える。これは**公開CG映画＋こちらで追加した日本語字幕**であり、元から日本語字幕が焼き付いた動画でも、実写YouTubeテロップでもない。

|作品|動画取得|日本語SRTイベント|明示的な複数行|最初5分のイベント|今回の確認|
|---|---|---:|---:|---:|---|
|Elephants Dream|原本840,078,359 bytes、1920×1080、約10分58秒|77|17|33|代表場面・2行字幕・複雑な線模様の背景を確認。最初の調整素材候補|
|Sintel|原本237,796,024 bytes、2047×872、約14分48秒|26|0|17|暗い背景と字幕なし場面、長い1行字幕の代表場面を確認。背景誤検出の試験候補|
|Cosmos Laundromat|未取得：原本と公式480p派生版がHTTP429|60|4|未集計|日本語SRT・権利表記は確認。動画場面は未確認|

Elephants Dream最初5分の字幕表示はSRT上74.462秒、Sintelは51.318秒。字幕なしが多いため、字幕取得率だけでなく空白時間への誤出力も必ず計測する。全尺を視聴したと主張せず、今回の目視は選んだ代表フレームに限る。

## 出典と利用条件の保存

- Elephants Dream映像：Orange Open Movie Team / Blender Foundation / Netherlands Media Art Institute、CC BY 2.5。
  [配布ページ](https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm)、[映像ライセンス](https://creativecommons.org/licenses/by/2.5/)、[日本語字幕](https://commons.wikimedia.org/wiki/TimedText:Elephants_Dream_(2006).webm.ja.srt)、字幕revision `1052627867`。
- Sintel映像：Durian Open Movie project / Blender Foundation、CC BY 3.0。
  [配布ページ](https://commons.wikimedia.org/wiki/File:Sintel_movie_-_Blender_Fondation.ogv)、[映像ライセンス](https://creativecommons.org/licenses/by/3.0/)、[日本語字幕](https://commons.wikimedia.org/wiki/TimedText:Sintel_movie_-_Blender_Fondation.ogv.ja.srt)、字幕revision `937798514`。
- Cosmos LaundromatのCommons配布映像：Blender Foundation、CC BY-SA 3.0。
  [配布ページ](https://commons.wikimedia.org/wiki/File:Cosmos_Laundromat_-_First_Cycle_-_Official_Blender_Foundation_release.webm)、[映像ライセンス](https://creativecommons.org/licenses/by-sa/3.0/)、[日本語字幕](https://commons.wikimedia.org/wiki/TimedText:Cosmos_Laundromat_-_First_Cycle_-_Official_Blender_Foundation_release.webm.ja.srt)、字幕revision `994690676`。

字幕はWikimedia Commons投稿者の別コンテンツであり、映像のCC BY条件をそのまま字幕に適用しない。Commons TimedTextページの本文ライセンス・投稿履歴も出典として保持する。公開した成果物はこの確認記録・hash等に限り、字幕全文・動画・加工画像はGitHubへ掲載していない。

ダウンロードはCommonsが提供するファイルURLを使用。YouTubeからの非公式ダウンロードは行っていない。配布ページの元出典がYouTubeと書かれていても、今回アクセスしたのは再利用条件を表示したCommons配布ファイルである。

## 字幕描画の確認

使用したffmpegはSubtitle Edit 5.2.0公式Macアプリ同梱版。検証用代表画像は幅960へ縮小し、`subtitles`フィルタ、VSE同梱Noto Sans CJK、FontSize=24 / Outline=2 / MarginV=20で描画。元SRTの時刻を維持して、画像中の文字が一致するか目視した。

- Elephants Dream 345秒：「なぜ？」と「―早く！」の2行が確認できる。
- Elephants Dream 115秒：字幕なし。背景に細かな多数の線・高輝度部分があり、背景除外の評価に使える。
- Sintel 112秒：「多くの罪なき人の血で汚れている」を確認。
- Sintel 118.5秒：「そんな頼りないのに 一人旅とは無茶なことを」を確認。背景は暗いが文字は白と黒縁で表示される。

2作品の30秒MP4プレビューもMacの`private/open-movies/`へ保存。Elephants Dreamは335～365秒、Sintelは105～135秒。タイムラインを0開始へ変えるため、本比較で使う場合は参照字幕も同じだけ時刻を引く必要がある。

取得したElephants Dream SRTは既存採点器で正常に77イベントへ解析できた。予防的な区切り補正も試したが元データとの差は0で、文字や時刻を変更していない。全画像の独立正解確認、5分MP4の4方式完走、性能採点はこの素材確認段階では実行していない。

## 次の比較の扱い

最初はElephants Dreamの5分区間を調整に使用し、Sintelはその後の固定設定検証に分離する。ただし同じBlender系CG作品であり、異なる実写投稿者の一般化検証を代替しない。Cosmosは取得成功後に役割を固定する。

三行、小字幕、暗い文字、固定タイトル、書影混入をこの配布字幕だけで満たしたとは言えない。追加スタイルを作る場合は別の管理条件として保存し、既存の焼き付け字幕と混ぜない。ダウンロードSRTも、描画された文字・折り返し・時間を確認してから正解として凍結する。

元ファイル・字幕・プレビュー・画像を削除していない。元ファイルSHA-256、字幕revision/hash、ライセンスAPI応答は同じoutputsディレクトリに保存した。
