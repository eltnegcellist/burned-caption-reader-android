"""Restore a signing key from CI Secrets without printing its contents."""
import base64
import os
from pathlib import Path
import sys


def restore(env):
    encoded = env.get("CAPTION_SIGNING_KEYSTORE_BASE64", "").strip()
    if not encoded:
        if env.get("CAPTION_REQUIRE_PERSISTENT_SIGNING") == "true":
            raise ValueError("Persistent signing key is required but not configured")
        print("::warning::No persistent signing key configured; this APK uses a temporary test certificate.")
        return None
    data = base64.b64decode(encoded, validate=True)
    if not data or len(data) > 1024 * 1024:
        raise ValueError("Invalid signing key size")
    folder = Path(env["RUNNER_TEMP"]) / "caption-signing"
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = folder / "caption.keystore"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
    os.chmod(path, 0o600)
    with open(env["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write("CAPTION_DEBUG_KEYSTORE=" + str(path) + "\n")
    print("Persistent signing key restored from CI Secrets.")
    return path


if __name__ == "__main__":
    try:
        restore(os.environ)
    except (ValueError, KeyError, OSError):
        print("::error::Signing key configuration failed; check CI Secrets and signing settings.", file=sys.stderr)
        sys.exit(1)
