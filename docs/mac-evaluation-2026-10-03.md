# Mac evaluation checkpoint — 2026-10-03 (JST)

Repository starting points were fetched, rather than assumed from the handoff:
main `16cc675a8f0b2326d8f4dba91389decad6a26f06`, draft/open PR #27 head
`f64c198d25d95e30a1eed0dc5d4e2301359d18b4`. The evaluation branch merges main's
two-day artifact retention and cleanup workflows. The stable release is unchanged.

## Confirmed locally

- Apple Silicon arm64, macOS 26.6.2. Existing Homebrew Java 25 was insufficient
  for the specified build environment; a workspace-local Temurin JDK 17 and
  Gradle 8.13 were downloaded. No wrapper existed.
- Android SDK 36, build-tools 35 (AGP default) and 36, platform-tools 37.0.1,
  emulator 37.2.12, one API 36 Google APIs arm64-v8a image revision 7.
- User explicitly agreed to SDK licensing before SDK package installation.
- AVD `CaptionReader_API_36`: 4096 MB RAM, 1080 × 2340, density 420. This is a
  comparison environment, not measured Galaxy S25 parity. Android 16 fingerprint:
  `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.
- APK installed on `emulator-5554`; MainActivity launched; screenshot and logcat
  saved. No physical device was installed, uninstalled or modified.
- WebView: `com.google.android.webview`, `133.0.6943.137`.
- Google TTS service installed:
  `googletts.google-speech-apk_20241125.02_p2.702443970`. Default synth setting
  returned null. Japanese voice availability and audible speech remain untested.
- 141 Java unit tests, zero failures/errors/skips. Four signing tests passed.
  Frozen replay passed: old 24 emitted rows, ledger 22, one unresolved case.
- App and Android evaluation APK builds succeeded. The first evaluation APK
  compile failed on `Files.readString/writeString`; replacing them with Android
  compatible `readAllBytes/write` fixed the build.
- Actual `MlKitJapaneseOcrEngine` evaluated two **synthetic** three-line PNGs:
  75 reference code points including line breaks, zero edits, CER 0. OCR times
  were 403 ms (first/cold) and 139 ms. These are raw OCR times on an emulator,
  not the player's `frame_done.processing_ms` and not device-performance claims.
- Importer control verified original-image extraction, retained image hashes,
  blank/unverified references, no automatic OCR-derived truth, comparison delta
  and rejection of mismatched input hashes.

Local APK SHA-256:
`6f23a233753c4109a97c348fc2271f92f70538973cf68b30ad611706d2240226`.
Local debug certificate SHA-256:
`7cccdd0b10cc8c9fd230d9a4e72793f4252d58d4d543f6d85799d330cce658c7`.
The local debug key was retained outside the repository. It differs from the
handoff preview certificate; physical-device update compatibility is not assumed.

## New entry point

See `tools/image-evaluator/README.md`. A debug-only instrumentation runner calls
the production Android OCR engine. It accepts frozen images with manual ground
truth or separately labelled synthetic render specifications. It saves per-image
predictions, edit distance, processing time and timestamp gaps; the host runner
adds dataset and APK hashes. Comparison checks identical input/reference/device.
This first entry point intentionally measures **raw OCR**, not the entire tracking,
refinement, temporal fusion, ordering, ledger and TTS pipeline.

## Not established

No real-video diagnostic ZIP was available locally and the test-video URL is
pending user input. YouTube playback, PixelCopy capture, live OCR, speech requests,
Japanese audible speech, real-image CER, missed captions, duplicates and full
pipeline replay are not validated. No production recognition threshold was changed.
There is no measured real-video quality improvement at this checkpoint.

Next: reproduce one supplied video's failure with opt-in current-player diagnostics,
retain original frames, prepare reviewed references and a held-out split, establish
the baseline, then fix one measured failure and rerun the identical input. Preserve
the distinction between raw OCR metrics and subtitle/TTS behavioral metrics.
