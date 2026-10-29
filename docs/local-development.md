# Local Android TV development

This records the setup tested on 2026-10-29 at upstream commit `d14832e1c477cca2b5613b893e590fd5fcab83ee`. Paths are specific to this machine.

## Host and toolchain

- Windows build 26300.9711, Ubuntu 26.04 LTS under WSL2 (x86_64), Git 2.53.0, GitHub CLI 2.102.0.
- WSL has 31 GiB RAM, 16 logical CPUs, about 941 GiB free on the Linux filesystem at setup time. The Windows drive had about 127 GiB free. The host has an AMD Ryzen 9 5950X and Radeon RX 6900 XT. WHPX reports that it is installed and usable. WSL exposes `/dev/dxg` but no `/dev/kvm`.
- Android Studio was not detected. Linux builds use Temurin JDK 17.0.21+9, Gradle wrapper 8.13, Android Gradle Plugin 8.13.2 and Kotlin 2.3.0. System Java 25.0.4.1 failed before configuration with `IllegalArgumentException: 25.0.4.1`; use JDK 17 as CI does.
- Linux Android SDK: `/home/schubmma/.local/share/nuvio-dev/android-sdk`, platform 36, build tools 35.0.0, platform tools 37.0.1, Android CLI 1.1.16717390 and NDK 29.0.14206865. `ffmpeg-decoder-downmix` triggers NDK installation even when the optional local FFmpeg build is not selected.
- Windows emulator SDK: `%LOCALAPPDATA%\NuvioDev\android-sdk`, with separate Temurin JDK 17.0.20.1+1. Keep the Windows and Linux SDK/JDK paths separate.
- `compileSdk`/`targetSdk` 36, `minSdk` 24; `full` and `playstore` flavors; `debug`, `release`, and `benchmark` build types. The bundled native AARs include x86_64, x86, arm64-v8a and armeabi-v7a. Optional Dolby Vision native build and local FFmpeg decoder have additional inputs.

## Build

```bash
cd /home/schubmma/NuvioTV
source /home/schubmma/.local/share/nuvio-dev/env.sh
./gradlew :app:assembleFullDebug --stacktrace
./gradlew :app:testFullDebugUnitTest --stacktrace
```

The env script selects the Linux JDK/SDK and sets `ORG_GRADLE_PROJECT_debuggable=true`, because this repository otherwise defaults debug builds to non-debuggable. `local.properties` is ignored by Git and holds `sdk.dir` and local signing overrides. Despite its name, the debug variant uses the release signing configuration; CI also overrides it with a development keystore. This machine has a separately generated debug key at `/home/schubmma/.local/share/nuvio-dev/debug.jks`. No production key or service credentials were needed to build or use guest mode. Optional service integrations require their own configuration; do not copy placeholder values from `local.example.properties` as secrets.

The baseline build completed successfully. Outputs include `app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk` and `app-full-universal-debug.apk`. The full debug package is `com.nuviodebug.com`, separate from release package `com.nuvio.tv` and Play Store package `com.nuvio.app`. AAPT identifies `com.nuvio.tv.launcher.AppIconDefault` as the launchable TV activity. APK signature verification succeeded. The final x86_64 APK (built from the commit above, with no playback source modifications) has SHA-256 `4ca85d233d9f2fc63040ed2dc12af4fa7bfb6adc2e9ad816b7973b8f70962218`.

## Windows Android TV emulator

The AVD is `Nuvio_Android_TV_API36`, running Android 16 on emulator 37.2.12.0, using `system-images;android-36;android-tv;x86_64`. Run on Windows, using the dedicated SDK:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\NuvioDev\android-sdk"
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd Nuvio_Android_TV_API36 -port 5584 -no-snapshot -gpu auto
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5584 install -r "$env:LOCALAPPDATA\NuvioDev\app-full-x86_64-debug.apk"
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5584 shell am start -n com.nuviodebug.com/com.nuvio.tv.launcher.AppIconDefault
```

Copy the current x86_64 APK to the Windows path before installation. Wait for `adb shell getprop sys.boot_completed` to return `1`: an install attempted earlier failed with `device is still booting`. This AVD does not replace a production installation.

The latest Windows `sdkmanager.bat` shim split semicolon-separated system-image names into separate packages in this environment. Calling the new CLI directly with the slash-separated package worked: `android.exe --sdk=<Windows SDK path> sdk install system-images/android-36/android-tv/x86_64`. Then `avdmanager.bat create avd` created the device. Hardware acceleration was reported available by `emulator.exe -accel-check`.

## Debugging and privacy

Guest mode, home rendering, network artwork, D-pad navigation, addon installation and player launch were exercised on the AVD. The AVD cannot decode the selected 10-bit HEVC release with Media3 and Nuvio offers its MPV fallback. The package manifest marks this build debuggable. A JDWP handshake through a Windows ADB port forward succeeded; a WSL localhost connection to the Windows forward was refused, so attach from Windows or configure host connectivity.

The upstream app can log full addon and playback URLs in debug mode. These may contain private configuration and signed tokens. During this investigation the dedicated emulator was set to suppress ordinary tags (`adb shell setprop log.tag S`), with a specific diagnostic tag enabled. Do not share unfiltered logcat or diagnostics without review and redaction. Restore normal logging on this disposable emulator when finished with `adb shell setprop log.tag ''`. Use `adb logcat -s <safe-tag>` only after checking that the selected messages never contain credentials, private identifiers, or signed URLs.

Gradle tasks include `:app:testFullDebugUnitTest` and `:app:connectedFullDebugAndroidTest`; the latter needs a running device. The repository also contains `app/src/test`, `app/src/testFull`, and `app/src/androidTest`.
