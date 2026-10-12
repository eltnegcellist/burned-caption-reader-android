"""Generate local and sanitized numeric experiment report from measured outputs."""
import argparse, hashlib, json, platform, subprocess, statistics, sys
from pathlib import Path
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
from evaluate import normalize, distance, quant, sha

def main():
    p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);a=p.parse_args();w=a.workspace.resolve()
    out=w/'outputs/ocr-p0-p5-experiment-20261013';s=json.loads((out/'summary.json').read_text());c=json.loads((out/'controls.json').read_text())
    inventory=json.loads((out/'inventory.json').read_text());models=[]
    for path in sorted((w/'repo/app/src/main/assets').rglob('*')):
        if path.is_file() and path.suffix in ['.onnx','.json']:
            models.append({'path':str(path.relative_to(w/'repo')),'bytes':path.stat().st_size,'sha256':sha(path)})
    provenance=json.loads((w/'repo/app/src/main/assets/ppocrv6/model-provenance.json').read_text())
    for name,value in provenance.items():assert sha(w/'repo/app/src/main/assets/ppocrv6'/name)==value['sha256']
    roots=[ET.parse(p).getroot() for p in (w/'repo/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    android={};pairs=[]
    for label in ['coarse-A','coarse-D','p3-oracle-4']:
        d=json.loads((out/'private'/f'{label}.json').read_text());fs=d['frames'];metrics={}
        for kind in ['NFKC','NFC']:
            char=edit=pos=exact=blankfp=blanks=multi=multiexact=0
            for f in fs:
                pred=f.get('mapped_text',f['ocr_text']);gold=normalize(f['expected_text'],kind,kind=='NFC');predkey=normalize(pred,kind,kind=='NFC')
                char+=len(gold);e=distance(gold,predkey);edit+=e
                if gold:pos+=e;exact+=predkey==gold
                else:blanks+=1;blankfp+=bool(predkey)
                if '\n' in f['expected_text']:multi+=1;multiexact+=normalize(f['expected_text'],kind,True)==normalize(pred,kind,True)
            metrics[kind]={'reference_chars':char,'edits_including_blank':edit,'cer_including_blank':edit/char,'positive_only_cer':pos/char,'positive_exact':exact,
              'blank_frames':blanks,'blank_false_output':blankfp,'multiline_frames':multi,'multiline_structure_exact':multiexact}
        android[label]={'scope':d['scope'],'frames':len(fs),'metrics':metrics,'all_time_ms':quant([f['ocr_ms'] for f in fs]),
          'warm_after_first_ms':quant([f['ocr_ms'] for f in fs[1:]]),'first_ocr_ms':fs[0]['ocr_ms'],
          'sampled_peak_pss_mib':d['sampled_peak_pss_kb']/1024,'device_model':d['device_model'],'device_fingerprint':d['device_fingerprint'],
          'apk_sha256':d['apk_sha256'],'test_apk_sha256':d['test_apk_sha256'],'input_manifest_sha256':d['input_manifest_sha256']}
        if 'cumulative_engine_metrics' in fs[-1]:
            android[label]['engine_metrics']=fs[-1]['cumulative_engine_metrics']
        for f in fs:
            pairs.append({'run':label,'id':f['id'],'mono_ms':f['mono_ms'],'sha256':f['sha256'],'gold_present':bool(f['expected_text']),
              'raw_exact_nfkc':normalize(f['expected_text'])==normalize(f['ocr_text']),
              'mapped_exact_nfkc':normalize(f['expected_text'])==normalize(f.get('mapped_text',f['ocr_text'])),
              'raw_rows':len(f['rows']),'mapped_rows':len(f.get('mapped_rows',f['rows'])),'ocr_ms':f['ocr_ms']})
    metadata={'created_utc':datetime.now(timezone.utc).isoformat(),'mac_os':platform.mac_ver()[0],'cpu':'Apple M4','memory_gib':16,
      'python':sys.version,'main_commit':'2c4d3db86fdacae051480bd8c91c83f560200627','pr49_commit':'7d67fe28cd1f316813f751577c260756220d3e46',
      'branch':'codex/ocr-p0-p5-mac-evaluation-20261013','physical_device':'none authorized in adb before emulator launch',
      'junit_tests':sum(int(r.attrib['tests']) for r in roots),'junit_failures':sum(int(r.attrib['failures']) for r in roots),
      'models':models,'font_sha256':sha(Path('/System/Library/Fonts/Supplemental/Arial.ttf')),
      'apk_sha256':sha(w/'repo/app/build/outputs/apk/debug/app-debug.apk'),'test_apk_sha256':sha(w/'repo/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
    for name,data in [('android-metrics.json',android),('android-pairs.json',pairs),('environment.json',metadata)]:
        (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    pct=lambda v:f'{100*v:.2f}%'
    fmt=lambda v:f'{v:.3f}'
    lines=['# OCR改善 P0〜P5 実験報告（2026-10-13）','',
      'P0〜P4のMac検証と、独立Androidエミュレーターの実モデル推論を実行した。P1は数字遷移の合成観測を0/8→7/8へ改善したが、自然背景の既知9イベントは0/9復旧。P2はAndroid観測で字幕本文の精度改善を確認できなかった。P3の手動ROI実推論は既知4枚の2枚を正解復旧したが、実アプリの条件付き復旧率ではない。P4には独立真値の合成保護ケースで誤skipが残る。Galaxy S25は未接続でP5実機評価は未実施。mainのマージ、既定変更、リリース公開は行っていない。','',
      '## 1. 環境・固定状態','',
      '|項目|実際に確認した値|','|---|---|',
      f'|Mac|macOS {metadata["mac_os"]}、Apple M4、16 GiB|',
      '|main|`2c4d3db86fdacae051480bd8c91c83f560200627`。未設定時はD|',
      '|PR #49|`7d67fe28cd1f316813f751577c260756220d3e46`。GitHubでOPEN/Draft、CI SUCCESSを読み取り確認|',
      '|検証用branch|`codex/ocr-p0-p5-mac-evaluation-20261013`。独立cloneとmainのdetached worktree|',
      '|既存比較ソース|`5764edaa3e2bc019e52b628c51c5dbf5a7f9101f`。既存作業木を変更せず参照|',
      f'|実行環境|Python {platform.python_version()}、既存Pillow 10.4.0 venv、JDK 17.0.20.1、Gradle 8.13、SDK 36/ARM64|',
      '|Android|コピーした専用AVD、`emulator-5562`。Galaxy S25は認可済みADB端末一覧に存在しない|',
      '|TTS|新しいMac/エミュレーター比較では発話を実行していない。音声開始、実聴、重複発話、待ち時間は未測定|',
      f'|アプリAPK SHA-256|`{metadata["apk_sha256"]}`|',
      f'|最終テストAPK SHA-256|`{metadata["test_apk_sha256"]}`|','',
      'A=ML Kit検出＋PP-OCRv5認識、B=ML Kit検出＋v6認識、C=v6検出＋v5認識、D=v6検出＋v6認識。モデルは交換せず既存ファイルをコピーし、v6の4ファイルはpinされたhashと照合した。B/Cの新しい実推論は行っていない。今回のA粗認識段階はML Kitのみで、v5認識は候補の再認識段階にある。したがって下のA/D粗認識表はアプリ全体のA/D比較ではない。','',
      '## 2. 入力データと評価の定義','',
      '|データ|照合・実行した件数|','|---|---:|',
      '|Elephants/Sintel、日本語字幕を追加したCG映像|各300秒、33/17イベント|',
      '|連続PixelCopy PNG・保存済みOCR候補|各624、計1248。全PNG hash照合・画像decode|',
      '|固定PixelCopy画像|43＋27＝70。字幕50、字幕なし20。全hash照合、全70枚を新規Android推論|',
      '|既知raw正解→selected空|Elephants 1,2,15,16,17／Sintel 6,10,15,17。1始まりID、全9件を再現|',
      '|検出漏れ4枚|Elephants手動ROIの4枚。原画像を今回目視確認・v6限定ROI推論|',
      '|追加保護入力|12個の独立した合成画素遷移と21観測の価格リプレイ|','',
      '「4枚」は元報告のElephants手動ROI評価の数字。Sintel手動ROIにも1枚あり、2作品計では5枚。全画面評価の空出力はElephants 2枚＋Sintel 1枚＝3枚。これらを自然動画9イベントと混同しない。','',
      '参照は独立SRTと既存の画像目視転記。OCR出力から正解を作っていない。人間による二重監査は未実施で、`human_verified=false`。追加字幕の描画境界は約41.7msの量子化不確かさがある。既存2作品は既に結果を見た開発・回帰セットであり、新しい未使用検証ではない。実写・書影・上部3行の自然動画を網羅した成績ではない。','',
      '主指標はNFKC文字CER（空白/改行を除外、句読点は保持）とNFC原文CER（空白/改行/文字幅を保持）。字幕なし場面の挿入を含む値と字幕ありだけの値を分ける。過去の「9件」の照合だけは過去採点器と同じ句読点除去を使う。候補CER、粗認識CER、発話・SRT製品成績を合算しない。','',
      '## 3. P0：棄却原因と診断不変性','',
      '保存済みログに当時のselection_traceはなく、当時の理由は未観測。現在の同一候補列・同一時刻列をPR #49の実Javaクラスへ渡し、traceあり/なしの候補文字列・track ID・枠・locked状態が1248/1248フレーム一致した。追加OCRは0。候補列はstrip復旧後の保存済みrecognition_rowsも維持した。診断有効時の実音声・captureスケジューリング不変性は未測定。','',
      '|作品/イベント|raw正解を含む観測|現在の再現棄却理由|main正解候補→PR49正解候補|','|---|---:|---|---:|']
    for film,d in s.items():
        for e in d['events']:
            if e['known_loss']:
                reason='候補形状条件不成立（strong/upperともfalse）' if e['predicate_evidence'].get('caption_shape_failed') else 'candidate/length_or_height（1文字相当の短文）'
                lines.append(f'|{film}/{e["event_id_1based"]}|{e["raw_contains_correct_frames"]}|{reason}|{e["main_exact_candidate_frames"]}→{e["pr49_exact_candidate_frames"]}|')
    lines+=['','8イベントは候補形状条件、Sintel 6は最低文字数等の候補生成条件で除外された。P1が修正する「似た文の数字・否定変化によるlane遷移」とは原因が異なる。traceの`temporal_or_caption_shape_gate`を時間条件と決めつけず、既存private predicateの非変更・読み取り評価で形状不成立を確認した。全しきい値緩和は実施していない。','',
      '## 4. P1：同一観測・単独対照','',
      '|作品|候補CER NFKC main→PR49|候補CER NFC原文 main→PR49|完全一致候補イベント NFKC|追加OCR|','|---|---:|---:|---:|---:|']
    for film,d in s.items():
        m=d['main']['selection_metrics'];q=d['pr49']['selection_metrics']
        lines.append(f'|{film}|{pct(m["NFKC"]["cer_including_blank"])}→{pct(q["NFKC"]["cer_including_blank"])}|{pct(m["NFC"]["cer_including_blank"])}→{pct(q["NFC"]["cer_including_blank"])}|{m["NFKC"]["exact_event_count"]}→{q["NFKC"]["exact_event_count"]}/{len(d["events"])}|0|')
    lines+=['','自然動画の既知9件は0/9復旧、目標6/9未達。候補文字列はmainと全1248フレーム一致し、既存正解候補の増減も0。これはcoarse候補の評価であり、再crop認識後の最終selectedや実発話の新しい成績ではない。','',
      '保存済み価格観測21件では100円が5/8→5/8、blankの選択0/5→0/5、200円が0/8→7/8。初回200円を保留し、2観測目以降を選択。固定見出しの新しい選択0件。数字・否定5テスト、記録価格2テスト、初回確定4テストが通過した。初回確定/再確認ONの自然映像・音声の対照は未測定。','',
      '|Mac JVM候補選択時間（各条件3回、全624観測をprewarm後）|main p95範囲|PR49 p95範囲|PR49中央値範囲|','|---|---:|---:|---:|']
    for film,d in s.items():
        old=d['main']['selection_time_ms_runs'];new=d['pr49']['selection_time_ms_runs'];span=lambda key,values:f'{fmt(min(v[key] for v in values))}〜{fmt(max(v[key] for v in values))} ms'
        lines.append(f'|{film}|{span("p95",old)}|{span("p95",new)}|{span("median",new)}|')
    lines+=['','デスクトップの追加処理は小さいが、Galaxyの2ms/10MiB予算を達成した証拠ではない。Javaのimage decode・trace・CSV出力は選択タイマーから除外。PSS/常駐増分は未測定。','',
      '## 5. P2：同一Android OCR枠のshadow','',
      '|作品|配置変更観測|NFKC CER 現行→compose|NFC原文CER 現行→compose|2行の原文構造一致|','|---|---:|---:|---:|---:|']
    for film,d in s.items():
        x=d['p2'];b=x['baseline_layout'];z=x['composed_layout']
        lines.append(f'|{film}|{x["changed_frames"]}/624|{pct(b["NFKC"]["cer_including_blank"])}→{pct(z["NFKC"]["cer_including_blank"])}|{pct(b["NFC"]["cer_including_blank"])}→{pct(z["NFC"]["cer_including_blank"])}|{b["NFC"]["multiline_structure_exact_frames"]}→{z["NFC"]["multiline_structure_exact_frames"]}/{b["NFC"]["multiline_frames"]}|')
    lines+=['','追加OCRは0。Sintelの変更1観測は字幕なし場面の行配置で、NFKC CER・正しい字幕取得を改善しない。NFC原文CERはblank場面の改行等が2文字分減り、95.82%→95.66%へ小さく改善した。字幕ありのNFC CERには変化がない。新しい70枚のA粗認識/D PP観測も配置変更0/70ずつ。別blockを統合しない制約が効き、現在の断片には利得がない。元のVision CER改善をAndroidの改善へ換算していない。3行・段組みの自然例は未測定（単体テスト4件は通過）。','',
      '|Mac compose追加処理、3回のp95範囲|値|','|---|---:|']
    for film,d in s.items():
        v=[r['p95'] for r in d['pr49']['compose_time_ms_runs']];lines.append(f'|{film}|{fmt(min(v))}〜{fmt(max(v))} ms|')
    lines+=['','## 6. P3：発動条件と限定ROI実推論','',
      '|保存A観測の条件リプレイ|raw空・字幕あり|raw空・字幕なし|仮発動/字幕あり/字幕なし|','|---|---:|---:|---:|']
    for film,d in s.items():
        q=d['p3'];lines.append(f'|{film}|{q["raw_empty_positive_frames"]}|{q["raw_empty_blank_frames"]}|{q["shadow_rescue_candidates"]}/{q["candidates_on_positive_frames"]}/{q["candidates_on_blank_frames"]}|')
    oracle=android['p3-oracle-4'];em=oracle['engine_metrics']
    lines+=['','合計12仮発動中、字幕あり3、字幕なし9。明暗contrastは文字存在の証明ではない。今回リプレイでは追加OCRを呼ばず、保存Aのtrusted lane/strip有無を使う。D/Cの同じ時系列・Activity上のP3発動率とは呼ばない。字幕初登場時やraw空の後でtrustが失われた場合、P3は発動できない。','',
      '別実験として、Elephants手動ROIの既知4枚を固定領域でDの`recognizeLimited`→`LockedRegionRescue.merge`へ入力した。画像・正解は同一。各枚は検出1回＋認識1回、計4＋4回で上限内。手動の既知領域を与えた到達可能性試験であり、本番のraw空/前回帯/strip不発/cooldownをすべて満たした復旧率ではない。','',
      '|画像ID|限定ROIのraw|merge後|原因|','|---|---|---|---|',
      '|elephants-pixelcopy-16685|正解|正解|復旧可|',
      '|elephants-pixelcopy-21005|正解|正解|復旧可|',
      '|elephants-pixelcopy-64205|正解|空|短文の横幅が`.18`未満、mergeで除外|',
      '|elephants-pixelcopy-132366|誤字あり|誤字あり|認識の濁点誤り、領域だけでは解決せず|','',
      f'正解復旧2/4。merge後NFKC CER {pct(oracle["metrics"]["NFKC"]["cer_including_blank"])}, NFC原文CER {pct(oracle["metrics"]["NFC"]["cer_including_blank"])}。初回{fmt(oracle["first_ocr_ms"])}ms、残り3回中央値{fmt(oracle["warm_after_first_ms"]["median"])}ms/p95 {fmt(oracle["warm_after_first_ms"]["p95"])}ms。初期化はdetector {fmt(em["detector_init_ns"]/1e6)}ms、recognizer {fmt(em["recognizer_init_ns"]/1e6)}ms。サンプル最大PSS {oracle["sampled_peak_pss_mib"]:.1f}MiBは別プロセスの総値であり、P3の追加PSSではない。','',
      '5分総OCR時間+10%、全体p95+20%、追加PSS+50MiB、busy/capture欠落、Bitmap解放と世代変更の実ライブ回帰は未測定。merge座標・上限の既存4単体テストは通過。P3 ONの本採用条件は未達。','',
      '## 7. P4：独立真値による誤skip検証','',
      '|実フレームのshadow|仮skip|独立SRTの誤skip|比較p95（Mac）|','|---|---:|---:|---:|']
    for film,d in s.items():
        q=d['p4'];lines.append(f'|{film}|{q["shadow_skip_candidates"]}/624|{q["independent_gold_false_skips"]}|{fmt(q["comparison_time_ms"]["p95"])} ms|')
    lines+=['','背景変化・trusted lane条件により実フレームの仮skipは0/1248。したがってこのセットで速度改善の証拠も0。実OCR削減は未導入のため測定不可で、0件の実省略を20%達成と扱わない。判定はselectedの変化ではなく、独立SRTの最後の非skip観測との変化を使用。','',
      '|合成保護ケース|仮skip|独立真値による誤skip|','|---|---:|---:|']
    for r in c['shadow']:lines.append(f'|{r["id"]}|{int(r["would_skip"])}|{int(r["false_skip"])}|')
    lines+=['',f'12制御中、誤skip {c["false_skips"]}件。1桁の小さな字形、短い変化、サンプル間の等輝度色変化、暗い否定表示が反例。既存の「サンプルした1セルの色変化」テストは通る一方、16×9/48×12の点の間にある画素は最大RGB差でも見えない。常駐fingerprint配列のpayloadは720×4＝2880 bytes（オブジェクト等の実heap/PSSを除く）。サイズが小さくても安全性の証明にはならない。実スキップを導入していない。','',
      '## 8. 新しいAndroid粗認識の補助測定','',
      '|同一70画像、粗認識のみ|NFKC CER（blank挿入含む）|NFC原文CER|正解字幕画像/50|blank誤出力/20|中央値/p95、初回除外|サンプル最大PSS|','|---|---:|---:|---:|---:|---:|---:|']
    for label in ['coarse-A','coarse-D']:
        q=android[label];m=q['metrics'];v=q['warm_after_first_ms']
        lines.append(f'|{label}|{pct(m["NFKC"]["cer_including_blank"])}|{pct(m["NFC"]["cer_including_blank"])}|{m["NFKC"]["positive_exact"]}|{m["NFKC"]["blank_false_output"]}|{fmt(v["median"])} / {fmt(v["p95"])} ms|{q["sampled_peak_pss_mib"]:.1f} MiB|')
    lines+=['','各条件1回の画像batch。A粗認識はML Kit検出の出力、v5再認識なし。Dはv6検出＋認識。ROI精度、候補選択、refine、安定化、TTSを含まない。初回1件除外は厳密なcold-load分離ではない。エミュレーター速度・PSSをGalaxyの数値に置き換えない。A/Dの純粋な認識モデル比較や3回の実機試験を完了したとは扱わない。','',
      '## 9. P5と採否','',
      '|改善|判定（提案）|根拠と必要な残作業|','|---|---|---|',
      '|P1|限定採用|数字・否定の保護用途に限定。価格0/8→7/8、自然9件は0/9。一般欠落の修正としては保留。短い字幕のshape/1文字条件を別修正として検証|',
      '|P2|保留|Android字幕本文のCER/行構造の改善0、blankの改行のみNFC挿入誤差2文字減。shadow保持。same block制限を緩める前に実際の断片・段組み負例を集める|',
      '|P3|保留|手動ROIで2/4回復、短文1件再棄却、12仮発動中blank9。C/Dの条件付きライブ対照と負荷予算が未測定|',
      '|P4|見送り|実スキップは保護入力で誤skip4件、自然セット仮削減0%。領域内の面積/エッジ差などサンプル間の変化を検出する別案が必要。shadow記録のみ維持|',
      '|P5|未実施|Galaxy未接続。未使用5分以上素材・3回交互比較・実音声開始/PSS/温度も未測定|','',
      'P1の候補選択だけが変わる狭い保護用途と、P2/P4のshadowは併存可能だが、精度・速度改善の有望な実稼働組み合わせを今回の数字から選べない。初回確定/再確認、P3 OFF/ONは同じD・同じGalaxyで評価する必要がある。P1だけで6/9目標が達成される想定は棄却する。元PR #49/mainのコードと通常設定は変更していない。','',
      '## 10. 再現と証拠','',
      '`summary.json`（Mac単独対照）、`events.csv/json`（全50イベント）、`frames.csv`（全1248フレーム）、`controls.json`、`android-metrics.json`、`android-pairs.json`、`environment.json`、`inventory.json`に数値・hashを保存。原文を含む実験JSON/TSVは`private/`へ分離。`logs/`にビルド、失敗・修正履歴、実行ログを保存。入力動画・SRT・PNGは既存保存先を読み取るだけで上書きしていない。','',
      '```sh',
      '# 作業ルートから。DATA_ROOTは既存比較データのルート。パスはruntime/env.shを参照。',
      'source runtime/env.sh',
      'python3 repo/tools/device-ad-compare/test_selection.py',
      'python3 repo/tools/device-ad-compare/test_compare_trials.py',
      'python3 repo/tools/mac-p0-p5/test_metrics.py',
      'gradle --offline --no-daemon -p repo -PcaptionAbis=arm64-v8a :app:testDebugUnitTest :app:assembleDebug',
      'python3 repo/tools/mac-p0-p5/evaluate.py --workspace . --data-root "$DATA_ROOT" --jdk "$JAVA_HOME"',
      '"$VSE_PYTHON" repo/tools/mac-p0-p5/controls.py --workspace . --jdk "$JAVA_HOME"',
      'gradle --offline --no-daemon -p repo -PcaptionAbis=arm64-v8a -PcaptionTestRunner=jp.hidemaru.burnedcaptionreader.evaluation.MacOcrExperimentInstrumentation :app:assembleDebugAndroidTest',
      '# 独立エミュレーターだけ。既存実機runnerと混ぜない。',
      'python3 repo/tools/mac-p0-p5/run_android.py --workspace . --data-root "$DATA_ROOT" --adb "$ANDROID_HOME/platform-tools/adb" --device emulator-5562',
      'python3 repo/tools/mac-p0-p5/report.py --workspace .','```','',
      f'Android単体テスト{metadata["junit_tests"]}件、失敗{metadata["junit_failures"]}。指定Python診断テスト8件、新しい採点テスト5件も通過。デバッグapp/test APKをビルドし、70＋70＋4画像の本物の推論を完走。検証用runner追加だけでmainソースの差分は0。通常test runnerは既定を保持し、任意Gradleプロパティで今回runnerを指定する。正式releaseビルド/通常YouTube再生は未検証。','',
      'モデルhashはenvironment.json、素材全hashはinventory.json。主要動画：']
    for item in inventory:
        if item['kind']=='video':lines.append(f'- {item["film"]}: `{item["sha256"]}`')
    lines+=['','今回の検証用コードは別branchへ保存する。次の機能修正は、短文shape/最低文字数、P3の実際の発動・短文map、P4のサンプル間保護を分けたDraft PRで提案すべきであり、今回は安全性未証明の修正を本番へ配線しない。素材、画像、全字幕、診断ZIP、APKは公開しない。','',
      '## 11. 実機評価に必要なユーザー操作','',
      '- Galaxy S25をUSB接続し、開発者設定のUSBデバッグを有効にして、このMacへのデバッグ認可を許可する。',
      '- 許可済みの未使用5分以上の日本語字幕動画のローカルパスを指定する。CG2作品以外で固定見出し・短文・上部複数行を含む素材が望ましい。',
      '- 実際の音声の開始・二重読みを評価する場合は、端末を操作可能な状態にし、録音/実聴による独立確認を行う。','']
    text='\n'.join(lines);(out/'REPORT.md').write_text(text)
    (w/'REPORT.md').write_text('# OCR改善実験結果\n\n[詳細REPORT](outputs/ocr-p0-p5-experiment-20261013/REPORT.md)\n')
    print('Saved report and numeric metrics',len(lines),'lines')
if __name__=='__main__':main()
