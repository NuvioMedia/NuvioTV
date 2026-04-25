<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="OmnioTV" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Stremio addons • Android TV optimized • Playback-focused experience
  </p>

</div>

## About

OmnioTV is a modern media player designed specifically for Android TV, built with Kotlin and Jetpack Compose (TV Material3).

It acts as a client-side playback interface that integrates with the Stremio addon ecosystem for content discovery and source resolution through user-installed extensions.

## Features

- **Playback** — forked ExoPlayer core (bundled as AARs in [app/libs/](app/libs/)) with FFmpeg, AV1/libgav1, IAMF, and MPEG-H decoders; [mpv](https://mpv.io/) available as an alternate engine.
- **Subtitles** — SRT/VTT/PGS plus ASS/SSA via [peerless2012/ass-media](https://github.com/peerless2012/ass-media).
- **Stremio addons** — manifest-based catalog/stream/meta addons, ordered and reorderable per profile.
- **JS plugins** — QuickJS-backed plugin runtime with `Jsoup`, `Gson`, and `crypto-js` available to plugin code. Addons can be configured from a phone via a QR code served by an in-app web server.
- **Multi-profile** — per-profile addons/plugins/library/settings synced via self-hosted Supabase.
- **Integrations** — Trakt (scrobble, library, watch progress), TMDB, an internal `aiometadata` Fly.io service, and optional Emby (username/password sign-in, activity log, playback reporting).
- **Collections** — home-screen collections (ported from upstream Nuvio).
- **In-app updater** — driven by GitHub Releases.

## Installation

Download the latest APK from [GitHub Releases](https://github.com/TheMrClaus/OmnioTV/releases/latest) and sideload onto your Android TV device.

Release builds ship per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) plus a universal APK.

## Repository layout

```
app/                — Android TV application (Kotlin + Compose, single module)
baselineprofile/    — Macrobenchmark module that generates baseline profiles
web/panel/          — Next.js account panel (account.omnio.tv) — v2 full edit
web/tv-login/       — Next.js TV pairing flow (app.omnio.tv/tv-login)
supabase/           — Postgres migrations and Edge Functions
infra/aiometadata/  — Fly.io deployment config for the metadata service
dev-setup/          — macOS one-shot scripts for emulator-based dev
scripts/            — Release, baseline-profile, and perf tooling
```

## Development

### Prerequisites

- **JDK 17** (AGP 8.13 refuses 11)
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0`
- **Gradle wrapper 8.13** (bundled)

On macOS, [dev-setup/setup-omniotv-dev.sh](dev-setup/setup-omniotv-dev.sh) installs all of the above idempotently. See [dev-setup/README.md](dev-setup/README.md) for the full emulator workflow.

### Build & install

```bash
git clone https://github.com/TheMrClaus/OmnioTV.git
cd OmnioTV

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # install on connected device/emulator
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lint                   # Android lint
```

Run a single test class:

```bash
./gradlew :app:testDebugUnitTest --tests "com.omnio.tv.SomeTestClass"
```

Beta releases are produced by [scripts/release_beta.py](scripts/release_beta.py) or the `Beta Release` GitHub Actions workflow.

### Secrets & BuildConfig

Secrets (Supabase, Trakt, TMDB, internal service URLs) are injected into `BuildConfig` from `local.properties` (release) or `local.dev.properties` (debug), with env vars taking precedence. Templates: [local.example.properties](local.example.properties) and [local.properties.example](local.properties.example).

A debug build compiles and boots with most keys blank — the app just won't fully function.

## Companion apps

- **[web/panel/](web/panel/)** — Next.js 15 account control panel at **[account.omnio.tv](https://account.omnio.tv)**. Edits every domain the TV app exposes (profiles, addons, plugins, integrations, collections, home layout, playback, linked devices) via the same self-hosted Supabase backend.
- **[web/tv-login/](web/tv-login/)** — Next.js pairing flow at `app.omnio.tv/tv-login` that approves TV sign-in sessions via the `approve_tv_login_session` Supabase RPC.

## Legal & DMCA

OmnioTV functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

OmnioTV is not affiliated with any third-party extensions or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our **[Legal & Disclaimer Page](https://tapframe.github.io/NuvioTV/#legal)**.

## Built With

- Kotlin, Jetpack Compose, TV Material3
- Forked ExoPlayer (AARs in `app/libs/`) + mpv alternate engine
- Hilt (DI), Retrofit + OkHttp + Moshi, Coil
- QuickJS-kt (plugin runtime), NanoHTTPD (in-app addon config server)
- Supabase (auth + postgrest + sync), Trakt, TMDB, Emby
- Next.js 15 + Tailwind (companion web apps)

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR. This project does **not** accept new major features, large UX redesigns, cosmetic-only changes, or speculative refactors via PR — non-trivial features require maintainer approval in an issue first. Translation-only PRs are welcome.

## Star History

<a href="https://www.star-history.com/#TheMrClaus/OmnioTV&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=TheMrClaus/OmnioTV&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=TheMrClaus/OmnioTV&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=TheMrClaus/OmnioTV&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/TheMrClaus/OmnioTV.svg?style=for-the-badge
[contributors-url]: https://github.com/TheMrClaus/OmnioTV/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/TheMrClaus/OmnioTV.svg?style=for-the-badge
[forks-url]: https://github.com/TheMrClaus/OmnioTV/network/members
[stars-shield]: https://img.shields.io/github/stars/TheMrClaus/OmnioTV.svg?style=for-the-badge
[stars-url]: https://github.com/TheMrClaus/OmnioTV/stargazers
[issues-shield]: https://img.shields.io/github/issues/TheMrClaus/OmnioTV.svg?style=for-the-badge
[issues-url]: https://github.com/TheMrClaus/OmnioTV/issues
[license-shield]: https://img.shields.io/github/license/TheMrClaus/OmnioTV.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
