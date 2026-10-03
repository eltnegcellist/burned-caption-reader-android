# Android image OCR evaluation

This entry point runs the application's **actual `MlKitJapaneseOcrEngine` on Android**.
It measures raw image OCR only: code-point edit distance / reference characters
(CER, including whitespace and punctuation), per-frame OCR text, timestamp gaps,
and measured OCR processing time. It does not score caption selection, temporal
fusion, TTS, audio, or real-time performance. Original full video frames include
non-caption text; annotate all visible text for raw OCR CER. A caption-only
reference is useful for inspecting output, but must not be called raw OCR CER.

## Prepare real input

```sh
python3 tools/image-evaluator/prepare.py /path/to/diagnostics.zip /path/to/dataset
```

Only saved `video_frame` images from the current PixelCopy diagnostics are accepted.
Images remain local. Inspect each image and enter `expected_text` in `dataset.json`
in top-to-bottom / left-to-right order. Never copy OCR predictions into ground
truth. Use `""` for no text. Set `human_verified` to true only after inspection;
assign `split` to `development` or `held_out` and keep held-out data out of tuning.
`sha256` freezes each image. Missing images remain missing; timestamps expose gaps.
Do not infer capture completeness from these retained frames.

## Build and run

Build with JDK 17, Gradle 8.13 and Android SDK 36:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/image-evaluator/run.py /path/to/dataset /path/to/baseline.json \
  --adb /path/to/sdk/platform-tools/adb --device emulator-5554 --label BASELINE_GIT_SHA
```

The runner installs only on the explicitly named emulator. It refuses real-device
serials. Dataset files are copied to debug app-private storage using `run-as`;
the custom instrumentation produces JSON results, failing on unreadable images,
missing references, unordered timestamps, OCR errors, or timeout. It removes a
previous result before running so stale output cannot appear to pass.

Run the candidate build against the same dataset and emulator. Result JSON stores
the original manifest hash, APK hash, and supplied build label. Compare predictions
and total edit distance with the same reference-character count. Device OCR time
includes cold model initialization and should be reported separately from steady
state. This runner preserves timestamps as metadata; it does **not** replay the
tracking pipeline or schedule images in real time.

```sh
python3 tools/image-evaluator/compare.py /path/to/baseline.json /path/to/candidate.json
```

Comparison rejects mismatched manifest hashes, device serials, references, frame
counts, timestamps and evaluation scopes. Negative edit-distance delta is better
raw OCR on this dataset; it says nothing about speech omissions or duplicates.

Synthetic images may verify this harness but do not establish real caption quality.
With Pillow installed, generate a separate smoke dataset using
`python3 tools/image-evaluator/make_smoke.py /path/to/smoke --font /path/to/Japanese-font`.
It explicitly records `source: synthetic`, `human_verified: false` and
`ground_truth_source: synthetic_render_spec`, so generated text is never represented
as a human annotation of a real frame.
The published v1.0.0 and existing diagnostic-free ledger replay remain separate
baselines. Do not publish a quality-improvement claim from raw OCR or synthetic
results alone.
