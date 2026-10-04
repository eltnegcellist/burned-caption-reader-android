#!/usr/bin/env python3
"""Run actual ML Kit on an explicitly selected emulator and save auditable results."""
import argparse
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("dataset", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--adb", required=True)
parser.add_argument("--device", required=True)
parser.add_argument("--label", required=True, help="Git SHA or build label")
parser.add_argument("--mode", choices=["raw", "player-replay", "caption-crops"], default="raw")
parser.add_argument("--crop-preparation", choices=["original", "double", "white-core", "dark-core"], default="original", help="Diagnostic manual-caption-region preparation; never automatic detection")
parser.add_argument("--coarse-width", type=int, choices=[0,1100], default=0, help="Test-only pipeline override, not automatic application performance")
parser.add_argument("--app-apk", type=Path)
parser.add_argument("--test-apk", type=Path)
args = parser.parse_args()
if args.coarse_width and args.mode != "player-replay":
    parser.error("Coarse width override requires player-replay diagnostic mode")
if args.crop_preparation != "original" and args.mode != "caption-crops":
    parser.error("Crop preparation requires caption-crops diagnostic mode")
if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.label):
    parser.error("Label must contain only letters, digits, dot, underscore, slash or hyphen")
if not args.device.startswith("emulator-"):
    parser.error("Only explicit emulator targets are supported; real-device installs need separate review")
dataset = json.loads((args.dataset / "dataset.json").read_text(encoding="utf-8"))
synthetic = dataset.get("source") == "synthetic" and dataset.get("ground_truth_source") == "synthetic_render_spec"
visual_review = dataset.get("ground_truth_source") == "codex_visual_review" and dataset.get("reference_verified_from_pixels") is True
if (dataset.get("human_verified") is not True and not synthetic and not visual_review) or not dataset.get("frames"):
    parser.error("Nonempty manually verified dataset required")
if args.mode == "caption-crops" and dataset.get("manual_caption_regions") is not True:
    parser.error("Diagnostic mode requires manually reviewed caption regions; not automatic recognition")
previous = -1
for frame in dataset["frames"]:
    if args.mode == "caption-crops":
        boxes = frame.get("caption_regions")
        if not isinstance(boxes, list):
            parser.error("Each diagnostic frame requires caption_regions, including [] for blanks")
        for box in boxes:
            if (not isinstance(box, list) or len(box) != 4
                    or any(type(v) not in (int, float) or not math.isfinite(v) for v in box)
                    or not 0 <= box[0] < box[2] <= 1
                    or not 0 <= box[1] < box[3] <= 1):
                parser.error("Invalid normalized manual caption rectangle")
    if not isinstance(frame.get("expected_text"), str):
        parser.error("Each expected_text must be manually entered; use empty string for no text")
    path = (args.dataset / frame["image"]).resolve()
    if not path.is_relative_to(args.dataset.resolve()):
        parser.error("Image path escapes dataset")
    if hashlib.sha256(path.read_bytes()).hexdigest() != frame["sha256"]:
        parser.error("Image hash mismatch")
    if frame["mono_ms"] < previous:
        parser.error("Frames must be ordered")
    previous = frame["mono_ms"]
package = "jp.hidemaru.burnedcaptionreader"
def adb(*command, **kwargs):
    return subprocess.run([args.adb, "-s", args.device, *command], check=True, **kwargs)
root = Path(__file__).resolve().parents[2]
app_apk = args.app_apk or root / "app/build/outputs/apk/debug/app-debug.apk"
test_apk = args.test_apk or root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb("install", "-r", str(app_apk))
adb("install", "-r", str(test_apk))
# Transfer through stdin to avoid Android shared-storage permission/version differences.
adb("shell", "run-as", package, "mkdir", "-p", "files/image-evaluation/images")
def copy(local, remote):
    with local.open("rb") as source:
        adb("shell", f"run-as {package} sh -c 'cat > {remote}'", stdin=source)
for i, frame in enumerate(dataset["frames"]):
    # Remote filenames use a fixed safe generated name, independent of input paths.
    copy(args.dataset / frame["image"], f"files/image-evaluation/images/{i}.png")
    frame["image"] = f"images/{i}.png"
payload = json.dumps(dataset, ensure_ascii=False).encode("utf-8")
adb("shell", f"run-as {package} sh -c 'cat > files/image-evaluation/dataset.json'", input=payload)
adb("shell", "run-as", package, "rm", "-f", "files/image-evaluation/results.json")
run = adb("shell", "am", "instrument", "-w", "-r", "-e", "label", args.label,
          "-e", "mode", args.mode, "-e", "crop_preparation", args.crop_preparation, "-e", "coarse_width", str(args.coarse_width),
          package + ".test/jp.hidemaru.burnedcaptionreader.evaluation.ImageEvaluationInstrumentation",
          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
log = run.stdout.decode("utf-8")
print(log)
if "INSTRUMENTATION_CODE: -1" not in log or "FAILED:" in log:
    raise SystemExit("Instrumentation failed; no result accepted")
output = adb("exec-out", "run-as", package, "cat", "files/image-evaluation/results.json", stdout=subprocess.PIPE)
result = json.loads(output.stdout)
result["device_serial"] = args.device
result["input_manifest_sha256"] = hashlib.sha256((args.dataset / "dataset.json").read_bytes()).hexdigest()
result["apk_sha256"] = hashlib.sha256(app_apk.read_bytes()).hexdigest()
result["test_apk_sha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Saved evaluation results: {args.output}")
