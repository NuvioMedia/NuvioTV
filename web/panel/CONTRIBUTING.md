# Contributing to web/panel

## Critical: keeping the settings schemas in sync with the TV

The `profile_settings.settings_json` blob is shaped by the Kotlin `*DataStore.kt`
files. The web panel mirrors those shapes in two places:

- `lib/settings/schemas.ts` — Zod schemas (validation)
- `lib/settings/schemas.ts` — encoder maps (envelope type per key)

**If you add or rename a key in any of these Kotlin files:**

- `app/src/main/java/com/omnio/tv/data/local/ThemeDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/LayoutPreferenceDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/PlayerSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/TrailerSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/TmdbSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/MDBListSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/AnimeSkipSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/TrackPreferenceDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/TraktSettingsDataStore.kt`
- `app/src/main/java/com/omnio/tv/data/local/EmbyCredentialsDataStore.kt`

…you **must** add or rename the same key in `lib/settings/schemas.ts`, in both
the Zod schema and the encoder map, **in the same PR**. Schema drift between
TV and web silently corrupts settings — `encodeFeature` will throw on save for
any unknown key, but only at runtime, only on the affected user, and only on
the affected feature.

## Envelope type must match the TV-side preference key

The TV uses `intPreferencesKey` / `longPreferencesKey` / `floatPreferencesKey`
/ `doublePreferencesKey` / `booleanPreferencesKey` / `stringPreferencesKey`
/ `stringSetPreferencesKey` to declare the type of each pref. The envelope
`type` field must match — encoding an int as `"long"` makes the TV-side read
silently fall back to the default. The encoder map in `schemas.ts` is the
single place this mapping is recorded; double-check it against the
`*DataStore.kt` you are mirroring.

## Tests

`npm test` runs round-trip tests over every feature. Add a sample to
`lib/settings/envelope.test.ts` whenever you add a new feature.
