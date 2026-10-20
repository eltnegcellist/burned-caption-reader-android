# Mac Codex: Private 99-video-frame Indra OCR 1.1.2 vs current E

## Status and objectives

This is **ready-to-run private test code**, NOT a completed 99-frame inference.
Prior 5-frame synthetic run and actual archived E PR #55 outputs are separate.

- User wants to compare Indra OCR 1.1.2 with **current E** on exactly the
  same original 99 Japanese video frames from PR #33 / PR #55.
- Mac images and reference strings must remain private. Do not put PNG,
  original captions/GT, raw OCR text, models, ADB diagnostics, trace or any
  personal file path in a public repository or GitHub Actions artifact.
- Production stays E, no changes to Android app, switches, releases or APK.
- Existing P1–P4 improvements remain closed, no implementation scope creep.
- Indra model weights have modified OpenRAIL-M terms, do not redistribute
  model binaries. Research use only until distribution terms are reviewed.
- This comparison will **not** be a smartphone speed comparison. Indra is native
  macOS ARM64 CPU; archived E is Android AVD replay on the Mac.

## Frozen 99-image sources

Previously documented locally retained PR #33 folders:

- $DATASETS/dev-cat : 28 images
- $DATASETS/dev-watch : 28 images
- $DATASETS/heldout : 43 images (**previously used, not independently new**)

A fully auditable E baseline is the PR #55 archived pass-1 replay output:

- $E_RESULTS/dev-cat-e_mlkit_v6-1.json
- $E_RESULTS/dev-watch-e_mlkit_v6-1.json
- $E_RESULTS/heldout-e_mlkit_v6-1.json

Earlier Mac reports locate this directory at a local private
e-experiment/results folder, distinct from the Git working tree.

The private evaluator checks:

1. All three **original** manifest SHA256 (hardcoded from PR #55 report).
2. Count 28+28+43 and exact PNG hash for every row.
3. Matching frame ID, reference text, mono_ms, manifest hash in E raw JSON.
4. Full E output re-scored with actual Java comparisonKey port:
   29+48+103=180 edits / 2545 reference chars -> **7.07% CER**.
5. Same E APK SHA across all three datasets.
6. Same input bytes for both Indra modes and E results.
7. Private output path must be outside repository root.

Mismatch in any input **aborts before running Indra**, preventing an invalid
result from being presented as a speedup or CER improvement.

## Running on the user's Mac with Codex

Use a separate worktree, do not change the user's existing workspace:

    git fetch origin
    git worktree add ../caption-indra99 origin/codex/indra-99-vs-e-private-mac-20261020
    cd ../caption-indra99

Locate the original private folders from the prior Codex workspace.
Do not fabricate the paths or quietly substitute newer screenshots. Set:

    export DATASETS="/path/to/original/2026-10-12-github-pr-33-4-ocr-https/datasets"
    export E_RESULTS="/path/to/existing/private/e-experiment/results"
    export PRIVATE_OUT="/path/outside/git/private-indra99-output"

Check that input files actually exist. First run hash validation **without
installing any models or running OCR**:

    python3 tools/indra-ocr-99-private/run_private_mac.py \
      --datasets "$DATASETS" --e-results "$E_RESULTS" \
      --out "$PRIVATE_OUT" --validate-only

Once the gate reports 99 frames and **180/2545**, set up Python 3.11 or newer
in a virtualenv or the Codex workspace's isolated Python environment:

    python3 -m venv /path/outside/git/.venv-indra99
    source /path/outside/git/.venv-indra99/bin/activate
    python -m pip install "indra-ocr==1.1.2" "pillow>=11,<13"

Run Indra with the *same original images*, 4 CPU threads, 3 repetitions.
Both default image orientation and known-upright mode are measured:

    python tools/indra-ocr-99-private/run_private_mac.py \
      --datasets "$DATASETS" --e-results "$E_RESULTS" \
      --out "$PRIVATE_OUT" --threads 4 --repeat 3 \
      --labels "/path/to/existing/private/strata-labels.json"

If the optional private labels file is unavailable, omit --labels rather than
guessing labels. The output is **outside Git**:

- $PRIVATE_OUT/indra-e-99-PRIVATE.md — aggregate table, no text excerpts.
- $PRIVATE_OUT/indra-e-99-PRIVATE.json — FULL references, OCR outputs,
  per-image SHA and timings. Keep local; **never attach to public PR**.

Indra records **full-image OCR** and E records *subtitle-selected OCR*, so
its non-subtitle detection may be penalized in the caption CER. This is a valid
initial end-to-end comparison of outputs, but not a pure recognizer comparison
or a completed Indra-powered app. If useful, conduct a separate *identical,
manual-caption-crop* recognizer experiment and label it oracle diagnostic,
never deployable accuracy.

### If E raw results cannot be found

Do **not** skip the E matching gate or backfill outcomes from public
frames.numeric.json (that file deliberately does not contain OCR strings).

Look for the previously retained PR #55 results first. If truly missing,
reproduce with the **same** private manifest on an authorized emulator:

    python3 tools/models/prepare_v6.py
    gradle :app:assembleDebug :app:assembleDebugAndroidTest
    python3 tools/image-evaluator/run.py "$DATASETS/dev-cat" \
      "$E_RESULTS/dev-cat-e_mlkit_v6-1.json" \
      --adb "$ANDROID_HOME/platform-tools/adb" --device emulator-5554 \
      --label E-retest --mode player-replay \
      --ocr-mode e_mlkit_v6 --subtitle-recheck off

Repeat for dev-watch and heldout. The exact emulator-5554 must actually exist
and be authorized; if not, use the configured **explicit** emulator serial,
not a physical Galaxy S25. Use a dedicated private output directory and do
not uninstall/change the user's phone app. Freeze the new APK/model hashes.
If new E source/refinement produces scores other than the original
180/2545, investigate the change instead of forcing the expected result:
the archived 7.07% benchmark and current-new E test then need separate tables.

## Reporting and decision

Provide a concise anonymous summary with:

- Validation gate result, exact model version/hash as observed on the Mac,
  three frozen dataset manifests, git commit and input provenance.
- E / Indra auto-rotation / Indra turn=False **CER, edits, exact frames,
  missed subtitles, blank-frame false positives**, overall and per video.
- Row-count and background-text strata **only if labels are available**.
- Repeated Indra Mac native latency median and p95, model startup separately;
  never divide these by archived E Android emulator time.
- Examples of failure mechanisms with redacted examples or anonymized IDs.
  Pay particular attention to 3-line rows, 180° orientation errors, noncaption
  nameplates, top row omission, negation and numbers.
- Explicit statement that these are **three previously studied videos**,
  not new holdout clips or a Galaxy S25 test.
- Comparative recommendation: no merge of Indra models/logic unless the
  candidate meets or beats E under the same caption metric AND the licensing
  question and real-device latency are resolved.

No further changes to production OCR without user's separate direction.
