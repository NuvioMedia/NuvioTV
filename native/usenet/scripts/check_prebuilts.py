#!/usr/bin/env python3
"""Reject Android sidecars whose recorded source or binary digest is stale."""

import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
MANIFEST = ROOT / "prebuilt" / "manifest.json"
SOURCE_SUFFIXES = {".go", ".mod", ".sum", ".cc", ".c", ".cpp", ".h", ".hpp", ".S", ".s", ".cmake", ".kts", ".py"}


def source_digest(root=ROOT):
    digest = hashlib.sha256()
    paths = sorted(
        (p for p in root.rglob("*")
        if p.is_file() and (p.suffix in SOURCE_SUFFIXES or p.name == "CMakeLists.txt")
        and not p.name.endswith("_test.go")
        and not {"build", "prebuilt", "testdata", "__pycache__"}.intersection(p.relative_to(root).parts)),
        key=lambda p: p.relative_to(root).as_posix(),
    )
    for path in paths:
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
    return digest.hexdigest()


def binary_digests(root=ROOT):
    return {abi: hashlib.sha256((root / "prebuilt" / abi / "libnuvio_usenet.so").read_bytes()).hexdigest() for abi in ABIS}


def record(go_version, ndk_version, release_repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", release_repository):
        raise ValueError("Release repository must be owner/name")
    source = source_digest()
    data = {"version": 1, "sourceSha256": source, "binarySha256": binary_digests(),
            "downloadBaseUrl": f"https://github.com/{release_repository}/releases/download/usenet-native-{source[:16]}",
            "toolchain": {"go": go_version, "ndk": ndk_version}}
    temporary = MANIFEST.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    temporary.replace(MANIFEST)


def read_manifest(root=ROOT):
    data = json.loads((root / "prebuilt" / "manifest.json").read_text())
    if data.get("version") != 1 or data.get("sourceSha256") != source_digest(root):
        raise ValueError("Android Usenet sidecars were built from different source")
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
        print(f"{exc}. Rebuild all ABIs with scripts/build_android.py.", file=sys.stderr)
        return 1
    print("Usenet source and all four Android sidecar digests match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
