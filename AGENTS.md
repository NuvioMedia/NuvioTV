# AGENTS.md — NuvioTV Contributor Guide

> This file is intended for both **human contributors** and **AI coding agents** working on the NuvioTV codebase. It describes the project architecture, conventions, build instructions, and contribution guidelines.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Package Structure](#3-package-structure)
4. [Key Technologies](#4-key-technologies)
5. [Build & Run](#5-build--run)
6. [Configuration (local.properties)](#6-configuration-localproperties)
7. [Coding Conventions](#7-coding-conventions)
8. [Navigation Model](#8-navigation-model)
9. [Dependency Injection (Hilt)](#9-dependency-injection-hilt)
10. [Plugin System](#10-plugin-system)
11. [Data Flow](#11-data-flow)
12. [Testing](#12-testing)
13. [Common Pitfalls](#13-common-pitfalls)
14. [Contribution Policy](#14-contribution-policy)

---

## 1. Project Overview

NuvioTV is a modern Android TV media player built on **Kotlin + Jetpack Compose for TV**. It acts as a pure client-side interface — it does not host or distribute any content. Media sources come from two integration points:

- **Stremio addons** — HTTP-based extensions that expose catalogs and stream links via a standardised JSON API.
- **Local JS plugins (scrapers)** — JavaScript files executed in-app via a QuickJS runtime; fetched from user-hosted plugin repositories.

The app is a **single-module** Android project (`app/`) with a clean layered architecture.

---

## 2. Architecture

NuvioTV follows an **MVVM + Clean Architecture** pattern:

```
UI Layer        →  Screens (Composables) + ViewModels
Domain Layer    →  Repository interfaces + Domain Models
Data Layer      →  Repository implementations + Remote/Local data sources
Core Layer      →  Cross-cutting services: auth, sync, plugin engine, networking, etc.
```

**State management** uses Kotlin `StateFlow`/`Flow` for reactive UI updates.  
**Side effects** are managed in ViewModels using `viewModelScope` coroutines.  
**Single source of truth** for settings and user data lives in `DataStore<Preferences>` (no Room database — all persistent state is in DataStore or Supabase).

---

## 3. Package Structure

All source files live under `app/src/main/java/com/nuvio/tv/`.

```
com.nuvio.tv
│
├── NuvioApplication.kt       # @HiltAndroidApp entry point; Coil image loader setup
├── MainActivity.kt           # Single Activity; hosts the Compose NavHost
├── ModernSidebarBlurPanel.kt # Reusable sidebar panel used in the main layout
│
├── core/                     # Cross-cutting, non-UI infrastructure
│   ├── auth/                 # AuthManager — Supabase session + auth state Flow
│   ├── di/                   # Hilt modules: NetworkModule, RepositoryModule,
│   │                         #   ProfileModule, SupabaseModule
│   ├── network/              # IPv4FirstDns (prefers IPv4), NetworkResult sealed class,
│   │                         #   SafeApiCall extension
│   ├── player/               # ExternalPlayerLauncher, FrameRateUtils,
│   │                         #   OpenSubtitlesHasher, StreamAutoPlayPolicy/Selector
│   ├── plugin/               # PluginManager (orchestrates JS scrapers),
│   │                         #   PluginRuntime (QuickJS executor)
│   ├── profile/              # ProfileManager — multi-profile selection & state
│   ├── qr/                   # QrCodeGenerator (used for TV sign-in flow)
│   ├── server/               # NanoHTTPD local servers for addon config & repo config
│   │                         #   (AddonConfigServer, RepositoryConfigServer)
│   ├── sync/                 # Background sync services:
│   │                         #   AddonSyncService, LibrarySyncService,
│   │                         #   PluginSyncService, ProfileSyncService,
│   │                         #   StartupSyncService, WatchProgressSyncService,
│   │                         #   WatchedItemsSyncService
│   ├── tmdb/                 # TmdbService (API client), TmdbMetadataService
│   └── util/                 # ReleaseInfoUtils
│
├── data/                     # Data layer: sources, repos, mappers
│   ├── local/                # DataStore wrappers (one per feature area):
│   │                         #   AddonPreferences, PlayerSettingsDataStore,
│   │                         #   ProfileDataStore, PluginDataStore,
│   │                         #   WatchProgressPreferences, WatchedItemsPreferences,
│   │                         #   TraktAuthDataStore, ThemeDataStore, etc.
│   ├── mapper/               # Pure mapping functions: AddonMapper, CatalogMapper,
│   │                         #   MetaMapper, MetadataFieldMappers, StreamMapper
│   ├── remote/               # Retrofit API interfaces (Stremio addon endpoints)
│   ├── repository/           # Implementations of domain repository interfaces +
│   │                         #   Trakt services (auth, library, progress, scrobble),
│   │                         #   MDBListRepository, ImdbEpisodeRatingsRepository,
│   │                         #   ParentalGuideRepository, SkipIntroRepository, etc.
│   └── trailer/              # In-app YouTube trailer extraction + chunked playback
│
├── domain/                   # Business logic contracts (pure Kotlin, no Android deps)
│   ├── model/                # Addon, Meta, MetaPreview, Stream, Subtitle, Plugin,
│   │                         #   WatchProgress, WatchedItem, UserProfile, CatalogRow,
│   │                         #   LibraryModels, AppTheme, HomeLayout, etc.
│   └── repository/           # Repository interfaces: AddonRepository, MetaRepository,
│                             #   StreamRepository, SubtitleRepository,
│                             #   CatalogRepository, LibraryRepository,
│                             #   WatchProgressRepository, SyncRepository
│
├── ui/                       # Presentation layer
│   ├── components/           # Shared Composable components (buttons, cards, overlays…)
│   ├── navigation/           # Screen.kt (sealed route definitions), NuvioNavHost.kt
│   ├── screens/              # One sub-package per screen/feature:
│   │   ├── home/             # Home screen (3 layout variants: Classic, Grid, Modern)
│   │   ├── detail/           # MetaDetailsScreen — media detail page with episodes,
│   │   │                     #   cast, MoreLikeThis, ratings, etc.
│   │   ├── player/           # PlayerScreen + all player overlay composables
│   │   │                     #   (controls, audio/subtitle, skip intro, binge prompt…)
│   │   ├── stream/           # StreamScreen — source selection before playback
│   │   ├── search/           # Search screen + ViewModel
│   │   ├── discover/         # Discover/browse screen
│   │   ├── library/          # Library screen + ViewModel
│   │   ├── settings/         # Settings screens (playback, theme, Trakt, TMDB, etc.)
│   │   ├── addon/            # AddonManagerScreen + CatalogOrderScreen
│   │   ├── account/          # Auth, QR sign-in, profile management, sync codes
│   │   ├── cast/             # CastDetailScreen — actor/crew filmography
│   │   └── plugins/          # Plugin/scraper management screens
│   ├── theme/                # Compose Theme (colors, typography, font selection)
│   └── util/                 # UI helpers (FocusUtils, etc.)
│
├── di/                       # App-level Hilt: PluginModule
└── updater/                  # In-app GitHub Releases updater
    ├── UpdateViewModel.kt
    ├── UpdateRepository.kt
    ├── ApkDownloader.kt / ApkInstaller.kt
    ├── AbiSelector.kt
    └── ui/                   # Update dialog UI
```

---

## 4. Key Technologies

| Technology | Purpose |
|---|---|
| **Kotlin** | Primary language |
| **Jetpack Compose + TV Material** | UI (`androidx.tv:tv-material:1.0.1`) |
| **Hilt** | Dependency injection (`@HiltAndroidApp`, `@Singleton`, `@Inject`) |
| **ExoPlayer (forked Media3)** | Video playback — uses custom local AARs in `app/libs/` replacing stock `media3-exoplayer` |
| **FFmpeg decoders** | `lib-decoder-ffmpeg-release.aar` — audio codec support (AC3, EAC3, DCA, TrueHD…) |
| **libass-android** | ASS/SSA subtitle rendering |
| **Retrofit + OkHttp + Moshi** | HTTP networking and JSON parsing |
| **Coil** | Async image loading (custom Coil instance in `NuvioApplication`) |
| **DataStore Preferences** | All persistent local state (no SQLite/Room) |
| **Supabase** | User auth, remote sync (cloud save for addons/plugins/library/watch progress) |
| **QuickJS (quickjs-kt)** | In-process JavaScript engine for running JS scrapers |
| **NanoHTTPD** | Lightweight local HTTP server for addon/repository configuration via QR code |
| **Trakt** | Scrobbling, watch history, library sync (device-code OAuth flow) |
| **TMDB API** | Metadata enrichment (ratings, cast, backdrops, episode details) |
| **Kotlinx Serialization** | Used alongside Moshi (Supabase models use kotlinx serialization) |
| **Haze** | Blur/glassmorphism effects (`dev.chrisbanes.haze`) |

---

## 5. Build & Run

### Prerequisites

- **Android Studio** (latest stable)
- **JDK 11** or newer
- **Android SDK**, `minSdk = 24`, `compileSdk = 36`
- **Gradle 8.x** (wrapper included)

### Clone & build

```bash
git clone https://github.com/tapframe/NuvioTV.git
cd NuvioTV
./gradlew assembleDebug
```

On Windows PowerShell/CMD, use `gradlew.bat` instead of `./gradlew` when needed.

### Install on a connected device or emulator (Android TV / Fire TV)

```bash
./gradlew installDebug
# or via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Package IDs

| Build type | Application ID |
|---|---|
| `debug` | `com.nuviodebug.com` |
| `release` | `com.nuvio.tv` |
| `benchmark` | `com.nuvio.tv.debug` |

### ABI splits

The release build produces per-ABI APKs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a universal APK. When sideloading pick the one matching your device.

---

## 6. Configuration (local.properties)

Copy `local.example.properties` → `local.properties` and fill in any keys you need for local development. **None of these are required to do a basic debug build** — the app will compile and run with empty values; features requiring external APIs will simply not function.

```properties
# Supabase (cloud sync, auth — leave blank to disable account features)
SUPABASE_URL=
SUPABASE_ANON_KEY=
AVATAR_PUBLIC_BASE_URL=

# Trakt (scrobbling & library sync — leave blank to disable)
TRAKT_CLIENT_ID=
TRAKT_CLIENT_SECRET=

# TMDB (metadata enrichment — leave blank to disable TMDB features)
TMDB_API_KEY=

# Optional internal/private API endpoints (leave blank if not a maintainer)
PARENTAL_GUIDE_API_URL=
INTRODB_API_URL=
TRAILER_API_URL=
IMDB_RATINGS_API_BASE_URL=
IMDB_TAPFRAME_API_BASE_URL=
DONATIONS_BASE_URL=
DONATIONS_DONATE_URL=
```

For development against a **separate dev environment**, create `local.dev.properties` and override only what you need.

### Property precedence (important)

The effective source depends on build type and key:

- `release`:
  - Uses `local.properties` (or built-in defaults where defined in Gradle).
- `debug`:
  - Many backend/env keys are overridden from `local.dev.properties` (e.g., `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `TV_LOGIN_WEB_BASE_URL`, internal endpoint URLs).
  - Some keys still come from `local.properties` unless changed in Gradle `buildTypes.debug` (notably `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `TMDB_API_KEY`, `TRAKT_REDIRECT_URI`).
  - `DONATIONS_*` and `AVATAR_PUBLIC_BASE_URL` in debug use `local.dev.properties` first, then fall back to `local.properties`.

If docs and Gradle config diverge, treat `app/build.gradle.kts` as the source of truth.

### Release signing keys

Release signing can come from environment variables or `local.properties`:

- `NUVIO_RELEASE_STORE_FILE`
- `NUVIO_RELEASE_KEY_ALIAS`
- `NUVIO_RELEASE_KEY_PASSWORD`
- `NUVIO_RELEASE_STORE_PASSWORD`

CI can force debug signing for release builds with `CI_USE_DEBUG_SIGNING=true`.

---

## 7. Coding Conventions

### General

- **Kotlin idioms** — use `data class`, `sealed class`, `object`, scope functions (`let`, `run`, `apply`, `also`), and extension functions where appropriate.
- **No Java** for new code. The codebase is 100% Kotlin.
- **Coroutines** for all async work. Avoid `Thread`, `AsyncTask`, or `Handler` patterns.
- **No Room / SQLite** — all persistence goes through `DataStore<Preferences>` or Supabase. Do not introduce a database dependency.

### Compose

- UI is written exclusively in Jetpack Compose. Do not create Views or XML layouts for new screens.
- TV-specific interactive components should use `androidx.tv.*` from TV Material (e.g., `androidx.tv.material3.Button`, `TvLazyColumn`).
- Keep `@Composable` functions focused and small. Extract reusable pieces to `ui/components/`.
- Avoid business logic inside Composables — drive all state from a `ViewModel`.
- Use `collectAsStateWithLifecycle()` (not `collectAsState()`) for coroutine-backed state.

### ViewModels

- Expose UI state as a single `StateFlow<UiState>` sealed/data class where feasible.
- Use `viewModelScope.launch` for coroutine work. Never launch coroutines in Composables.
- Follow the `*UiState` naming convention (e.g., `HomeUiState`, `MetaDetailsUiState`).

### Repositories and Data Sources

- Repository implementations live in `data/repository/` and implement interfaces from `domain/repository/`.
- DataStore wrappers live in `data/local/` — one class per logical feature group.
- Do **not** leak Android context or DataStore references into domain models or ViewModels directly; inject DataStore wrappers as needed.
- All network calls should be wrapped with the `SafeApiCall` helper from `core/network/` (`Result<T>` pattern).

### Naming

| Artifact | Convention |
|---|---|
| Screen Composables | `*Screen.kt` (e.g., `HomeScreen.kt`) |
| ViewModels | `*ViewModel.kt` |
| UI state | `*UiState.kt` |
| Repository interfaces | `*Repository.kt` (domain) |
| Repository implementations | `*RepositoryImpl.kt` (data) |
| DataStore wrappers | `*DataStore.kt` or `*Preferences.kt` |
| Hilt modules | `*Module.kt` |

### Imports and formatting

- Follow the default **Android Studio / IntelliJ Kotlin formatter** (4-space indentation).
- Wildcard imports are acceptable for `import com.nuvio.tv.*` within the project, but **avoid** wildcard imports for third-party libraries.

---

## 8. Navigation Model

Navigation is handled by `androidx.navigation.compose`. All routes are declared as `sealed class Screen` objects in `ui/navigation/Screen.kt`. The single `NuvioNavHost` composable in `NuvioNavHost.kt` maps routes to screen composables.

**Key screens and their routes:**

| Screen | Route |
|---|---|
| `Home` | `home` |
| `Detail` | `detail/{itemId}/{itemType}?addonBaseUrl=…` |
| `Stream` | `stream/{videoId}/{contentType}/{title}?…` |
| `Player` | `player/{streamUrl}/{title}?…` |
| `Search` | `search` |
| `Discover` | `discover` |
| `Library` | `library` |
| `AddonManager` | `addon_manager` |
| `Plugins` | `plugins` |
| `Account` | `account` |
| `Settings` | `settings` |
| `CastDetail` | `cast_detail/{personId}/{personName}` |

When navigating **always use the `createRoute(…)` factory method** on the relevant `Screen` subtype — never construct route strings by hand. All string parameters are URL-encoded inside `createRoute`.

---

## 9. Dependency Injection (Hilt)

Hilt is the DI framework. The module files are:

| Module | Location | Provides |
|---|---|---|
| `NetworkModule` | `core/di/` | `OkHttpClient`, `Retrofit`, API services |
| `RepositoryModule` | `core/di/` | Repository bindings (interface → impl) |
| `ProfileModule` | `core/di/` | `ProfileManager` |
| `SupabaseModule` | `core/di/` | Supabase `SupabaseClient` |
| `PluginModule` | `di/` (app-level) | `PluginManager`, `PluginRuntime` |

When adding a new injectable dependency:
1. Define the interface in `domain/` if it crosses layers.
2. Add the `@Inject constructor(…)` implementation in `data/` or `core/`.
3. Bind it in the appropriate `*Module.kt` with `@Binds` / `@Provides`.
4. Scope singletons with `@Singleton`.

---

## 10. Plugin System

NuvioTV includes a JavaScript scraper plugin system powered by QuickJS.

### Concepts

| Term | Description |
|---|---|
| **Repository** | A remote manifest URL (`manifest.json`) listing available scrapers |
| **Scraper** | A JavaScript file that, given a TMDB ID / media type, returns stream URLs |
| **PluginRuntime** | Wraps QuickJS — executes scraper JS code in a sandboxed environment |
| **PluginManager** | Orchestrates repositories and scrapers; handles add/remove/refresh/execute lifecycle |

### PluginManager key behaviours

- **Concurrency**: At most `MAX_CONCURRENT_SCRAPERS = 5` scrapers run in parallel (controlled by a `Semaphore`).
- **Single-flight deduplication**: Identical concurrent requests for the same `(scraperId, tmdbId, mediaType, season, episode)` share a single in-flight `Deferred`.
- **Streaming execution**: `executeScrapersStreaming(…)` returns a `Flow<Pair<String, List<LocalScraperResult>>>` that emits partial results as each scraper finishes — used by `StreamScreen` to show incremental results.
- **Remote sync**: On any local mutation (add/remove/toggle), `PluginManager` schedules a debounced (500ms) push to Supabase via `PluginSyncService`.
- **Max items**: Deduped results are capped at `MAX_RESULT_ITEMS = 150`.
- **Scraper size limit**: Scraper JS files larger than `MAX_RESPONSE_SIZE = 5MB` are rejected.

### Manifest URL format

Plugin repositories expose a `manifest.json` at a known URL. The format is:
```
https://example.com/my-repo/manifest.json
```
The `PluginManager` normalises URLs (lowercase, ensures `/manifest.json` suffix, deduplicates) before storing.

---

## 11. Data Flow

### Content browsing (Home → Detail → Stream → Player)

```
HomeViewModel
  → CatalogRepository (fetches catalogs from Stremio addons via Retrofit)
  → HomeScreen displays rows of MetaPreview items

  User selects item →

MetaDetailsViewModel
  → MetaRepository (fetches full Meta from Stremio addon)
  → TmdbMetadataService (enriches with TMDB cast, ratings, backdrops)
  → MetaDetailsScreen shows hero, episodes, cast, MoreLikeThis

  User selects Watch →

StreamScreen / StreamViewModel
  → StreamRepository (loads streams from Stremio addon)
  → PluginManager.executeScrapersStreaming() (local JS scrapers)
  → Merged stream list ranked by StreamAutoPlaySelector

  User selects stream (or autoplay triggers) →

PlayerScreen / PlayerViewModel
  → ExoPlayer (forked) plays the stream URL
  → WatchProgressRepository saves position periodically
  → TraktScrobbleService scrobbles to Trakt
```

### Watch Progress & Library

- Progress is saved locally in `WatchProgressPreferences` (DataStore) on playback.
- `WatchProgressSyncService` / `LibrarySyncService` sync with Supabase periodically and on app launch via `StartupSyncService`.

### Auth / Profiles

- Supabase handles authentication (`AuthManager`).
- Multiple profiles are supported per account (`ProfileManager`, `ProfileDataStore`).
- TV login uses a QR code + companion web app (`AuthQrSignInScreen`).

---

## 12. Testing

Unit tests live in `app/src/test/`. The stack:

- **JUnit 4** for test runners.
- **MockK** (`io.mockk:mockk`) for mocking Kotlin classes/objects.
- **kotlinx-coroutines-test** for testing suspend functions and Flows.

```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

**There are currently very few tests in the project.** When fixing a bug, consider adding a unit test for the function you changed if it contains pure logic that can be tested without a device. Keep tests in the same package structure under `src/test/`.

---

## 13. Common Pitfalls

### Native library conflicts
The `app/libs/` directory contains forked ExoPlayer AARs. The `build.gradle.kts` **globally excludes** `androidx.media3:media3-exoplayer` and `androidx.media3:media3-ui` to avoid conflicts. Do not add these as explicit dependencies.

### DataStore vs Room
This project uses **no Room database**. All local persistence is `DataStore<Preferences>`. If you need to store structured data, serialise it to JSON (using Moshi or kotlinx.serialization) and persist the string.

### Debug vs Release Application IDs
The `debug` build uses `com.nuviodebug.com`, not `com.nuvio.tv`. This means debug and release builds can coexist on the same device. If you're testing with ADB do not mix them up.

### IPv4First DNS
A custom `IPv4FirstDns` resolver is set on the plugin system's `OkHttpClient`. This prefers IPv4 addresses when both A and AAAA records exist — important for some addon/scraper hosts. If you're adding a new OkHttp client elsewhere, consider whether this matters for your endpoint.

### Scraper JS execution is blocking
`PluginRuntime.executePlugin(…)` runs QuickJS synchronously. It is already dispatched to `Dispatchers.IO + NonCancellable` in `PluginManager`. Do not call it from the main thread.

### TV focus management
Jetpack Compose for TV has its own focus model. Use `FocusRequester` and `onFocusChanged` modifiers as demonstrated in existing screens. Avoid relying on default focus traversal — TV remotes require explicit focus routing in many layouts.

### Compose stability
A `compose_stability_config.conf` file at the root allows certain types to be treated as stable for Compose. If you add new domain model types that appear in Composable parameters, check if they need to be listed there to avoid unnecessary recompositions.

---

## 14. Contribution Policy

Please read `CONTRIBUTING.md` for the full policy. Key points:

- PRs are accepted for **bug fixes, stability improvements, minor maintenance, and translations**.
- PRs for new major features, UX redesigns, or refactors without a clear benefit are generally closed without merge.
- **For bug fixes**: keep the scope small and focused on the single issue. One PR per bug.
- **Before opening a PR for anything non-trivial**: open an Issue first and get maintainer confirmation.
- **Bug reports** must include: app version, device model, Android version, exact reproduction steps, and expected vs. actual behaviour. Crash reports require a logcat stack trace.

### Collecting logs for a bug

```bash
# Capture last 300 log lines after reproducing the bug
adb logcat -d | tail -n 300

# Filter to NuvioTV-specific logs
adb logcat -d | grep -E "com\.nuvio|NuvioTV|PluginManager|PlayerScreen"
```

### Useful Gradle tasks

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK (requires signing config)
./gradlew installDebug         # Build + install debug APK
./gradlew test                 # Run unit tests
./gradlew lint                 # Run Android lint
./gradlew dependencies         # Show full dependency tree
```
