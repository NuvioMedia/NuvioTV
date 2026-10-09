# Isolated AVC High10 video decoder

This module is intentionally independent of `app/libs/lib-decoder-ffmpeg-release.aar` and loads
`libnuvioHi10Video.so`. Video architecture is adapted from AndroidX Media PR #1591 at commit
`1649087fbe3ce1b2c51abc320782be0b600b311b` under Apache-2.0.

## Pinned inputs

- FFmpeg release: `7.0.2`
- FFmpeg tag commit: `e3a61e91030696348b56361bdf80ea358aef4a19`
- Archive: `https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz`
- Archive SHA-256: `8646515b638a3ad303e23af6a3587734447cb8fc0a0c064ecdb8e95c4fd8b389`
- FFmpeg static-library NDK: `29.0.14206865`
- JNI/MC NDK: `27.0.12077973` (explicit module pin; unchanged compiler)
- CMake: `3.22.1`
- Android ABI: `armeabi-v7a`
- Minimum API used by native compiler: 24

## Build

From WSL, using the Windows NDK wrappers:

```bash
bash hi10-video-decoder/src/main/jni/build_ffmpeg_armv7.sh \
  /mnt/c/Users/akai/source/NuvioTV/hi10-video-decoder \
  /mnt/c/Users/akai/AppData/Local/Android/Sdk/ndk/29.0.14206865
```

Then build the Android module with JDK 21:

```powershell
$env:JAVA_HOME='C:\Users\akai\.jdks\jbr-21.0.11'
.\gradlew.bat :hi10-video-decoder:testDebugUnitTest :hi10-video-decoder:assembleRelease
```

## ARMv7 High10 MC integration

`high10_qpel_family_neon.c` retains the validated coherent 8/16-wide High10 put/avg
qpel family. Dispatch replaces these entries only for depth 10 with NEON available;
other depths, no-NEON CPUs, and smaller block entries retain FFmpeg's original tables.
No changes to frame threading, conversion, audio decoding, or Media3 feed/drain.
Xiaomi baseline remains four frame workers and sixteen output buffers; existing
processor-count-based thread selection on other devices is unchanged.

CMake prepares a **build-local copy** of libavcodec with only the initializer's
definition renamed. All decoder callers still reach the wrapper, which initializes
FFmpeg's original tables first. The original archive is never modified. Clean stock
archives and historical already-renamed archives are accepted; original initializer
`.text` must remain byte-identical. No manual archive edit is needed after rebuilding
FFmpeg. The internal DSP header is hash-pinned to the validated 7.0.2 layout; upgrades
require explicit revalidation, not just changing the version number.

Host integration regression (WSL; C compiler, binutils, CMake):

```bash
bash hi10-video-decoder/src/test/native/archive_hook_test.sh
```

After the minified application Release build, verify the actual DEX JNI contract
(R8 mapping alone omits some unchanged native names and is not sufficient):

```bash
python3 hi10-video-decoder/src/test/native/verify_jni_apk.py \
  app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk
```

Consumer rules retain both JNI class names and every registered native method,
including snapshot JNI retained for registration even though Release Java never calls it.

The implementation is LGPL-2.1-or-later (see source header); distribution must retain
FFmpeg/candidate source and corresponding build instructions and meet applicable
LGPL obligations. This integration is currently ARMv7-only, not new ARM64 SIMD coverage.
Stage clocks/counters and Java pipeline probes are Debug-only; the temporary burst
acceptance probe is removed. Release playback on target hardware remains an acceptance
gate before distribution; building successfully is not proof of production readiness.

## Symbol isolation

FFmpeg is statically linked. `-fvisibility=hidden`, `--exclude-libs,ALL`, `-Bsymbolic`, and
`nuvio_hi10.map` restrict the dynamic export table to `JNI_OnLoad`. Generic FFmpeg and swscale
symbols therefore remain local to this DSO. The SONAME is explicitly `libnuvioHi10Video.so`.
