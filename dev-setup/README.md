# OmnioTV — Local Dev Environment

A one-shot setup so you can build OmnioTV and run it on an Android TV emulator
on your Mac, without diving into Android Studio's GUI installer.

## Prerequisites

- macOS (Apple Silicon recommended; Intel works but the emulator is slower)
- Homebrew installed (`brew --version` should print a version)
- ~10 GB free disk space (Android Studio + SDK + system images add up fast)

## One-time setup

From the project root:

```bash
bash dev-setup/setup-omniotv-dev.sh
```

This installs and configures:

| Component                 | Why                                            |
| ------------------------- | ---------------------------------------------- |
| Eclipse Temurin JDK 17    | AGP 8.13 refuses to run on JDK 11              |
| Android Studio            | IDE (you can keep using it for debugging/UI)   |
| Android command-line tools| Headless `sdkmanager` and `avdmanager`         |
| `platforms;android-36`    | Project's `compileSdk` is 36                   |
| `build-tools;36.0.0`      | Matches the platform                           |
| `platform-tools`          | Provides `adb`                                 |
| `emulator`                | The QEMU-based device emulator                 |
| Android TV API 34 (ARM64) | The system image you boot the emulator into   |
| AVD `OmnioTV_TV_API34`    | A ready-to-launch Android TV virtual device    |

The script is **idempotent** — re-running it is safe. It will skip anything
that's already installed.

After it finishes, **open a new terminal** so the new `ANDROID_HOME`,
`JAVA_HOME`, and `PATH` entries from `~/.zshrc` are picked up.

## Daily use

### 1. Start the Android TV emulator

```bash
bash dev-setup/launch-tv-emulator.sh
```

Leave that terminal open — closing it shuts down the emulator. The first boot
takes a minute or two; subsequent boots are faster.

### 2. Build & install OmnioTV onto the running emulator

In a second terminal:

```bash
bash dev-setup/build-and-install.sh
```

This runs `./gradlew :app:installDebug` and then launches the app via `adb`.

### 3. (Optional) Open the project in Android Studio

```bash
open -a "Android Studio" .
```

Android Studio will detect the SDK path from `ANDROID_HOME` automatically. It
will also offer to import the Gradle wrapper (Gradle 8.13) on first open.

## What about secrets?

The build expects a `local.properties` (or env vars) for things like Supabase
URLs, TMDB keys, etc. See `local.example.properties` and
`local.properties.example` in the project root. **For a debug build, you can
leave most of them blank** — the app won't fully work but will compile and
boot, which is enough to verify the toolchain.

If you want to enable the dev backend instead of release, copy values into a
new `local.dev.properties` file (same format).

## Troubleshooting

| Symptom                                        | Fix                                                                   |
| ---------------------------------------------- | --------------------------------------------------------------------- |
| `command not found: adb` after setup           | Open a new terminal — `~/.zshrc` only re-loads on shell start         |
| `Unsupported class file major version`         | Run `java -version` — should report 17. If not: `export JAVA_HOME=...`|
| Emulator boots to a black screen for >2 min    | `bash dev-setup/launch-tv-emulator.sh` again with `-wipe-data` (edit) |
| Gradle build fails with "SDK location not found" | Create `local.properties` with `sdk.dir=$ANDROID_HOME` value        |
| `adb` shows no devices                         | Make sure the emulator window finished booting (Android logo gone)    |

## File layout

```
dev-setup/
├── README.md                 ← this file
├── setup-omniotv-dev.sh      ← run once
├── launch-tv-emulator.sh     ← daily: start the TV emulator
└── build-and-install.sh      ← daily: build + push to emulator
```
