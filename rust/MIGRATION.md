# Nuvio core migration

Shared logic for the Android TV app and Nuvio Desktop, moving into Rust behind a
UniFFI boundary. Phase 0 (scaffolding) has landed; the phases below describe what
follows.

## Why a Rust core, and why not a GPUI rewrite

The app is 1,374 Kotlin files / ~298k lines. The UI is the bulk of it: 444 files
and ~160k lines of Compose, with 122 files using `FocusRequester` and 160 files
on `androidx.tv.material3`. That UI is the product and it does not port.

What does port is the logic, and it is currently written twice — once in `app/`
and again in `_source/NuvioDesktop/composeApp`, which holds ~361 `commonMain`
files of near-identical repositories, models and addon-protocol code.

A GPUI rewrite was evaluated and rejected:

- GPUI has no first-party Android or iOS target; mobile exists only as early
  community work (`gpui-mobile` describes itself as early development).
- It has no TV focus/DPAD model. That is the single most product-defining part of
  a TV app and would be rebuilt from scratch.
- It is consumed as a git dependency tracking Zed, not a versioned release.
- It renders through blade/wgpu; cheap Android TV boxes are GLES-only with stale
  drivers — debugging Compose currently insulates us from.
- There is no Media3/ExoPlayer equivalent, so the DoVi bridge, the ffmpeg decoder
  modules and libass integration would all be redone on libmpv + NDK MediaCodec.
- CloudStream extensions are JVM bytecode loaded via `DexClassLoader`. A Rust
  process cannot load dex.

So: Rust owns logic and network I/O, Compose keeps the UI, Media3 keeps playback.

## Scope

| Stays in Kotlin / C++ | Reason |
| --- | --- |
| `ui/**` (444 files) | Compose + TV focus model |
| `core/player/**`, Media3, `DV7/`, `ffmpeg-decoder-downmix/` | ExoPlayer + DoVi JNI |
| `core/plugin/cloudstream/**` | Requires ART (`DexClassLoader`) |
| `libs/lib-nuvio-engine-android-*.aar` | Already native (C++/libtorrent), working |
| `data/local/**` | Preferences DataStore, SharedPreferences, AndroidKeyStore |
| `core/di/**`, `core/server/**` | Hilt wiring and the Android-bound config servers |

Moves to Rust: addon protocol, repository logic, the plugin host, tracker engines
(Trakt / Simkl / MDBList), and watch-progress computation.

**Architectural rule:** the core is stateless and is never the store of record.
State enters and leaves as UniFFI values, which keeps every phase independently
revertible.

## Phases

| Phase | Content | Status |
| --- | --- | --- |
| 0 | Workspace, UniFFI boundary, binding generation, Gradle + CI wiring | **done** |
| 1 | Plugin host: OXC policy gate + `rquickjs`, host functions, timeout/cancel | next |
| 2 | Addon protocol + transport (`nuvio-addon`, `nuvio-net`) | |
| 3 | Tracking engines (`nuvio-track`) | |
| 4 | Watch progress (`nuvio-progress`) | |

Each phase ships in **shadow mode**: Kotlin and Rust both compute, divergences are
logged, and the Kotlin path is deleted only after a release with zero diffs.

## Phase 0 — what landed

- `rust/` workspace: `nuvio-core` (the only FFI surface) and `uniffi-bindgen`.
- `nuvio-core` exports `coreVersion()` and `suspend coreSelfCheck()` — one sync and
  one async call, proving both directions of the boundary before real work depends
  on it.
- `app/src/main/java/com/nuvio/tv/core/rust/nuvio_core.kt` — generated bindings,
  committed, package `com.nuvio.tv.core.rust`, cdylib `nuvio_core`.
- Gradle tasks: `cargoBuildAndroid`, `cargoBuildHost`, `generateRustBindings`,
  `checkRustBindings`. JNA is on the classpath (`@aar` for Android, plain jar for
  unit tests) and proguard keeps are in place.
- CI (`.github/workflows/rust-core.yml`): fmt, clippy, tests, a binding-drift gate,
  and an Android job that builds all four ABIs and fails if any `PT_LOAD` segment
  is not 16 KB aligned.
- uniffi is pinned to `0.29`: 0.32's dependency tree requires rustc 1.91, and
  `rust-toolchain.toml` pins the 1.88 toolchain this project builds with.

## Plugin engine: `rquickjs` over QuickJS-NG

Measured release builds, same machine, with the harness cross-checking that both
engines produce identical values on every workload:

| Workload | Boa 0.21.1 | QuickJS-NG | ratio |
| --- | ---: | ---: | ---: |
| fib(25) recursion | 150 ms | 17.0 ms | 8.9× |
| 5M loop sum | 1515 ms | 219 ms | 6.9× |
| 1M closure calls | 880 ms | 111 ms | 7.9× |
| 100k object alloc | 277 ms | 46.8 ms | 5.9× |
| 20k array sort | 259 ms | 31.9 ms | 8.1× |
| **total** | **3175 ms** | **438 ms** | **7.3×** |
| cold init | 0.52 ms | 0.18 ms | 2.9× |

QuickJS-NG wins by 5.3–12.4× per workload and ~2.5× on cold start; a scraper run
pays init cost every time and TV hardware has far less headroom than the desktop
used for the measurement. Boa was pinned to 0.21.1 (0.22 needs rustc 1.91), so its
numbers are already a floor — the ranking holds.

`deno_core`/`rusty_v8` and `mozjs` were rejected on binary size, memory, and the
no-JIT requirement of the iOS desktop build.

Open item: `quickjs-kt-android-1.0.5-nuvio.aar` is a **patched** QuickJS fork.
Diff its patches against QuickJS-NG and reapply anything that matters before
switching engines.

## Security findings from the survey

These are recorded because they shape Phase 1, not because they are fixed:

- `PluginRuntime` exposes `__get_tmdb_api_key()` to plugin JS, so
  `BuildConfig.TMDB_API_KEY` reaches third-party scraper code. Phase 1 removes the
  global and injects the key host-side instead.
- `PluginSafety` — the only trust gate for scrapers — is a substring match on
  `"videasy"`. OXC's AST + scope analysis replaces it.
- Nothing verifies signatures or checksums on downloaded JS or `.cs3` extensions.
- The `@Named("addonPermissive")` OkHttp client trusts all certificates. That is
  deliberate (self-hosted addons), but the Rust port must replicate it and keep it
  scoped to addon traffic only.

## Verify

```bash
cd rust && cargo test --workspace && cargo clippy --workspace -- -D warnings
./gradlew :app:generateRustBindings   # after changing exported functions
./gradlew :app:checkRustBindings      # CI gate
./gradlew :app:cargoBuildAndroid      # requires cargo-ndk + NDK
```

A plain checkout builds without a Rust toolchain: the Android build only runs
`cargoBuildAndroid` when `-PnuvioRustCore=true` is passed.

## Known gaps

- Android cross-compilation is verified in CI only; the machine this landed on had
  no `cargo-ndk` and only the host target installed.
- A full `:app:compileFullDebugKotlin` was not completed locally (killed during
  KSP/Hilt warm-up). The bindings only add JNA imports, but this should be
  confirmed on a normal developer machine or in CI.
- `jsoup` vs `html5ever` selector differences are unmeasured. Phase 1 stages the
  `__cheerio_*` bridge over a UniFFI callback so Jsoup keeps serving the DOM until
  the Rust implementation passes the scraper corpus.
