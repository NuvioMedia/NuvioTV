# Current Release playback validation — 2026-10-09

MiTV-AFMU0, armeabi-v7a, four Cortex-A55 cores; display 1920x1080 at 120 Hz.
Existing 1080p AVC High10 Episode07 MKV, 23.976 fps, reused locally. Source SHA256:
`CB53ADA3E1460F1FC69792B39C04607958B0FBA8FC77228FD9B63020785F978F`.
Retained configuration: four FFmpeg frame workers, sixteen decoded outputs,
Native Memory off. Saved playback preferences remained unchanged across runs.

Measured minified Full Release test-identity APK SHA256:
`E5990BB0AE55B175A5ABE01B6512641DB4E392AE017328AC19FDBFAE39D96590`.
Audio SO SHA256 (unchanged from preceding isolated Release):
`5D371B32F9B72C3504F38F377E5055BD74F81CB6408D79FC541C28405B849FEF`.
High10 SO SHA256:
`CD9F8C4E55B7292C78EE8EEA93518A4F8849E1B9D5402C3A5470DBC186C39EBF`.

## Three completed 42–55 second traversals

| Run | Boundary positions ms | Monotonic duration s | Successful submissions | Media3 drops | Submission FPS | App CPU % |
|---|---|---:|---:|---:|---:|---:|
| R1 | 42003 → 55007 | 13.000573 | 312 | 0 | 23.99894 | 249.20 |
| R2b | 41999 → 54999 | 13.000166 | 312 | 0 | 23.99969 | 249.51 |
| R3 | 42006 → 55001 | 12.999861 | 312 | 0 | 24.00026 | 248.36 |

Aggregate: zero drops in 936 successful submissions; window-local drop percentage
0%. Input drops, skipped inputs and skipped outputs did not increase in any
window. No output-drop streak is possible within a zero-drop window.
CPU 100% means one core, not the entire four-core device.

Lifetime drops were already 42 / 49 / 34 at the first boundary and did not increase
during the burst. Lifetime max streak counters were 3 / 4 / 3. These are not
window-local counters and are not presented as zero. The pre-window drops occurred
before sampling in the instrumented cold-open/seek sequence; their precise cause
has not been attributed. This is not a claim of zero drops throughout an episode.

R2b fresh HAL temperatures before/after capture: 40.1 / 40.9°C; R3: 41.0 / 40.0°C;
thermal status 0. R1's actual successful start was not timestamped with a stored
HAL sample. No burst peak/frequency series was captured in this bounded check.
These are current validation runs, not a new thermally matched optimization A/B.

## Measurement method and limitations

An external, framework-only instrumentation APK reads existing Media3
DecoderCounters from the unchanged installed Release process via reflection.
It seeks to 35s, anchors the content clock before 40s, then takes two snapshots
scheduled on the application looper for content 42s and 55s. No native profiling,
production code changes, per-frame polling/logging, queue, or decoder overlap was
introduced. Only two boundary snapshots occur during the measured window.

Actual elapsed timestamps and content positions are reported; all observed
boundary errors were at most 7 ms. The observer accepts at most 100 ms error and
checks playing state and renderer identity at both boundaries. It is not the
historical per-event probe: it does not exhaustively observe every intermediate
seek/generation/flush. No remote input was sent during these three windows.
Decoded-completion FPS and same-PTS latency cannot be recovered from these
counters and are not claimed. Successful Surface submission is not confirmed
physical display presentation. No formal observer-overhead A/B was performed;
the observed zero-drop result includes the observer's small boundary work.

An initial AndroidX-runner attempt failed before measurement because Debug test
dependencies expected a tracing class eliminated/renamed in the minified target.
The framework-only observer avoids those dependencies without changing Release.
R2 did not open the movie before its timeout and was excluded; R2b replaced it.
Invalid/failed attempts were not divided by 13 seconds or included in the table.

## Focused correctness checks

Debug and Release decoder unit-test tasks passed (21 tests each). Full Debug
and focused AndroidTest builds passed. On Xiaomi, the recreated-Surface test
passed three native-generation replacements of the identical Java Surface,
with PixelCopy confirming the first white frame each time. Progress-fill test
passed color/zero-value checks and no additional composition or measurement.
The progress test was repeated separately immediately before its commit.

All eleven JNI registrations survived actual minified APK DEX verification.
The MC source's only cleanup was a trailing empty line; every ARM instruction
byte/disassembly in the rebuilt Release MC object matched its retained object.
Final Full Debug, focused AndroidTest, and minified Full Release assembly completed
with exit 0. Final installed test APK SHA256:
`363BB45FDBF3D66572159DA385131E4B64F896C00A669C83D9F4E66EE296DE58`.
Every non-META-INF entry's content matches the measured Release APK; the entire
High10 SO remains byte-identical, including the SHA256 shown above. APK container/
signature bytes differ; no playback implementation changed between these packages.

The final Release also passed three real-movie Home/return cycles while paused
at approximately 15:34. Screenshots retained the decoded picture each time;
the UI still showed Play afterward. Subtitle text was visible. This is not a
frame-accurate ASS animation or audible A/V-sync test. No relevant abandoned-window,
Surface-render failure or fatal-exception message appeared in the checked log tail.

Historical matched scalar/NEON evidence and correctness hashes are recorded in
`armv7-high10-mc.md`; do not relabel those measurements as current Release results.

No new speculative decoder optimization was retained. Whole-player UI speed was
not established by a controlled UI A/B; the progress change proves phase-local
work reduction. Official Nuvio and other forks were not replaced. No push or PR.
