# Android frame evaluation

The instrumentation calls the production `MlKitJapaneseOcrEngine` on Android.
Keep two scopes separate:

- `raw` (default): all visible text, raw code-point CER including whitespace and
  punctuation. Annotate all text for this metric, not only captions.
- `player-replay`: shared production bitmap preparation, caption tracker,
  same-original-frame crop and re-OCR, refinement selection, temporal consensus,
  stabilizer (stableMs=300), order buffer, and row ledger. References contain only
  intended Japanese captions. `caption_comparison_key_cer` scores per-frame
  selected text after `SubtitleNormalizer.comparisonKey`; omitted captions count
  as deletions, extra selected text as insertions. This is **not raw OCR CER**.
  Results also expose broad OCR, every refinement band, job count, and emitted
  speech-request text/timestamps.

The replay starts with empty state and uses retained original timestamps. Order
flushes occur at logical deadlines between frames. TTS completes immediately in
this simulation. It does not exercise Activity callbacks, PixelCopy, real-time
scheduling, TTS queue/cancellation/audio, pause/resume, or heat. Sparse retained
frames may miss observations that occurred during live playback. Its speech
requests are not a measure of words audibly spoken or a complete live omission
rate. Live diagnostics must be reviewed separately.

## Prepare frozen input

```sh
python3 tools/image-evaluator/prepare.py /path/to/diagnostics.zip /path/to/dataset
```

Only original `video_frame` images from PixelCopy diagnostics are imported. Images
remain local. Independently inspect pixels and enter `expected_text` in reading
order; never copy OCR predictions into references. Use `""` for no target text.
Set `human_verified: true` only after human inspection. A Codex visual transcription
must instead explicitly retain `human_verified: false`,
`ground_truth_source: codex_visual_review`, `reference_verified_from_pixels: true`,
and a note that human audit is pending. Such results are provisional.

Assign `split: development` or `held_out`. Freeze labels before evaluating a
held-out input and keep it out of tuning. Frame SHA-256 and manifest SHA-256 freeze
both original pixels and annotations. Missing retained images remain missing;
timestamps expose gaps. Do not infer capture completeness from retained frames.
Exclude frames with mute buttons/player controls obscuring target captions.

## Build and compare

Use JDK17, Gradle8.13, SDK36:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/image-evaluator/run.py /path/to/dataset /path/to/baseline.json \
  --adb /path/to/sdk/platform-tools/adb --device emulator-5554 \
  --mode player-replay --label BASELINE_GIT_SHA \
  --app-apk /path/to/baseline-app-debug.apk
```

The runner installs only on the explicitly named emulator, never a physical-device
serial. It copies data into debug app-private storage via `run-as`, rejects changed
image hashes/missing references/unordered timestamps, and removes the previous
result before running. OCR errors, timeout, or failed instrumentation fail the run.
`--test-apk` optionally selects the harness APK. The same harness can exercise the
baseline and candidate app APKs if their production component APIs are compatible.

Repeat against the candidate APK with the **identical dataset and emulator**:

```sh
python3 tools/image-evaluator/compare.py baseline.json candidate.json
```

Comparison rejects different manifest hashes, references, timestamps, frame count,
device, scope, metric, or annotation provenance. Negative edit-distance delta is
better for the stated metric; it does not by itself establish better audible
speech. Processing times include model initialization and emulator effects;
replay times must not be compared with live diagnostic `processing_ms` as speedups.

## Live diagnostic capture (test APK only)

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e mode capture -e video_id VIDEO_ID_11_CHARACTERS -e seconds 60 \
  jp.hidemaru.burnedcaptionreader.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation
adb -s emulator-5554 exec-out run-as jp.hidemaru.burnedcaptionreader \
  tar -cf - no_backup/caption-diagnostics > diagnostics.tar
```

This opens the normal share flow, starts the existing bounded local recorder, and
stops after 20–90 seconds. Its worker must publish `session_stop` before the test
exits. Playback still requires UI inspection: dismiss autoplay mute affordances,
verify captions are unobscured, and wait for controls to disappear. A tap command succeeding does not prove that the mute affordance disappeared.
Save a screenshot after the tap, visually verify its absence, record device
monotonic uptime, and inspect every included original frame before annotating.
Exclude all pre-verification/obscured frames and ads; discard a session with no
verified unobscured interval. Record exclusions and verification evidence with
the dataset. The record cap
may retain only the session tail. Capture uses normal streamed playback and local
PixelCopy, never downloaded videos, transcript/CC APIs, or external OCR. The
recorder is opt-in and disabled after the capture.

Synthetic images verify the harness only:
`python3 tools/image-evaluator/make_smoke.py /path/to/smoke --font /path/to/Japanese-font`.
They retain synthetic provenance and `human_verified: false`. The published v1.0.0
and diagnostic-free ledger replay remain separate baselines.


The shared player now calls `selectForRecognition`: qualified caption candidates
reach refinement without consuming an extra temporal observation; speech still
requires the unchanged consensus, stabilizer, order buffer and ledger. Compact
parallel noun labels are excluded before grouping. Small single-row rescue bands
retain the old provisional temporal gate. Legacy `selectAll` behavior is unchanged.
The replay harness discovers the production candidate entry point by reflection,
falling back to `selectAll` only for old APKs that lack it. This permits exactly the
same test APK for both builds; each frame records `selection_api` for audit.

## Longer debug-only capture

Normal diagnostics retain a bounded tail. For a five-minute verification window,
use the debug APK's `capture-window` mode rather than increasing the ordinary
user recording duration:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e mode capture-window -e video_id VIDEO_ID_11_CHARACTERS \
  -e max_seconds 900 -e window_seconds 305 \
  jp.hidemaru.burnedcaptionreader.test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation
```

First inspect the actual player screen, tap any mute/skip affordance, and verify
that controls no longer obscure the captions. Only then create the local gate:

```sh
adb -s emulator-5554 shell run-as jp.hidemaru.burnedcaptionreader \
  touch files/full-evaluation/start-from-zero
```

The probe waits for unmuted, playing, non-ad media, rewinds to zero, and records
positions in `files/full-evaluation/state.json`. The gate is a manual visual
approval of the current player; a tap alone is not proof that an overlay cleared.
The post-rewind seek controls still require excluding the initial obscured frames.
Independent reference PNGs are requested roughly once a second even when OCR is
busy. Only reference images are saved; OCR stage metadata remains in JSON.
The explicit debug store preserves old records up to 3 GiB/200,000 records, then
fails without discarding existing evidence. Image omission counters remain fatal
to capture success. `summary.json` distinguishes window completion from native
video end. `capture-full` requires a matching native end event instead of a window.
Neither mode establishes all-video-frame coverage. Inspect ad and overlay images
before selecting evaluation input, and report requested/retained/scored counts
separately. Delete collected screenshots and image archives after validation when
requested; retain result JSON, reference hashes, and code, and state that identical
OCR replay then needs recapture.

The three five-minute results and limitations are in
[`docs/2026-10-03-five-minute-evaluation.md`](../../docs/2026-10-03-five-minute-evaluation.md).
