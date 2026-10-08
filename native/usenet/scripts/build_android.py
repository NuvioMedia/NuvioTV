#!/usr/bin/env python3
"""Build all Android sidecars and record their source/binary correspondence."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess

from check_prebuilts import ABIS, ROOT, record, source_digest

TARGETS = (("arm64", "aarch64-linux-android"), ("arm", "armv7a-linux-androideabi"),
           ("amd64", "x86_64-linux-android"), ("386", "i686-linux-android"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--go", default="go")
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--host", default="linux-x86_64", choices=("linux-x86_64", "darwin-x86_64", "windows-x86_64"))
    args = parser.parse_args()
    args.ndk = args.ndk.resolve()
    before = source_digest()
    toolchain = args.ndk / "toolchains" / "llvm" / "prebuilt" / args.host / "bin"
    suffix = ".cmd" if args.host == "windows-x86_64" else ""
    outputs = []
    for abi, (arch, target) in zip(ABIS, TARGETS):
        build = ROOT / "build" / "prebuilt" / abi
        subprocess.run([args.cmake, "-S", str(ROOT / "third_party" / "rapidyenc-native"), "-B", str(build),
                        f"-DCMAKE_TOOLCHAIN_FILE={args.ndk / 'build' / 'cmake' / 'android.toolchain.cmake'}",
                        f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
                        "-DCMAKE_BUILD_TYPE=Release", "-DDISABLE_SHARED=ON", "-DDISABLE_TOOL=ON", "-DDISABLE_CRC=ON"], check=True)
        subprocess.run([args.cmake, "--build", str(build), "--target", "rapidyenc_static", "-j", "4"], check=True)
        shutil.copyfile(build / "rapidyenc_static" / "librapidyenc.a",
                        ROOT / "third_party" / "rapidyenc" / f"librapidyenc_android_{arch}.a")
        env = dict(os.environ, GOOS="android", GOARCH=arch, CGO_ENABLED="1",
                   CC=str(toolchain / f"{target}24-clang{suffix}"), CXX=str(toolchain / f"{target}24-clang++{suffix}"))
        if arch == "arm":
            env["GOARM"] = "7"
        output = build / "libnuvio_usenet.so"
        subprocess.run([args.go, "build", "-trimpath", "-buildvcs=false", "-buildmode=pie",
                        "-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384", "-o", str(output), "./cmd/nuvio-usenet"],
                       cwd=ROOT, env=env, check=True)
        outputs.append((abi, output))
    if before != source_digest():
        raise RuntimeError("Usenet source changed during the build; prebuilts were not replaced")
    for abi, output in outputs:
        destination = ROOT / "prebuilt" / abi / "libnuvio_usenet.so"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output, destination)
    go_version = subprocess.check_output([args.go, "version"], text=True).strip()
    ndk_version = next(line.split("=", 1)[1].strip() for line in (args.ndk / "source.properties").read_text().splitlines()
                       if line.startswith("Pkg.Revision"))
    record(go_version, ndk_version)


if __name__ == "__main__":
    main()
