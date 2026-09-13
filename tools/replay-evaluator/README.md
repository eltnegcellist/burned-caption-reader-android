# Caption replay evaluator

This deterministic, OCR-free replay feeds the frozen fixture through the preserved pre-change `SubtitleEventManager.accept` and the current `RowSpeechLedger.reserve` → `markInFlight` → `markSpoken` path. It reports accepted/reserved events, emitted row counts, and suppressed events. Observed rows use the exact `mono_ms` values from `replay-evidence.json`; synthetic scenarios use clearly labeled monotonic values and reset between groups. The fixture contains exact observed excerpts from `replay-evidence.json`; the punctuation-heavy later overlap is explicitly retained as `HARD_CASE_UNRESOLVED`, so it is not scored as a solved OCR or spatial problem.

The synthetic controls cover changed numerals, changed negation, short captions, disappearance and reappearance after the history window, reordered rows, a separator, and cancellation/retry. The cancellation control verifies that a reservation is not spoken until completion, and that release makes it retryable.

Run from the repository root:

```sh
tools/replay-evaluator/run.sh
```

`baseline/SubtitleEventManager.java` is the source snapshot used for the old path; its SHA-256 is recorded in `baseline/SHA256SUMS`. A Java 17 JDK is sufficient. The script also supports a JDK whose `javac` launcher is absent by invoking its compiler module directly. Android SDK, Gradle, OCR, and network access are not required.
