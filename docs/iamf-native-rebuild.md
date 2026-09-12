# IAMF native library rebuild

## Scope and provenance

The bundled IAMF AAR contains a 16 KiB-aligned `libiamfJNI.so` but 4 KiB-aligned
`libiamf.so`. The official Media3 1.8.0 CMake script applies its page-size linker
option only to the JNI target. The local build recipe applies it to both source
build targets. No ELF headers are patched, no decoder feature is removed, and
`classes.jar` plus all existing non-native AAR entries are preserved byte for byte.

Pinned sources:

- AndroidX Media3 **1.8.0**, commit
  `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`:
  https://github.com/androidx/media/tree/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/decoder_iamf
- AOMedia libiamf **v1.1.0**, commit
  `f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63`:
  https://github.com/AOMediaCodec/libiamf/tree/f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63
- Android NDK **29.0.14206865**, Android API 24, static C++ runtime,
  CMake 3.22.1 and Ninja; two compile workers.

The precise upstream commit used for the existing binary is unknown. The selected
released libiamf version matches the original capability-string form and the
Media3 JNI API; this is evidence for compatibility, not proof of original provenance.
The original native library advertises PCM capability; further export/capability
comparison and actual decoder verification are recorded below after execution.

## Repeat the build

```powershell
python scripts/rebuild_iamf.py `
  --ndk G:/tools/android-ndk-r29 `
  --cmake G:/tools/android-sdk/cmake/3.22.1/bin/cmake.exe
```

Use an available Python 3 installation. The recipe fetches pinned official Git
sources into a build-owned directory, compiles four ABIs, and writes a **candidate**
AAR under `build/native-iamf/`. It does not overwrite the application dependency.
It records source pins, NDK version, original/output AAR hashes, `classes.jar` hash,
and individual native hashes in `iamf-build-provenance.json`.

The candidate includes the upstream Media3 Apache license, libiamf BSD Clear
license and patent terms, plus code, resampler and WAV writer notices in
`assets/licenses/iamf/`. No prebuilt codec archive from the source checkout is used
unless resolved by the Android toolchain; required capability parity is checked
before accepting the candidate.

## Verification status

Source build completed for all four ABIs. Static comparison of all eight native
libraries against the original confirms:

- Every exported native symbol set is identical (zero missing or added exports).
- JNI names match the original Java native declarations (`javap -p -s`).
- DT_NEEDED dependencies are identical; no shared C++ runtime dependency is added.
- The PCM capability format is unchanged, and neither artifact advertises Opus,
  AAC or FLAC codec capability. The Android build did not import desktop codec
  archives from the upstream checkout.
- Every PT_LOAD alignment is at least 16,384 bytes, including both 64-bit ABIs.
- Every existing non-native entry remains byte-identical; six license assets are
  added separately. `classes.jar` SHA-256 is
  `37ce2c388e4d9ea45b5d7ec1bb576cf1615778cf503f759a08f148f72fccb3c2`.

Accepted candidate AAR SHA-256:
`8248056ad332d3b023af751868fd08de2b18cc10202b2d591a1815703470c0b6`.
It replaced `app/libs/lib-decoder-iamf-release.aar` during a coordinated interval
with no Gradle process running. Build evidence remains under `build/native-iamf/`:
`build.log`, `compatibility.json`, and `iamf-build-provenance.json`.

The NDK archive was downloaded from the official Google Android repository and
its SHA-1 matched the published SDK repository XML:
`ab3bb30fbb9e6903666d60c55d11e78b04e07472` (833,850,862 bytes).
It was extracted separately to avoid modifying the SDK directory used by Gradle.

The recipe is a repeatable source-build procedure, not a claim of byte-identical
ZIP output: generated license entries contain ZIP timestamps and compilation paths
can differ. The official PCM IAMF fixture was decoded successfully through Media3 on the
Android TV x86_64 API 36 emulator with 4 KiB memory pages. The first stereo output
buffer matched both upstream golden hashes, 1303596737 and 17085665. Evidence:
`output/prerelease14-device/native-debug-instrumentation.txt` (all three native
integration tests passed). This is debug-APK decoder evidence.

The optimized R8 release was verified separately for cold launch and synthetic
H.264/AAC playback, pause and resume; that smoke test is not IAMF decoder evidence.
Its native library bytes match the tested native snapshot. The attempted release
instrumentation runner failed before tests because of a tracing dependency omitted
after R8 optimization; no release-native instrumentation success is claimed.

Static 16 KiB alignment is verified. Runtime decoding was tested on a 4 KiB-page
emulator, so it does not establish runtime behavior on a 16 KiB-page device or on
physical TV hardware.
