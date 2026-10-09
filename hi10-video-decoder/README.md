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
MC integration does not change FFmpeg threading, conversion or audio decoding.
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
acceptance probe is removed. Target-device minified Release playback and exact burst
results are recorded in
[`benchmarks/xiaomi-hardening-validation-2026-10-09.md`](benchmarks/xiaomi-hardening-validation-2026-10-09.md).
Its remaining limits still apply; successful builds or one TV do not prove wider
production readiness.

## Production scope and lifecycle

Software eligibility is clear AVC High10, ARMv7, rotation=0, ordinary Surface output.
Prefer hardware only when Media3 advertises High10 and supports the actual track's
profile/level, size and frame rate. Non-High10 AVC, HEVC Main10 and AV1 keep their
existing paths. DRM and rotated tracks are not claimed by this software renderer;
unsupported High10 must produce an actionable error rather than audio-only playback
or initialize an explicitly incompatible platform AVC decoder.

`High10DecoderLifecycle` owns fixed input/output pools (4/16 on Xiaomi). One worker
feeds FFmpeg and converts synchronously. A rejected EAGAIN packet retains its input
slot while output is drained; no unbounded copied-packet queue exists. EOS sends a
null packet and receives through native EOF before publishing one EOS. Decoded
best-effort timestamp, then frame PTS, controls output-start filtering; missing PTS
is an explicit error, never guessed from whichever input happened to trigger receive.

Flush invalidates generations, queues and pending drain immediately; worker flushes
native codec before processing new-generation input. In-flight and caller-held
storage is not prematurely recycled. Release wakes queue waiters, joins outside
queue/render locks, then releases native context. Surface control/render operations
share ownership protection; shared native color metadata uses a short mutex.

`ANativeWindow_setBuffersDataSpace` is resolved from its owning `libnativewindow.so`,
not `RTLD_DEFAULT`. Its API28+ availability remains optional on older Android APIs;
API24–27 retain the original platform fallback and require separate color acceptance.
One platform-library handle is retained process-wide, not once per decoder.
Surface submission/drop/stale-generation outcomes are distinct. Debug
`renderedFrames` counts successful native posts, `surfaceDroppedFrames` counts
abandoned/invalid Surface drops, and Media3 `rendered`/`dropped` counters keep their
own semantics. None of these proves physical HDMI presentation.

## Actual JNI/Surface regression gate

Test APK is independent of the installed player. Use a local synthetic fixture,
not copyrighted episode media. Reuse existing `avc-hi10.mp4` when available. Fixture
contract: AVC High10 with B-frames, 1280x720, 30fps, 20s/600 frames, limited-range
BT.709 testsrc (black/white corners). A compatible host FFmpeg/libx264 build can
generate it in a dedicated fixture directory:

```bash
ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 20 \
  -vf format=yuv420p10le -c:v libx264 -profile:v high10 -bf 2 \
  -color_range tv -colorspace bt709 -color_trc bt709 -color_primaries bt709 \
  -an avc-hi10.mp4
ffmpeg -i avc-hi10.mp4 -an -sws_flags bilinear -pix_fmt yuv420p \
  -f framemd5 avc-hi10-yuv8.framemd5
python -m http.server 18019 --bind 127.0.0.1 --directory /absolute/fixture/directory
```

From another terminal with the intended device connected:

```bash
adb -s DEVICE_SERIAL reverse tcp:18019 tcp:18019
./gradlew :hi10-video-decoder:connectedDebugAndroidTest
adb -s DEVICE_SERIAL reverse --remove tcp:18019
```

Windows uses `gradlew.bat`. If Gradle UTP fails before running tests with an invalid
device-provider output path, run the same standalone APK directly (keep the fixture
server/reverse active):

```bash
./gradlew :hi10-video-decoder:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r hi10-video-decoder/build/outputs/apk/androidTest/debug/hi10-video-decoder-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w com.nuvio.hi10video.test/androidx.test.runner.AndroidJUnitRunner
```

Test-only `fixtureBaseUrl` instrumentation argument
defaults to `http://127.0.0.1:18019/`. Stop the local server after testing. No addon
or account installation is needed. Tests compare all decoded PTS/hashes including
last delayed frame, five seeks, flush during drain, full-pool release, stale-output
rejection, native Surface-abandon accounting and release/output-replacement races.
Real SurfaceHolder recreation reuses its Java Surface and verifies black/white
range plus pixel-identical PixelCopy readback without submitting a newer frame.
Readback is not physical display proof; real player pause/resume, A/V/subtitles,
thermal matched playback and minified Release remain separate acceptance gates.

## Symbol isolation

FFmpeg is statically linked. `-fvisibility=hidden`, `--exclude-libs,ALL`, `-Bsymbolic`, and
`nuvio_hi10.map` restrict the dynamic export table to `JNI_OnLoad`. Generic FFmpeg and swscale
symbols therefore remain local to this DSO. The SONAME is explicitly `libnuvioHi10Video.so`.
