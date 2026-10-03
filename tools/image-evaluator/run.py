#!/usr/bin/env python3
"""Run actual ML Kit on an explicitly selected emulator and save auditable results."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("dataset", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--adb", required=True)
parser.add_argument("--device", required=True)
parser.add_argument("--label", required=True, help="Git SHA or build label")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.label):
    parser.error("Label must contain only letters, digits, dot, underscore, slash or hyphen")
if not args.device.startswith("emulator-"):
    parser.error("Only explicit emulator targets are supported; real-device installs need separate review")
dataset = json.loads((args.dataset / "dataset.json").read_text(encoding="utf-8"))
synthetic = dataset.get("source") == "synthetic" and dataset.get("ground_truth_source") == "synthetic_render_spec"
if (dataset.get("human_verified") is not True and not synthetic) or not dataset.get("frames"):
    parser.error("Nonempty manually verified dataset required")
previous = -1
for frame in dataset["frames"]:
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
adb("install", "-r", str(root / "app/build/outputs/apk/debug/app-debug.apk"))
adb("install", "-r", str(root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
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
result["apk_sha256"] = hashlib.sha256((root / "app/build/outputs/apk/debug/app-debug.apk").read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Saved raw OCR results: {args.output}")
