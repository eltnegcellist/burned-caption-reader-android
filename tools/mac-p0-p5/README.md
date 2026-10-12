# Mac P0–P5 experiment runner

These tools replay frozen Android observations through the production Java
classes and run a separate emulator-only inference diagnostic. They do not
enable real frame skipping, update normal app preferences, or start TTS.

Use a workspace with `repo/` (PR49 or the experiment branch), `baseline-main/`
(main `2c4d3db86fdacae051480bd8c91c83f560200627`), an existing licensed comparison
data root, and JDK17. The expected data layout is the PR32 comparison workspace's
`outputs/open-movie-benchmark/{elephants,sintel}/android-video.json` and
`private/open-movie-benchmark/{elephants,sintel}/` with reference SRT, saved PNGs,
and pixelcopy-dataset. No model or media download is performed by these runners.

`evaluate.py` checks all input hashes, compiles both checkouts, replays each
condition three times, verifies diagnostic parity, and measures P2/P3/P4 on
the same observations. Java component timings are desktop timings, excluding
decode and serialization. `controls.py` needs Pillow and creates independent
pixel controls, including changed pixels between fingerprint sample points.

`run_android.py` requires the explicit dedicated emulator serial and a test APK
built with `-PcaptionTestRunner=jp.hidemaru.burnedcaptionreader.evaluation.MacOcrExperimentInstrumentation`.
It measures coarse A/D on the same 70 images and v6 limited ROI feasibility on
the four previously empty Elephants manual-ROI images. The A coarse pass uses
ML Kit; its v5 recognition/refinement stage is intentionally outside this
measurement. The four ROI probes use known regions and do not establish live
P3 activation/recovery rates. This runner does not uninstall existing apps or
change another emulator; use a dedicated compatible installation.

`test_metrics.py` checks that numeric/negation changes remain errors, blank
insertions are counted, and collapsed line breaks fail NFC structure scoring.
`report.py` generates the local report and sanitized numeric summaries. Its
local `private/` outputs contain OCR and reference text: keep these outside Git.
The checked-in REPORT and NUMERIC_RESULTS contain numbers and hashes only.

Galaxy S25, actual speech start, real skips, and a new unseen five-minute video
remain unmeasured. See [REPORT.md](REPORT.md) for the measured scope and decisions.
