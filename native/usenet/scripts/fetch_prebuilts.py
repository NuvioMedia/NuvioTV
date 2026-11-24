#!/usr/bin/env python3
"""Fetch the pinned Android release assets, verifying each SHA-256 before use."""

import hashlib
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import URLError
from urllib.request import urlopen

from check_prebuilts import ABIS, ROOT, read_manifest


def fetch(root=ROOT, opener=urlopen):
    data = read_manifest(root)
    base = data.get("downloadBaseUrl", "")
    if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+", base):
        raise ValueError("Manifest must pin a GitHub release downloadBaseUrl")
    for abi in ABIS:
        expected = data["binarySha256"][abi]
        destination = root / "prebuilt" / abi / "libnuvio_usenet.so"
        if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".part", delete=False) as output:
                temporary = Path(output.name)
                digest = hashlib.sha256()
                size = 0
                with opener(f"{base}/libnuvio_usenet-{abi}.so", timeout=120) as response:
                    while chunk := response.read(65536):
                        size += len(chunk)
                        if size > 128 * 1024 * 1024:
                            raise ValueError(f"Usenet {abi} download exceeds 128 MiB")
                        digest.update(chunk)
                        output.write(chunk)
            if digest.hexdigest() != expected:
                raise ValueError(f"Usenet {abi} download failed SHA-256 verification")
            temporary.replace(destination)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    print("All four Usenet release assets are cached and verified")


def main():
    try:
        fetch()
    except (OSError, ValueError, URLError) as exc:
        print(f"{exc}. Download failed; no unverified binary was installed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
