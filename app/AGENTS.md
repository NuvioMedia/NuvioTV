# APP MODULE KNOWLEDGE BASE

## OVERVIEW
`app/` contains the Android application: Compose UI, navigation, Hilt DI, repositories, sync services, player logic, and account/profile flows.

## STRUCTURE
```text
app/
├── build.gradle.kts
└── src/
    ├── main/java/com/omnio/tv/
    │   ├── core/      # Sync, DI, player, plugin, profile, server, network helpers
    │   ├── data/      # Local stores, remote APIs/DTOs, repositories, mappers, trailers
    │   ├── domain/    # Domain models + repository interfaces
    │   ├── ui/        # Compose components, theme, navigation, screens
    │   ├── updater/   # In-app update flow
    │   ├── MainActivity.kt
    │   └── OmnioApplication.kt
    └── test/java/com/omnio/tv/
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| App bootstrap | `src/main/java/com/omnio/tv/OmnioApplication.kt` | Hilt app class, Coil configuration |
| Top-level shell | `src/main/java/com/omnio/tv/MainActivity.kt` | Very large file; avoid casual refactors |
| Navigation graph | `src/main/java/com/omnio/tv/ui/navigation/OmnioNavHost.kt` | All Compose destinations register here |
| Route creation | `src/main/java/com/omnio/tv/ui/navigation/Screen.kt` | Encodes route params; reuse helpers |
| Sync behavior | `src/main/java/com/omnio/tv/core/sync/` | 8 dedicated sync services |
| Data access | `src/main/java/com/omnio/tv/data/repository/` | 23 repository/service files, many Trakt-specific |
| Screen-level UI | `src/main/java/com/omnio/tv/ui/screens/` | Home, player, detail, search, settings, account, addon, tmdb |
| Unit tests | `src/test/java/com/omnio/tv/` | Mirrors production package structure |

## ARCHITECTURE
- Dependency flow is conventional: `ui` → `domain`/`data`, with `core` hosting cross-cutting services and DI modules.
- Hilt is the DI backbone (`@HiltAndroidApp`, `@AndroidEntryPoint`, Hilt modules under `core/di`).
- Route registration is centralized in `ui/navigation/OmnioNavHost.kt`; route string construction lives in `Screen.kt`.
- `MainActivity.kt` is a hotspot: onboarding, locale setup, auth/profile state, drawer UI, and app-shell coordination all live there.
- Large complexity clusters exist in `ui/screens/player`, `core/sync`, `core/plugin`, `core/tmdb`, and `data/repository`.

## CONVENTIONS
- Keep runtime config in Gradle `buildConfigField(...)` wiring; debug and release read from different property sources.
- Compose performance settings are intentional: metrics/reports are enabled and `compose_stability_config.conf` marks stable classes.
- Native libs come from dependencies; `jniLibs` local source is intentionally disabled via `src/main/_jni_disabled`.
- `app/build.gradle.kts` remaps debug `applicationId` to `com.omnio.tv.debug`; do not assume debug package name matches release.
- The repo uses unit tests with JUnit4, MockK, and `kotlinx-coroutines-test`; `MainDispatcherRule.kt` is the common coroutine test helper.

## ANTI-PATTERNS
- Do not hand-build navigation routes; use `Screen.*.createRoute(...)` helpers so encoding stays consistent.
- Do not split work across new ad hoc service layers when a matching package already exists in `core/`, `data/`, `domain/`, or `ui/`.
- Do not casually refactor `MainActivity.kt`, `PlayerScreen.kt`, `TraktProgressService.kt`, `TmdbMetadataService.kt`, `PluginRuntime.kt`, or `StartupSyncService.kt` during bugfixes; they are major knowledge hotspots.
- Do not add instrumentation-test assumptions to agent docs; the active test suite is primarily under `src/test/java`.

## COMMANDS
```bash
./gradlew :app:build
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

## NOTES
- `ui/screens/player/` and `data/repository/` are the densest subtrees; read neighboring files before editing to preserve local patterns.
- Theme migration is in progress; prefer `OmnioTheme.colors` over older direct color usage when touching theme-related code.
