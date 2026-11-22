#!/usr/bin/env python3
"""Verify Android sidecars against the manifest pinned by the app."""

import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def binary_digests(root=ROOT):
    return {abi: hashlib.sha256((root / "prebuilt" / abi / "libnuvio_usenet.so").read_bytes()).hexdigest() for abi in ABIS}


def read_manifest(root=ROOT):
    data = json.loads((root / "prebuilt" / "manifest.json").read_text())
    if data.get("version") != 1 or not re.fullmatch(r"[0-9a-f]{64}", data.get("sourceSha256", "")):
        raise ValueError("Invalid Usenet release manifest")
    if set(data.get("binarySha256", {})) != set(ABIS) or any(
        not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
        for value in data["binarySha256"].values()
    ):
        raise ValueError("Invalid Android Usenet binary checksums")
    return data


def main():
    try:
        data = read_manifest()
        if data.get("binarySha256") != binary_digests():
            raise ValueError("Android Usenet sidecar digests do not match the manifest")
    except (OSError, ValueError) as exc:
        print(f"{exc}. Run scripts/fetch_prebuilts.py to fetch the pinned release.", file=sys.stderr)
        return 1
    print("All four Android sidecar digests match the pinned release")
    return 0


if __name__ == "__main__":
    sys.exit(main())
