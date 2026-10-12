# Four-way Android comparison

Build the PR APK and instrumentation after `python3 tools/models/prepare_v6.py`.
Use frozen, independently pixel-reviewed input as described in
`tools/image-evaluator/README.md`. Keep development and held-out videos separate.

```sh
python3 tools/four-way-evaluator/run.py /data/dev-cat /data/dev-watch /data/heldout \
  --output /data/results --adb /sdk/platform-tools/adb --device emulator-5554 \
  --label GIT_SHA --passes 2
```

Both passes use fresh app processes; pass 2 reverses A/B/C/D. Accuracy uses the
first pass, while latency includes both passes. Every mode uses the identical
manifest, references, original image hashes and monotonic timestamps. Player
replay calls the production factory and shared caption/TTS-request components.
It does not measure audible speech, playback concurrency, power or real-device heat.

Independent component inference scores neither selected captions nor model
acceptance gates. `caption_lines` contains `{text, box}` per manually reviewed
horizontal line; both PP recognizers receive the identical original crop.
`text_boxes` contains `{text, box, role}` and records all legible text in selected
frames. All boxes are normalized left/top/right/bottom coordinates. Optional
`detection_roi` and `detection_ignore_regions` restrict the *diagnostic metric*
only; automatic player replay always sees the full image. ML Kit supplies its
public recognized-line boxes, since its internal detector proposals are inaccessible.
The PP diagnostic saves every DB box before shape filtering/empty-reading removal.
Session constructors run in ML Kit / v6 detector / v5 rec / v6 rec order; page
caches and ONNX library startup can affect initialization timings.

For the frozen PR #33 report bundle, extract its `datasets/` and `results/`
folders into one working directory, then run the saved `analyze.py` there.
Analysis needs only manifests and result JSON; actual inference also needs the
locally retained original PNGs. IoU uses maximum one-to-one matching at 0.5.
CER is code-point Levenshtein distance, with both raw and application comparison
key scores. SER is an erroneous whole-caption *frame* fraction, including blank
frame false positives; repeated observations are not independent sentences.
Insertions/deletions are alignment operations, not a claim about their semantic
source. Warm times exclude first image/model initialization frames; all-image
timings and excluded IDs are retained. p95 uses nearest rank.

See `docs/2026-10-12-pr33-four-way-validation.md` for the actual findings and limits.
