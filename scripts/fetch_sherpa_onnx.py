#!/usr/bin/env python3
"""Download the pinned Android speech runtime from its upstream release."""

import hashlib
from pathlib import Path
import tempfile
import urllib.request

VERSION = "1.13.8"
FILENAME = f"sherpa-onnx-{VERSION}.aar"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{FILENAME}"
SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def main():
    destination = Path(__file__).resolve().parents[1] / "app" / "libs" / FILENAME
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if digest(destination) != SHA256:
            raise SystemExit(f"Checksum mismatch: move {destination} aside and retry.")
        print(f"Verified {FILENAME}")
        return

    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".download", delete=False) as output:
            temporary = Path(output.name)
            with urllib.request.urlopen(URL, timeout=120) as response:
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    output.write(chunk)
        if digest(temporary) != SHA256:
            raise SystemExit("Downloaded speech runtime failed SHA-256 verification.")
        temporary.replace(destination)
        print(f"Downloaded and verified {FILENAME}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
