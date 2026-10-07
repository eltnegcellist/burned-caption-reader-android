#!/usr/bin/env python3
"""Convert the pinned official PP-OCRv5 character dictionary (requires PyYAML 6.0.3)."""
import argparse
import hashlib
import json
from pathlib import Path
import yaml

p = argparse.ArgumentParser()
p.add_argument("inference_yml", type=Path)
p.add_argument("output_json", type=Path)
a = p.parse_args()
data = a.inference_yml.read_bytes()
if hashlib.sha256(data).hexdigest() != "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e":
    p.error("Source differs from the pinned official model configuration")
characters = yaml.safe_load(data)["PostProcess"]["character_dict"]
if len(characters) != 18383 or not all(isinstance(c, str) for c in characters):
    p.error("Unexpected model dictionary")
converted = (json.dumps([""] + characters + [" "], ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
a.output_json.write_bytes(converted)
print(hashlib.sha256(converted).hexdigest())
