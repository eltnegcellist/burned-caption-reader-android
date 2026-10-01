# Android completion audit — 2026-10-01 (JST)

Baseline: `main` / `acdb8b249fd3fa939551529ef3b202a3bb2b93db`, v1.0.0.
The published v1.0.0 APK remains the stable baseline. This audit does not
establish real-device completion or OCR accuracy.

## Verified

- Baseline GitHub Actions run 36654929913 succeeded, including replay,
  `:app:testDebugUnitTest`, `:app:assembleDebug`, and stable APK publication.
- Re-ran all 129 existing Java unit tests locally with JDK 17 and JUnit 4.13.2.
- After interruption and local OCR pipeline regressions, all 141 Java unit tests passed.
- All four signing configuration tests passed locally.
- Re-ran the frozen replay: 19 fixture rows; old path emitted 24 rows, current
  ledger 22 rows; cancellation controls passed. One hard case remains unresolved.
  This is a ledger replay, not a video/OCR benchmark.
- Manifest and active UI use the in-app player, with no MediaProjection service.
- Fullscreen callbacks, settings panel, speech-only pause, screen-awake setting,
  and CC-hiding logic are present. Presence is not real-device validation.

## Fixes in this review branch

1. On Activity pause, stop speech and reset detection state while retaining
   completed speech history. Previously the ledger released cancelled speech,
   but its stabilizer retained the committed caption and prevented retry.
2. On full page navigation, stop old speech and invalidate captured frames.
   Detect a different video URL and clear its previous speech history.
   Sample the URL in fullscreen too, so watch-page SPA/autoplay changes are
   detected without relying exclusively on `onPageFinished`.
3. Connect the tested refinement/consensus modules to the current player and
   restore local diagnostic recording/export to settings.
4. Flush held speech at the order buffer's deadline even without another OCR
   result; cancel that timer on detection reset, pause and destruction.
5. Add pure pipeline regressions for retry after cancellation, suppression of
   completed speech after detection reset, and independent history in a new
   video. Android callback timing still needs device testing.

## Remaining engineering work (not just device checks)

- **Signing continuity:** the workflow now restores a private keystore from
  GitHub Secrets and can reject builds without it. See `tools/signing/README.md`.
  No signing secret was configured by this change; update-compatible installs
  remain conditional on recovering the existing private key. Without that key,
  reproducing the installed v1.0.0 certificate is impossible. CI explicitly labels
  an unconfigured build as using a temporary test certificate.
- **Active OCR pipeline:** the review branch connects coarse OCR (up to 1100px
  wide), padded original-frame subtitle crops (long edge up to 2000px),
  `OcrRefinementSelector`, and per-track `TemporalOcrConsensus`. Tests cover
  recovery of three ordered rows and prevent old votes from overriding numeric,
  negative-expression or typewriter changes. This does not validate actual font
  accuracy. Up to five OCR jobs per frame can slow sampling on-device; inspect
  `frame_done.processing_ms` in fresh diagnostic data before further tuning.
- **Diagnostics:** settings now expose explicit recording and document-picker
  ZIP export. The current player records video-only frames, coarse/refined OCR,
  selected bands, consensus, stabilization and speech ledger decisions. TTS queue
  replacement/capacity discards have explicit reasons. Recording is opt-in,
  bounded to about two minutes/64MB, and may omit images under load. Exact tracker
  rejection reasons for every raw row are not yet exposed.
- **Speech tradeoff:** balanced mode completes the current utterance and replaces
  waiting speech; it does not guarantee every caption is read. Latest mode can
  interrupt. Automatic video pause is not wired into the current in-app player.
- **Hard OCR overlap:** the replay evaluator explicitly leaves one observed
  punctuation-heavy overlapping caption case unresolved. Passing unit tests do
  not mean all duplicate reads or omissions are fixed.

## Galaxy S25 acceptance checks

Use the same tested build for each check and record WebView/TTS engine versions.

| Check | Procedure | Expected result |
| --- | --- | --- |
| URL/share | Open one video by URL, another through browser share | In-app playback, no screen-sharing prompt |
| Captions | Lower caption, upper caption, 3 lines, two speakers | Upper-to-lower order; log duplicates/misses instead of assuming perfect OCR |
| Interruption | Leave app during speech, return while caption remains | Unfinished visible caption can retry; completed captions remain suppressed |
| Video switch | Open next video inside YouTube, also test autoplay/fullscreen | Old speech stops; same text in new video is eligible |
| Fullscreen | Enter/exit fullscreen and rotate both ways | Correct video crop, no title/comment OCR |
| Settings | Change rate/volume while playing | Video continues; next utterance uses new values |
| Speech pause | Pause/restart speech | Video continues; paused-period captions are not queued |
| CC | Hidden/enabled setting with an English CC video | Hidden CC not mixed into speech; verify visible CC behavior when unhiding |
| Duration | Play 20–30 minutes | Record heat, crashes, OCR cadence and lag; no growing speech backlog |
| Lock | Press power button | Known restriction: playback/OCR need not continue while locked |
| Install update | Compare certificates before installing next version | Matching cert required; configure persistent key first |

Android completion remains conditional on the remaining engineering work and
device acceptance. Keep v1.0.0 available; do not label this audit branch a finished
replacement release solely because automated tests pass.
