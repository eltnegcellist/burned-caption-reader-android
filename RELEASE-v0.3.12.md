# v0.3.12 — first diagnostic-driven fixes

Based on v0.3.11-diagnostics, not the outdated main branch.

## Changes
- Independently spoken rows can be matched across recent history when OCR later merges them into a two/three-line block. Require two matching nontrivial rows; emit only remaining rows.
- Keep the existing 60-second bounded history. Relaxed row matching is only applied to multi-row evidence, not all single sentences.
- Guard changed digits and common Japanese negation markers from fuzzy whole-caption suppression. This is a conservative lexical check, not semantic understanding.
- Allow four independently qualified caption bands instead of two plus a nearby fragment. Preserve existing UI/product filtering and temporal eligibility checks.
- Rescue compact Japanese spatial multi-line dialogue with adequate confidence. Merely increasing the band limit did not recover the supplied dialogue examples.
- Add five regressions, including two exact OCR row/coordinate/confidence snapshots from caption-diagnostics-1788932464243.zip and the observed split-to-merged duplicate after 17–19 seconds.

## Validation and limitations
66 subtitle tests passed with the local Java/JUnit-compatible harness, including existing watch/UI exclusions. Full Android build/tests run in GitHub Actions. No physical device verification is claimed.

This is phase 1–3's initial, bounded fix, not completion of the full plan. Row history still uses time/text rather than screen-lifetime identity: a genuinely repeated identical caption inside 60 seconds may be suppressed. Tracking disappearance/reappearance and position-aware history needs a separate change with lifecycle tests. The two snapshots do not constitute a full recorded-stream or image/OCR replay.

Automatic pause exists in CaptureService via BrowserMediaController.pauseBrowser(), with resume on TTS listener idle. Its overlay feedback loop, complete selection-rejection diagnostics, queue-drop instrumentation and ordering refinements are not changed here. Actual OCR misrecognition is not fixed by this release. Test with auto-pause OFF first; verify all three dialogue groups, duplicates, and the original watch/title examples.
