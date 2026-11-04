<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## What this fork adds

This fork of [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) adds these features on top of upstream:

### 🖼️ Seek bar thumbnails
- A preview frame shows above the seek bar while you scrub, so you can see where you will land.
- Frames come from a native frame decoder written in Rust. They are fetched from the stream only while you scrub, with no pre-generated sprite sheets needed.
- The thumbnail stays on screen for 3 seconds after you stop scrubbing.
- Turn it on in the Playback player settings: **Seek Bar Thumbnails**.

### 🎨 Dolby Vision Profile 5 → HDR10 tone mapping (experimental)
- Plays Dolby Vision Profile 5 content in correct colour on TVs and devices **without** Dolby Vision support, instead of the usual green/purple picture.
- The Dolby Vision RPU metadata is read per frame and mapped to HDR10 by the app's own GL renderer. The display is switched to HDR10 output.
- Works with MP4, TS and Matroska (MKV) sources, and with libass subtitles in Overlay mode.
- An on-screen status note tells you whether mapping is active, and if not, why: for example a missing GPU capability, a non-HDR10 display, or a display that already supports Dolby Vision.
- If the effect can't start, playback falls back cleanly instead of getting stuck.

### 📺 Live TV
- A full Live TV section with a channel list, live preview, and an EPG programme guide.
- Supports **M3U playlists**, **Xtream Codes** and **Stalker portal** providers, plus **XMLTV** guide data.
- Catch-up / archive playback for channels that support it.
- Manage multiple playlists and categories, with guide and channel lists cached for fast loading.
- Set up playlists from your phone through a local setup page, so you don't have to type URLs with a remote.
- Plays in the full-screen player with a dedicated Live TV overlay. Works with single-connection IPTV accounts.

### 📅 Release calendar
- A month-view calendar of upcoming and recent releases for the movies and shows in your library.
- Shows new episode air dates for the series you follow, so you know when the next episode drops.

### 💬 libass CPU blend render mode
- A new **Overlay (CPU Blend)** ASS render mode for ASS/SSA subtitles.
- Subtitles are blended into the overlay surface with NEON-optimised CPU code and use no GPU. This can run better on devices with weak GPUs, and it keeps HDR output.
- Choose it in the subtitle settings under **ASS Render Mode**.

### Other fixes
- HDR10+ metadata stripping now applies only to HEVC tracks, so H.264 streams (most Live TV channels) are no longer corrupted.
- Zoom and stretch now work during tunneled playback.

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK (upstream)](https://github.com/NuvioMedia/NuvioTV/releases/latest)
- [This fork's releases](https://github.com/chrisk325/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/chrisk325/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
