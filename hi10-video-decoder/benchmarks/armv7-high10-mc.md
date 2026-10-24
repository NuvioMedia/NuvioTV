# ARMv7 High10 motion-compensation evidence

Validated implementation: coherent 8/16-wide High10 qpel put/average family,
dispatched once for bit depth 10 and NEON. FFmpeg 7.0.2; ARMv7 on four Cortex-A55
cores (MiTV-AFMU0). Four frame workers, sixteen output buffers, Native Memory off,
1920x1080 at 120 Hz, Episode07 source at 23.976 fps, 42–55 second burst.
Audio/source/subtitle settings were held constant within each comparison.
The original libavcodec initializer runs first; other depths and smaller blocks
retain original dispatch. Chroma MC, decoder threading and swscale are unchanged.

## Historical matched playback measurements, 2026-10-03

First three pairs used interpolation from five-second telemetry; these rates
are estimates, not exact window event counts.

| Pair | Decoded FPS, scalar → NEON | Submitted FPS, scalar → NEON | Drop %, scalar → NEON |
|---|---|---|---|
| 1 | 20.14 → 23.10 | 8.96 → 22.85 | 61.3 → 4.2 |
| 2 | 19.84 → 21.02 | 10.03 → 18.89 | 55.6 → 16.8 |
| 3 | 19.71 → 23.41 | 9.78 → 23.99 | 57.3 → 0 |

Additional interleaved pairs used a fixed monotonic 13-second counter window.
All scalar runs triggered decoder flush/recovery and invalidated the continuous
window: no exact full-window scalar FPS/drop percentage is claimed.

| Pair | NEON completions/submissions/drops | App CPU %, scalar → NEON | Send p95 ms, scalar → NEON |
|---|---|---|---|
| A4/B4 | 311 / 312 / 0 | 309.72 → 274.93 | 114.108 → 55.907 |
| A5/B5 | 310 / 312 / 0 | 306.16 → 270.66 | 112.278 → 55.914 |
| A7/B7 | 311 / 312 / 0 | 304.40 → 273.55 | 117.787 → 58.797 |

CPU 100% means one core. Scalar CPU spans include recovery and are not continuous
decode-only intervals. Send p95 samples are rolling telemetry near the burst,
not exact window-local latency distributions. Matched start temperatures used
current HAL readings, within 0.2°C; thermal trajectories are not identical.
NEON exact decoded FPS: 23.923 / 23.846 / 23.923; successful submissions: 24.000
each; output/input drops and local output-drop streak: zero in each valid window.
Submission does not prove physical display presentation.

Fresh exclusive sampled qpel/luma CPU decreased 15.061 → 8.878 CPU-seconds;
combined qpel/chroma decreased 19.388 → 14.510; worker samples 30.898 → 26.878.
Chroma's measured time increased; this is not a claim of optimized chroma.
Sampling was exclusive leaf attribution, not final inclusive native-stack cost.

## Correctness and scope

- 151,680 bit-exact differential cases used the actual APK CMake object.
- 1,320 decoded-frame hashes matched the scalar whole-stream manifest.
- Retained MC source SHA256:
  `241F32ED17E190091A4FE8EE0A3C3A88B6878708E5A144F96FB99DED2DD20D17`.
- Committed source SHA256 after removing one trailing empty line:
  `DEEAAD6E6ADDA2DD6889A3663CAADD8DAB113F0A021A45837E8DCE09F7B3F308`.
  All ARM instruction bytes/disassembly in the rebuilt Release MC object match
  the retained object's disassembly. No MC expression or dispatch was changed.
- Extended ASS-enabled playback: 37,353 successful submissions, 263 drops
  (0.699%); no observed persistent stall/crash/native-heap growth in that run.
- Current source is the retained implementation, not a new SIMD experiment.
  Current Android Release measurements must be reported separately from these
  historical Debug validation measurements.

The reproducible CMake archive hook copies the archive, renames only the original
initializer definition, preserves caller references and original text bytes,
and rejects missing/ambiguous definitions. The host archive-hook regression
checks stock and already-prepared archives. The internal qpel layout is hash-pinned;
this is not a portable public FFmpeg ABI or an upstream-ready patch.
