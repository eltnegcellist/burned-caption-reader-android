#!/usr/bin/env python3
"""Download immutable official PP-OCRv6 Small ONNX assets for the four-way test.
Runs at *build time*, never downloads a model from an app user's device.
Avoids committing ~31MB of large opaque binaries to git.
"""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "app/src/main/assets/ppocrv6"
DETECTION_REV = "28fe5895c24fd108c19eb3e8479f4ab385fbfc62"
RECOGNITION_REV = "b8f84f0b80c529de40b4fbb3544b84fa7233a513"
SOURCES = {
    "detection.onnx": ("PP-OCRv6_small_det_onnx", DETECTION_REV, "inference.onnx",
        9880512, "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"),
    "recognition.onnx": ("PP-OCRv6_small_rec_onnx", RECOGNITION_REV, "inference.onnx",
        21159378, "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"),
    "recognition.yml": ("PP-OCRv6_small_rec_onnx", RECOGNITION_REV, "inference.yml",
        150579, "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1"),
    "detection.yml": ("PP-OCRv6_small_det_onnx", DETECTION_REV, "inference.yml",
        885, "193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516"),
}
def checked(name, item):
    repo, revision, source, size, sha = item
    path = TARGET / name
    if path.exists() and path.stat().st_size == size and hashlib.sha256(path.read_bytes()).hexdigest() == sha:
        return
    url = f"https://huggingface.co/PaddlePaddle/{repo}/resolve/{revision}/{source}"
    print(f"Fetching pinned {name} from {repo}@{revision[:9]}",flush=True)
    temp = path.with_name(path.name + ".part")
    digest = hashlib.sha256()
    count = 0
    try:
        with urlopen(url, timeout=180) as src, temp.open("wb") as dest:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk: break
                count += len(chunk); digest.update(chunk); dest.write(chunk)
        if count != size or digest.hexdigest() != sha:
            raise ValueError(f"{name}: expected {size}/{sha}, got {count}/{digest.hexdigest()}")
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)

def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, source in SOURCES.items(): checked(name,source)
    import yaml
    config = yaml.safe_load((TARGET/"recognition.yml").read_text(encoding="utf-8"))
    chars = config["PostProcess"]["character_dict"]
    if not isinstance(chars,list) or len(chars)<1000 or not all(isinstance(c,str) for c in chars):
        raise ValueError("PP-OCRv6 recognition dictionary unexpected")
    (TARGET/"characters.json").write_text(
        json.dumps([""]+chars+[" "],ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8")
    provenance = {
        name: {"repo": item[0], "revision": item[1], "source": item[2], "bytes":item[3],"sha256":item[4]}
        for name,item in SOURCES.items()
    }
    (TARGET/"model-provenance.json").write_text(json.dumps(provenance,indent=2)+"\n")
    print(f"Ready PP-OCRv6 Small; CTC classes={len(chars)+2}")
if __name__ == "__main__": main()
