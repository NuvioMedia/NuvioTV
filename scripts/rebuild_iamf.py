"""Rebuild the pinned Media3 IAMF native libraries; preserve every non-native AAR entry.

Requires Git, Android NDK r29 and CMake >=3.21 (with Ninja alongside it).
Writes a candidate AAR; deliberately never replaces the application dependency.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

MEDIA_COMMIT = "b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9"  # AndroidX Media3 1.8.0
IAMF_COMMIT = "f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63"  # libiamf v1.1.0
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
ROOT = Path(__file__).resolve().parents[1]


def run(*args: object, cwd: Path | None = None) -> None:
    subprocess.run([str(x) for x in args], cwd=cwd, check=True)


def checkout(path: Path, url: str, commit: str, sparse: str) -> None:
    if not path.exists():
        run("git", "clone", "--filter=blob:none", "--no-checkout", url, path)
    actual_url = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=path, text=True).strip()
    if actual_url != url:
        raise ValueError(f"Unexpected source repository at {path}")
    # Never reset or clean a developer checkout; this directory is build-owned.
    run("git", "sparse-checkout", "set", sparse, cwd=path)
    run("git", "checkout", "--detach", commit, cwd=path)
    dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=path, text=True)
    if dirty.strip():
        raise ValueError(f"Source checkout contains modifications: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--work", type=Path, default=ROOT / "build/native-iamf")
    parser.add_argument("--input-aar", type=Path, default=ROOT / "app/libs/lib-decoder-iamf-release.aar")
    parser.add_argument("--output-aar", type=Path, default=ROOT / "build/native-iamf/lib-decoder-iamf-release.aar")
    args = parser.parse_args()
    if args.input_aar.resolve() == args.output_aar.resolve():
        parser.error("Use a separate candidate output; replace the dependency only after verification")
    ndk = args.ndk.resolve()
    if "Pkg.Revision = 29.0.14206865" not in (ndk / "source.properties").read_text():
        parser.error("This build recipe is pinned to Android NDK 29.0.14206865")
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    media = work / "media"
    iamf = work / "libiamf"
    checkout(media, "https://github.com/androidx/media.git", MEDIA_COMMIT, "libraries/decoder_iamf")
    checkout(iamf, "https://github.com/AOMediaCodec/libiamf.git", IAMF_COMMIT, "code")
    libraries = {}
    ninja = args.cmake.resolve().with_name("ninja.exe" if args.cmake.suffix == ".exe" else "ninja")
    for abi in ABIS:
        build = work / abi
        run(args.cmake, "-S", ROOT / "scripts/native/iamf", "-B", build, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={ninja.as_posix()}",
            f"-DCMAKE_TOOLCHAIN_FILE={(ndk / 'build/cmake/android.toolchain.cmake').as_posix()}",
            f"-DMEDIA_SOURCE={media.as_posix()}", f"-DIAMF_SOURCE={iamf.as_posix()}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY")
        run(args.cmake, "--build", build, "--target", "iamfJNI", "--parallel", "2")
        for filename in ("libiamf.so", "libiamfJNI.so"):
            matches = list(build.rglob(filename))
            if len(matches) != 1:
                raise ValueError(f"Expected exactly one {filename} in {build}, got {matches}")
            libraries[f"jni/{abi}/{filename}"] = matches[0].read_bytes()
    args.output_aar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.input_aar) as source:
        original_names = set(source.namelist())
        if not set(libraries).issubset(original_names):
            raise ValueError("Input AAR native layout does not match the recipe")
        classes_hash = hashlib.sha256(source.read("classes.jar")).hexdigest()
        license_files = {
            "media3-APACHE-LICENSE.txt": media / "LICENSE",
            "libiamf-LICENSE.txt": iamf / "LICENSE",
            "libiamf-PATENTS.txt": iamf / "PATENTS",
            "libiamf-code-LICENSE.txt": iamf / "code/LICENSE",
            "resample-LICENSE.txt": iamf / "code/src/iamf_dec/resample.license",
            "wavwriter-LICENSE.txt": iamf / "code/dep_external/src/wav/dep_wavwriter.license",
        }
        with zipfile.ZipFile(args.output_aar, "w") as output:
            for item in source.infolist():
                output.writestr(item, libraries.get(item.filename, source.read(item.filename)))
            for name, path in license_files.items():
                archive_name = f"assets/licenses/iamf/{name}"
                if archive_name not in original_names:
                    output.writestr(archive_name, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(args.output_aar) as output:
        assert hashlib.sha256(output.read("classes.jar")).hexdigest() == classes_hash
    evidence = {"media_commit": MEDIA_COMMIT, "libiamf_commit": IAMF_COMMIT,
                "ndk": "29.0.14206865", "classes_jar_sha256": classes_hash,
                "input_sha256": hashlib.sha256(args.input_aar.read_bytes()).hexdigest(),
                "output_sha256": hashlib.sha256(args.output_aar.read_bytes()).hexdigest(),
                "native_sha256": {key: hashlib.sha256(value).hexdigest() for key, value in libraries.items()}}
    (args.output_aar.parent / "iamf-build-provenance.json").write_text(json.dumps(evidence, indent=2))
    print(args.output_aar)


if __name__ == "__main__":
    main()
