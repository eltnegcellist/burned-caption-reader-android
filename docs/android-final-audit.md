# Android completion audit — 2026-10-01 (JST)

Baseline: `main` / `acdb8b249fd3fa939551529ef3b202a3bb2b93db`, v1.0.0.
The published v1.0.0 APK remains the stable baseline. This audit does not
establish real-device completion or OCR accuracy.

## Verified

- Baseline GitHub Actions run 36654929913 succeeded, including replay,
  `:app:testDebugUnitTest`, `:app:assembleDebug`, and stable APK publication.
- Re-ran all 129 existing Java unit tests locally with JDK 17 and JUnit 4.13.2.
- After the interruption regressions below, all 132 Java unit tests passed.
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
3. Add pure pipeline regressions for retry after cancellation, suppression of
   completed speech after detection reset, and independent history in a new
   video. Android callback timing still needs device testing.

## Remaining engineering work (not just device checks)

- **Signing continuity:** CI currently assembles a debug APK with an ephemeral
  default key. The optional `CAPTION_DEBUG_KEYSTORE` hook exists but the workflow
  does not supply it. A recoverable, persistent signing key must be configured
  before guaranteeing future update installs. If the private key for installed
  v1.0.0 is unavailable, preserving that certificate in a new build is impossible;
  a one-time reinstall or a separately identified migration build is needed.
  Do not commit a private keystore into this repository.
- **Active OCR pipeline:** `SharedPlayerActivity` performs one ML Kit recognition
  on each captured frame. `OcrRefinementSelector` and `TemporalOcrConsensus` are
  present and tested but are not used by this active path. Earlier two-stage OCR
  and consensus claims must not be assumed to describe v1.0.0's active player.
  Establish screenshot/log evidence before integrating or tuning them.
- **Diagnostics:** the current home/settings screens do not expose diagnostic
  export. The recorder exists and TTS writes diagnostic events, but the in-app
  player does not record its OCR selection pipeline. Old screen-sharing logs do
  not validate the current player. A minimal export/recording path is needed if
  detailed repeat/miss diagnosis is to continue on this architecture.
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
| Install update | Compare certificates before installing next version | Matching cert required; current CI does not yet guarantee it |

Android completion remains conditional on the remaining engineering work and
device acceptance. Keep v1.0.0 available; do not label this audit branch a finished
replacement release solely because automated tests pass.
