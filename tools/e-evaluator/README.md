# Experimental E: ML Kit detection + v6 recognition + diagnostic selection

The original PR #55 experiment (`815d0b8`) kept `ExperimentalEPolicy` in test-only sources.
The adopted policy is now `ECaptionOcrPolicy` in `app/src/main/java/.../ocr`.
`e_mlkit_v6` selects `CaptionOcrMode.E`, with the same ML Kit/v6 engine branch as B
and the shared E selection policy. No v6 detector is called. Normal app preferences
now select E; A/B/C/D explicit diagnostics remain available. See
`docs/2026-10-13-adopt-e-ocr.md` for adoption and first-observation validation.

The policy receives OCR geometry, strings and PP row probabilities only. It receives
neither GT, video names nor D outputs. Current choices were frozen before running E
and must not be tuned after inspecting this known 99-image experiment.

- Before tracking, reject a small Japanese noun label (width <= .50, height <= .12,
  length 2..12, no sentence-ending/punctuation) only if a spatially separate, larger
  adjacent two-row caption exists. Require a wide >= .50 anchor, >= 1.25 glyph-height
  ratio and separation > max(.10, 3 glyph heights). This can reject merged nameplates;
  bottom/top position alone never rejects a caption.
- After the ordinary B refinement, allow complete contiguous v6 row groups with the
  same row count as coarse, every PP probability >= .65, length ratio .50..1.50 and
  text similarity >= .50. Numeric/negation changes require every probability >= .90.
  These probability guards apply to additional E overrides; B's ordinary selection
  remains the fallback with its existing acceptance behavior. No recognition/detection inference is added.

Run B/D/E from the same app/test APK, fresh process each time, with cyclic orders
B,D,E / D,E,B / E,B,D. Use at least three repeats and freeze models/images/GT/labels.
The existing runner checks images, manifests, modes and engine calls.

```bash
python3 tools/four-way-evaluator/run.py "$DATASETS/dev-cat" "$DATASETS/dev-watch" "$DATASETS/heldout" \
  --output "$PRIVATE_RESULTS" --adb "$ADB" --device emulator-5580 --label E-fixed-89e2a5f \
  --passes 3 --modes v6_rec v6_both e_mlkit_v6 --rotate-order --record-environment --skip-components
python3 tools/e-evaluator/analyze.py --datasets "$DATASETS" --results "$PRIVATE_RESULTS" \
  --labels "$FROZEN_LABELS" --frozen-build "$FROZEN_BUILD" \
  --output docs/experiments/e-mlkit-v6-20261013
```

GT is used solely by the scorer after OCR finishes. This is exploratory known-video
validation, not evidence of unseen-video generalization or real audible timing.
Private frames, GT text, APKs, models and raw replay files must not be committed.
