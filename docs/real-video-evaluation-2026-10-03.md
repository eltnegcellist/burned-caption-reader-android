# Real-video evaluation — 2026-10-03 JST

Large three-row captions spanning ~0.40–0.44 of video height were split into 2+1
lanes by a fixed 0.34 height budget. Padded re-OCR of the upper band recovered the
bottom row while a second band read it again. The candidate gives spatially
validated triples a proportional height budget, preserves a top-anchored 2↔3-row
lane only with compatible font height/alignment, and requires consistent third-row
spacing so a document prop cannot fill the extra budget. Bitmap preparation was
extracted without changing the production resize/crop behavior.

## Frozen-input Android results

Baseline production components: commit `37ae0b37a15e6452d3130932b213784dd42b35e9`,
using the same test-only replay harness as the candidate. Baseline APK SHA-256 and
candidate APK SHA-256 are retained in each result. This is the unmodified preview
player tracker baseline, **not** a claim comparing the released v1.0.0 APK.

105 original PixelCopy frames from four streamed YouTube videos were frozen with
original timestamps, pixel hashes and independently transcribed references.
No downloaded video, transcript/CC API, audio recognition or external OCR was used.
Mute/controls-obscured frames were excluded. References were transcribed by Codex
from pixels, not OCR predictions: `ground_truth_source=codex_visual_review`,
`human_verified=false`; human annotation audit remains pending.

Metric is selected-caption comparison-key CER, after production refinement
selection and `SubtitleNormalizer.comparisonKey`. It includes missed selection
and extra text; excludes whitespace and common punctuation. It is not raw ML Kit
CER, audible speech accuracy, or an entire-video accuracy estimate.

| Video ID | Split | Frames / retained span | Edits / reference chars before → after | CER before → after | OCR jobs before → after | Simulated requests before → after |
|---|---|---|---|---|---|---|
| X115n3Sn5pc | held out | 31 / 28.939s | 309→309 / 429 | 72.03→72.03% | 35→35 | 0→0 |
| a6yGrlEas-4 | development | 27 / 63.846s | 484→306 / 974 | 49.69→31.42% | 63→49 | 7→8 |
| gb0Je93haqA | held out | 14 / 31.121s | 156→149 / 196 | 79.59→76.02% | 23→23 | 2→2 |
| DHX3ZDRP4Go | development | 33 / 66.256s | 350→221 / 869 | 40.28→25.43% | 75→61 | 7→6 |

Video2's exact duplicated `マスかったですかね?` row in one request was removed;
visually similar duplicate tails in other requests were also removed. The newly
recovered `それらの点検を一つ一つやっていたら…` event makes eight of eleven retained
event groups represented in replay requests, versus seven before. Three groups
still have no request. On video4, seven old requests represented six of thirteen
retained event groups (one caption was split across two requests); six candidate
requests represent the same six groups with joined captions, including the full
`大変申し訳ありません…` three-row event. Seven groups still have no request.
These are inspected replay groups, not measured complete-video omission rates.

Video4 was first held out and revealed a regression in the first grouping-only
candidate: CER rose from 40.28 to 43.04%, requests fell from seven to four. That
candidate was rejected. Video4 then became development input to fix row-count
tracking. Only videos1 and3 remain held out from tuning. First-candidate evidence
is retained locally, with this change of split explicitly documented.

Video1's isolated retained segment starts with empty tracker state and excludes
prior observations. Raw broad OCR often reads its caption cleanly but the replay
selects no band after provisional static-lane expiry; the isolated replay produces
zero requests. Its **full live baseline session** did submit/start/complete three
TTS utterances. The user's “mostly reads” observation is therefore not contradicted
by an entire-video failure claim. Sparse retention/cold replay cannot establish
that claim. Video3 still misses short captions and has OCR errors on menu/scene
text. Neither input was used to adjust the fix.

## Validation and scope

144 Java unit tests pass, including original video2/4 row-box regressions for tall
triples, 2→3→2 continuity and document-label separation. Four signing tests pass;
frozen ledger replay/cancellation controls pass (old24/new22 rows, one known
unresolved case). Main/test APKs build. A final incremental build initially found
an extra generated `BandText 2.class`; cleaning generated build output and rebuilding
resolved that artifact error. No source workaround was added for it.

Normal WebView streamed playback → video-only PixelCopy → production ML Kit →
TTS submission/start/done was observed in live emulator diagnostics. A final
video4 portrait session retained35 original frames,35 completed frames, six TTS
submissions/starts and five completions before capture exit. It recorded one queued
request and no retained OCR/TTS errors or discarded requests. `processing_ms`:
min338, median1071, max1437. These descriptive live values are **not** a same-input
speedup comparison with the older session, and do not establish S25 speed or heat.
The last in-flight request at instrumented shutdown is not counted as completed.

The replay executes production components with simulated immediate TTS completion
and stableMs300. It does not execute Activity asynchronous scheduling/lifecycle,
PixelCopy, audio, Balanced queue replacement or cancellation. See
`tools/image-evaluator/README.md` for commands, input schema and scope distinctions.

Emulator: Android16/API36 arm64, 1080×2340 density420, RAM4GB, WebView133.0.6943.137;
Google TTS20241125 installed, Balanced speech, level100%, media-volume setting5.
Actual Japanese TTS start/done callbacks were observed, but audio was not listened
to or recorded for verification. S25 OS/WebView/TTS, loudness, heat/battery, sustained
playback, lock/pause/resume and real-device upgrade signing remain unverified.

The stable release, main branch, and two-day Actions retention/cleanup are preserved.
Changes remain on the evaluation branch and draft PR28 stacked on draft PR27.
Only text evidence/fixtures are committed; original images, manifests, result JSON,
APKs and live diagnostic archives are retained locally for review and rerun.
