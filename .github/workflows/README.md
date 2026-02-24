# GitHub Actions for NuvioTV

This directory contains CI, security, and release workflows for this Android project.

## Workflows

- `ci.yml`
  - Triggers: PR/push on `dev` and `main` (skips docs-only changes in `README.md` and `assets/**`)
  - Hard gates: `:app:lintDebug`, `:app:testDebugUnitTest`
  - Progressive check: `:app:assembleDebug` with `continue-on-error: true`
  - Uploads lint/test reports as artifacts

- `security.yml`
  - Triggers: PR, weekly schedule, manual dispatch
  - Jobs:
    - Dependency review (`actions/dependency-review-action`)
    - CodeQL analysis for Java/Kotlin

- `release.yml`
  - Triggers: tag push `v*` and manual dispatch
  - Builds `release` APK/AAB (signed when secrets are available, unsigned fallback otherwise)
  - Publishes GitHub Release with attached artifacts

## Optional Signing Secrets (Release)

- `ANDROID_KEYSTORE_BASE64`
- `NUVIO_KEY_ALIAS`
- `NUVIO_KEY_PASSWORD`
- `NUVIO_STORE_PASSWORD`

If these are not set, release workflow still runs and produces unsigned release artifacts.

## Optional Secrets (CI/Release BuildConfig values)

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `PARENTAL_GUIDE_API_URL`
- `INTRODB_API_URL`
- `TRAILER_API_URL`
- `IMDB_RATINGS_API_BASE_URL`
- `IMDB_TAPFRAME_API_BASE_URL`
- `TRAKT_CLIENT_ID`
- `TRAKT_CLIENT_SECRET`
- `TRAKT_API_URL`
- `TV_LOGIN_WEB_BASE_URL`

If optional secrets are not set, workflows still run with empty/default values suitable for compile/test.

## Notes

- `release.yml` automatically uses signing secrets when present and falls back to unsigned artifacts when absent.
- Gradle signing values are resolved from environment or Gradle properties (`NUVIO_*`) and only applied when complete.
- `:benchmark` automation is intentionally not part of these workflows because the module is currently absent in this repo.
