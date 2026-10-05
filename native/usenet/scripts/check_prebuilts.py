#!/usr/bin/env python3
"""Reject Android sidecars whose recorded source or binary digest is stale."""

import hashlib
import json
from pathlib import Path
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


def binary_digests():
    return {abi: hashlib.sha256((ROOT / "prebuilt" / abi / "libnuvio_usenet.so").read_bytes()).hexdigest() for abi in ABIS}


def record(go_version, ndk_version):
    data = {"version": 1, "sourceSha256": source_digest(), "binarySha256": binary_digests(),
            "toolchain": {"go": go_version, "ndk": ndk_version}}
    temporary = MANIFEST.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    temporary.replace(MANIFEST)


def main():
    try:
        data = json.loads(MANIFEST.read_text())
        if data.get("version") != 1 or data.get("sourceSha256") != source_digest():
            raise ValueError("Android Usenet sidecars were built from different source")
        if data.get("binarySha256") != binary_digests():
            raise ValueError("Android Usenet sidecar digests do not match the manifest")
    except (OSError, ValueError) as exc:
        print(f"{exc}. Rebuild all ABIs with scripts/build_android.py.", file=sys.stderr)
        return 1
    print("Usenet source and all four Android sidecar digests match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
