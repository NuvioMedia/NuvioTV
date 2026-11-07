#!/usr/bin/env python3
"""Publish verified native assets to the release pinned by the manifest."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from check_prebuilts import ABIS, ROOT, binary_digests, read_manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, help="Full SHA of the pushed, cleaned source commit for the release tag")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.target):
        parser.error("--target must be a full commit SHA")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if head != args.target:
        parser.error("--target must match the checked-out source commit")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=ROOT):
        parser.error("Commit the source and manifest before publishing")
    data = read_manifest()
    if data["binarySha256"] != binary_digests():
        raise ValueError("Native assets do not match the manifest")
    match = re.fullmatch(r"https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/releases/download/(usenet-native-[0-9a-f]{16})", data.get("downloadBaseUrl", ""))
    if not match or match[2] != f"usenet-native-{data['sourceSha256'][:16]}":
        raise ValueError("Manifest must pin a release for this source digest")
    repository, tag = match.groups()
    with tempfile.TemporaryDirectory(prefix="usenet-release-") as directory:
        staging = Path(directory)
        assets = []
        for abi in ABIS:
            asset = staging / f"libnuvio_usenet-{abi}.so"
            shutil.copyfile(ROOT / "prebuilt" / abi / "libnuvio_usenet.so", asset)
            assets.append(str(asset))
        manifest = staging / "manifest.json"
        manifest.write_text(json.dumps(data, indent=2) + "\n")
        notes = staging / "notes.md"
        notes.write_text(
            f"Android Usenet native build assets for source commit `{args.target}`.\n\n"
            "These are build dependencies, not an application release. The source tree pins "
            "this release and verifies each ABI's SHA-256 before packaging.\n\n"
            f"Native source SHA-256: `{data['sourceSha256']}`.\n"
        )
        subprocess.run([
            "gh", "release", "create", tag, "--repo", repository, "--target", args.target,
            "--prerelease", "--latest=false", "--title", f"Usenet native assets {data['sourceSha256'][:16]}",
            "--notes-file", str(notes), *assets, str(manifest),
        ], check=True)


if __name__ == "__main__":
    main()
