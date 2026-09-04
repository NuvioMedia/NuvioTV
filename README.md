<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio TV" width="320" />

  <h3>The Ultimate Open-Source Media Player for Android TV & Mobile</h3>

  <p>
    Bring your media sources to life with artwork, rich metadata, subtitle management, and seamless cross-device playback.
  </p>

  <p>
    <a href="https://github.com/TheAceOfficials/Nuvio-TV/stargazers"><img src="https://img.shields.io/github/stars/TheAceOfficials/Nuvio-TV?style=for-the-badge&color=8A2BE2" alt="Stars"/></a>
    <a href="https://github.com/TheAceOfficials/Nuvio-TV/network/members"><img src="https://img.shields.io/github/forks/TheAceOfficials/Nuvio-TV?style=for-the-badge&color=4169E1" alt="Forks"/></a>
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=for-the-badge" alt="License"/></a>
    <img src="https://img.shields.io/badge/Platform-Android_TV_%7C_Mobile-green.svg?style=for-the-badge" alt="Platform"/>
    <img src="https://img.shields.io/badge/Kotlin-Compose_TV-orange.svg?style=for-the-badge" alt="Kotlin Compose"/>
  </p>

</div>

---

## 🔥 What's New in This Fork (Exclusive Features)

This fork introduces custom enhancements that elevate the viewing experience beyond the upstream project:

### 📺 Apple TV-Inspired Smart Subtitles Automation & Premium Sidebar
*Missed a line of dialogue or muted your TV? Subtitles got you covered!*

Inspired by the polished user experience of **Apple TV (tvOS)**, NuvioTV includes:

* **Apple TV-Style Premium Glassmorphism Sidebar**: Ultra-clean side navigation featuring fluid translucent glassmorphism blur panels, borderless floating focus states, and seamless tab transitions.
* **Auto-Enable on Rewind**: Automatically turns on subtitles whenever you seek backward (rewind) if subtitles are disabled.
* **Auto-Enable on Mute**: Automatically shows subtitles when audio is muted or volume is set to 0. Unmuting smoothly restores original subtitle status.
* **Smart Accumulated Duration**: Rewinding multiple times (20s, 30s+) dynamically accumulates and extends subtitle display time to match your rewind distance.
* **Minimal Bottom-Right Indicator**: Displays a clean, minimal **"Rewind Subtitle On"** or **"Subtitles On (Muted)"** badge in the bottom-right corner.
* **Dynamic Player Control Shift**: The notification badge smoothly lifts upward when player controls are active so it never overlaps seek bars or controls.
* **Smart Language Selection**: Automatically selects your preferred subtitle language or the best available track.
* **User Customizable**: Toggle features under **Settings**.

---

## ✨ Key Features

- **📱 Designed for Big Screens & Mobile**: Built natively using **Jetpack Compose for TV** and **Material 3**.
- **⚡ Dual Video Engines**: Seamless integration of **libmpv** (for hardware acceleration & complex subtitle rendering) and **AndroidX Media3 / ExoPlayer**.
- **💬 Advanced Subtitle Support**: Full support for ASS/SSA styling via `libass`, sidecar addon subtitles (OpenSubtitles / Stremio addons), custom timing offset, and outline customization.
- **🔊 Audio Features**: Multi-channel downmixing, audio track selection, dynamic audio amplification, and offset synchronization.
- **🎨 Glassmorphism & Modern UI**: Smooth motion design, dynamic preview scrubbing, TV remote navigation, and custom controls.

---

## 🛠️ Build from Source

### Prerequisites
- **Android Studio** (Ladybug or newer recommended)
- **JDK 17** or higher
- **Android SDK** (API level 34+)

### Build Commands

Clone the repository and build the debug APKs:

```bash
# Clone the repository
git clone https://github.com/TheAceOfficials/Nuvio-TV.git
cd NuvioTV

# Build Android TV / Mobile Debug APK
./gradlew :app:assembleFullDebug
```

Output APK location:
`app/build/outputs/apk/full/debug/app-full-debug.apk`

---

## 📄 License

Distributed under the **GNU General Public License v3.0**. See [`LICENSE`](./LICENSE) for details.
