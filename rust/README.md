# Nuvio Rust core

Shared logic for the Android TV app and Nuvio Desktop, exposed to Kotlin through
[UniFFI](https://mozilla.github.io/uniffi-rs/). The Compose UI, Media3 player,
libtorrent engine and DataStore stay in Kotlin; this workspace owns pure logic
and network I/O only, and holds no persistent state.

Scope, phase status, the plugin-engine decision and open risks are in
[MIGRATION.md](./MIGRATION.md).

## Layout

| Crate | Purpose |
| --- | --- |
| `nuvio-core` | The only FFI surface. Adds exported functions and composes the logic crates. |
| `uniffi-bindgen` | Binding generator binary (Kotlin). |

Logic crates (`nuvio-net`, `nuvio-addon`, `nuvio-plugin`, `nuvio-track`,
`nuvio-progress`) are added to the workspace as their migration phase starts, so
the workspace never carries empty placeholders.

## Commands

```bash
cargo test --workspace
cargo clippy --workspace -- -D warnings
```

Regenerate the Kotlin bindings after changing anything exported from `nuvio-core`:

```bash
./gradlew :app:generateRustBindings
./gradlew :app:checkRustBindings   # CI gate: fails if the committed copy is stale
```

Cross-compile for Android (requires `cargo-ndk`, the NDK, and the four Android
Rust targets — `rust-toolchain.toml` lists them):

```bash
./gradlew :app:cargoBuildAndroid
```

`src/main/jniLibs` then holds `libnuvio_core.so` per ABI and the normal Android
build picks it up. The Android build only runs this automatically when
`-PnuvioRustCore=true` is passed, so a plain checkout still builds without a Rust
toolchain.

## Constraints worth remembering

- `.cargo/config.toml` links every Android target for 16 KB pages. Play rejects
  uploads whose `.so` files are not 16 KB aligned.
- `uniffi.toml` pins the Kotlin package (`com.nuvio.tv.core.rust`) and cdylib
  name (`nuvio_core`); changing either breaks the generated bindings silently.
- The generated bindings are committed. Do not hand-edit them.
