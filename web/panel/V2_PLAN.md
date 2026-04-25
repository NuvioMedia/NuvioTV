# NuvioTV Web Panel — v2 implementation plan

> **Status: v2 SHIPPED on `dev` (commits `1f9d7599` + follow-up).** Migration 008 applied; every domain in §3 has an edit form; partial-merge RPC is live; 24 round-trip tests guard schema drift. The notes below are kept for historical reference and to seed v3.
>
> **For the AI taking this over:** read this entire document before touching code. The blob schema discipline (§4) and the partial-merge RPC (§3) are the two things that will make or break this work — get them wrong and you silently corrupt users' settings. v0/v1 are already shipped and live; you're picking up at v2.

---

## 1. Where we are

- **v1 is live** at https://account.omnio.tv — read-only views of profiles, addons, plugins, integrations status, collections, library, settings, devices.
- **Repo:** https://github.com/TheMrClaus/OmnioTV (origin remote name is `origin`, default branch is `dev`). Project conventions push commits directly to `dev`. There is also an `upstream` remote pointing at NuvioMedia/NuvioTV; do not push there.
- **Panel source:** `web/panel/` (Next.js 15.5 App Router, TypeScript strict, Tailwind, `@supabase/ssr` 0.5, Zod 3, Vitest 2). Lives next to `web/tv-login/` (a separate, unrelated TV-pairing flow app).
- **Supabase:** hosted Cloud, project `ttihlwxoejxoucehxstl.supabase.co`. Studio at https://supabase.com/dashboard. Anon key uses the new `sb_publishable_*` format. Same instance is used by the TV app, the tv-login flow, and this panel.
- **Vercel project:** `omniotv-panel`, Production Branch = `dev`, Root Directory = `web/panel`. Env vars set: `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`. Env-var changes do **not** auto-redeploy — manual redeploy required.

## 2. Critical concepts you must internalize

### 2.1 The settings blob and its type-tagged envelope

The TV stores 10 separate "feature" DataStores per profile, but pushes them all to Supabase as **one** JSONB blob in `profile_settings.settings_json`:

```jsonc
{
  "version": 1,
  "features": {
    "theme_settings": {
      "selected_theme": { "type": "string", "value": "DARK" },
      "selected_font":  { "type": "string", "value": "INTER" }
    },
    "tmdb_settings": {
      "tmdb_enabled":  { "type": "boolean", "value": true },
      "tmdb_language": { "type": "string",  "value": "en"   }
    }
    // … 8 more features
  }
}
```

**Every leaf is a type-tagged object** with `type` ∈ `"string" | "boolean" | "int" | "long" | "float" | "double" | "string_set"`. The TV's decode path picks `intPreferencesKey` / `longPreferencesKey` / `floatPreferencesKey` / `doublePreferencesKey` / `booleanPreferencesKey` / `stringPreferencesKey` / `stringSetPreferencesKey` based on `type`. **Encoding an int as `"long"` makes the TV-side read silently fall back to the default — there is no error, just silent data loss.** Test every encoder you write against `lib/settings/envelope.test.ts`.

The 10 features and their `*DataStore.kt` source of truth:

| Feature key (in blob) | DataStore file (`app/src/main/java/com/omnio/tv/data/local/`) |
|---|---|
| `theme_settings` | `ThemeDataStore.kt` |
| `layout_settings` | `LayoutPreferenceDataStore.kt` |
| `player_settings` | `PlayerSettingsDataStore.kt` |
| `trailer_settings` | `TrailerSettingsDataStore.kt` |
| `tmdb_settings` | `TmdbSettingsDataStore.kt` |
| `mdblist_settings` | `MDBListSettingsDataStore.kt` |
| `animeskip_settings` | `AnimeSkipSettingsDataStore.kt` |
| `track_preference` | `TrackPreferenceDataStore.kt` (dynamic keys, **do not edit from web**) |
| `trakt_settings` | `TraktSettingsDataStore.kt` |
| `emby_credentials` | `EmbyCredentialsDataStore.kt` (**`emby_device_id` is excluded from sync** — never write it) |

The encode/decode helpers and the per-feature Zod schemas + encoder maps already exist:

- `web/panel/lib/settings/envelope.ts` — `encode*`, `decode`, `parseBlob`, `EnvelopeDecodeError`
- `web/panel/lib/settings/schemas.ts` — `themeSettingsSchema`, `layoutSettingsSchema`, …, plus `themeSettingsEncoders`, `layoutSettingsEncoders`, …, plus the helper `encodeFeature(featureKey, decodedValues)` which throws on unknown keys (good — that's how schema drift is caught).
- `web/panel/lib/settings/envelope.test.ts` — 14 round-trip tests; run `npm test` after any change.

### 2.2 The TV's whole-blob push race

The TV's `ProfileSettingsSyncService` (at `app/src/main/java/com/omnio/tv/core/sync/ProfileSettingsSyncService.kt`) observes every DataStore for changes, then pushes the **entire blob** with a 1.5-second debounce. If the panel naively pushes the whole blob too, a TV-side change made between the panel's pull and the panel's push gets silently overwritten.

**Solution: migration 008 (already committed) introduces a partial-merge RPC**:

```sql
sync_push_profile_settings_partial(
  p_profile_id integer,
  p_feature_key text,            -- e.g. 'tmdb_settings'
  p_feature_json jsonb,          -- the type-tagged sub-object only for that feature
  p_expected_updated_at timestamptz default null
) returns timestamptz
```

Server-side `jsonb_set` merges the supplied feature into the existing blob. If `p_expected_updated_at` is non-null and the stored row is newer, raises `profile_settings_conflict` with code `P0001` — caller refetches and retries.

**Migration 008 is committed but NOT YET APPLIED to Supabase.** Your first job is to apply it (§5.1 below).

### 2.3 RLS: how authenticated panel users see TV data

`profile_settings` and the other tables use RLS policies referencing two helper functions in migration 001:

- `current_sync_owner_id()` — returns `auth.uid()` if you're a primary user, or the `linked_devices.owner_id` if you're a paired TV.
- `can_access_owner(p_owner_id)` — returns true if you are that owner or a linked device of theirs.

The panel's user logs in via Supabase Auth email/password. Their `auth.uid()` is the same UUID that the TV used when it signed in directly with the same email. Reads via `.from("profiles").select(...)` and writes via SECURITY DEFINER RPCs all resolve correctly without you doing anything special.

**Trap:** if the user has an empty `linked_devices` table (because their TVs sign in directly with email/password rather than via QR pairing), the Devices tab will be empty. That's expected. v1's empty state explains this.

## 3. v2 deliverables (in priority order)

| # | Deliverable | Files to touch | Risk |
|---|---|---|---|
| 1 | Apply migration 008 in Supabase Studio | (no repo change — manual SQL) | trivial |
| 2 | Generic "save form" pattern + Server Action wrapper | `web/panel/lib/actions/`, `web/panel/components/forms/` | **medium — sets pattern for 9 domains** |
| 3 | Addons CRUD (relational, easiest) | `app/p/[profileId]/addons/page.tsx` + new form component | low |
| 4 | Plugins enable/disable/reorder (no JS edit) | `app/p/[profileId]/plugins/page.tsx` + new form | low |
| 5 | Integrations blob domains (TMDB, MDBList, AnimeSkip, Emby) | per-domain pages under `app/p/[profileId]/integrations/` | **medium — first uses of partial-merge** |
| 6 | Trakt status + "re-auth on TV" link | `app/p/[profileId]/integrations/trakt/page.tsx` | trivial (read-only) |
| 7 | Settings forms (theme, layout, trailer, player) | break out `app/p/[profileId]/settings/page.tsx` into per-domain pages | medium |
| 8 | Collections CRUD | `app/p/[profileId]/collections/page.tsx` + form | medium |
| 9 | Profile CRUD (rename / avatar / delete) | `app/p/[profileId]/profiles/page.tsx` (currently doesn't exist; v1 uses `/profiles` for the picker) | low |
| 10 | Danger zone (sign out everywhere, delete profile data) | `app/p/[profileId]/danger/page.tsx` | low |
| 11 | Re-add `vercel.json` with a working `ignoreCommand` path filter | `web/panel/vercel.json` | trivial |

**Out of scope for v2 (defer to v3):** plugin JS code editing, Trakt OAuth on web, collections image uploads (Supabase Storage), `track_preference` editing (per-content dynamic keys).

## 4. The schema-drift discipline (read twice)

Every key in every `*DataStore.kt` is mirrored in `web/panel/lib/settings/schemas.ts` in **two** places:

1. The Zod schema for that feature (`*Schema`)
2. The encoder map (`*Encoders`)

`encodeFeature(featureKey, decoded)` **throws** on unknown keys. That's intentional — silent drops would corrupt users' settings on save. If you add or rename a key on the Kotlin side, you **must** update both the Zod schema and the encoder map in the same PR.

`web/panel/CONTRIBUTING.md` documents this rule. Honor it.

When v2 adds an edit form for a feature, also add a new test in `lib/settings/envelope.test.ts` that round-trips a representative blob through that feature's `encodeFeature` → server-side merge → `parseBlob` → `decodeFeature` → assert deep-equal. The current test file has 14 tests; expand it.

## 5. Implementation playbook

### 5.1 Apply migration 008 to Supabase

1. Open https://supabase.com/dashboard/project/ttihlwxoejxoucehxstl/sql/new
2. Paste the contents of `supabase/migrations/008_profile_settings_partial_merge.sql` (already in the repo)
3. Run
4. Verify: `select pg_get_functiondef('public.sync_push_profile_settings_partial(integer, text, jsonb, timestamptz)'::regprocedure);` should return the function body
5. Smoke test: in Studio's API docs (left sidebar) → Functions → `sync_push_profile_settings_partial` → Try it out with a known profile ID. Should return a timestamp.

### 5.2 The save-form pattern (build this once, reuse 9 times)

Create:

- `web/panel/lib/actions/blob.ts` — Server Action wrappers around `sync_push_profile_settings_partial`. Take `(profileId, featureKey, decodedValues, expectedUpdatedAt)`, call `encodeFeature` to get the envelope, call the RPC, return `{ ok: true, updatedAt }` or `{ ok: false, conflict: true }` on `P0001` / `{ ok: false, error: string }` otherwise.
- `web/panel/lib/actions/relational.ts` — Server Action wrappers around `sync_push_addons`, `sync_push_plugins`, `sync_push_collections`, `sync_push_profiles`, `sync_delete_profile_data`. Each takes a profile ID + the relevant list / object and posts via `postgrest.rpc(...)`.

The form component pattern:

```
components/forms/<Domain>Form.tsx (Client Component)
  ↓ uses useFormStatus / useTransition
  ↓ optimistic UI: marks form dirty on change
  ↓ on submit: calls Server Action, shows toast / inline result
  ↓ on { ok: false, conflict: true }: refetches via router.refresh() and re-shows pending diff
```

Keep one `<DirtyTracker>` hook that detects unsaved changes and warns on navigation away — every domain's form will need it.

### 5.3 Order of domain rollout (do them in this order — earlier ones de-risk later ones)

**1. Addons (relational, simplest possible).** Page already shows the table. Add: drag-to-reorder (use `@dnd-kit/sortable` — already in the npm registry, hasn't been installed yet), enable toggle, add-by-URL, remove. Server Action calls `sync_push_addons(p_profile_id, p_addons)` with the full ordered list. RPC overwrites in one shot, no merge needed.

**2. Plugins (relational, identical pattern to addons).** Same UI, different RPC: `sync_push_plugins`. Do **not** edit the JS code — only enable/disable/reorder/rename. The TV stores plugin code on disk under `plugin_code/` directories.

**3. Single-domain blob features (in increasing complexity):**

   1. **TMDB settings** — 14 boolean toggles + 1 language string. Easiest because every key is `boolean` or `string`, no numerics or sets. Build the form here first as a template.
   2. **MDBList settings** — 8 booleans + 1 string (api key). Same shape as TMDB.
   3. **AnimeSkip settings** — 1 boolean + 1 string. Trivial.
   4. **Emby credentials** — 3 strings. **Do not include `emby_device_id`** in your form or your encoder call — it's excluded from sync (`ProfileSettingsSyncService.kt:89`). The TV manages it per-device.
   5. **Trailer settings** — 1 boolean + 1 int. Trivial.
   6. **Theme settings** — 2 strings (theme name, font name). Trivial. Render as enum dropdowns with the values from `domain/model/AppTheme.kt` and `AppFont.kt`.
   7. **Trakt settings** — read-write for the prefs (`continue_watching_days_cap`, `show_unaired_next_up`, `show_meta_comments`, `watch_progress_source`); read-only `dismissed_next_up_keys` (don't surface UI for it). The OAuth tokens live in a per-device DataStore and are NOT in the blob — the page should also show "connected as @username" if a token exists on the TV side, but the panel cannot trigger Trakt re-auth. Add a CTA: "Re-authenticate on your TV". v3 will deep-link.
   8. **Player settings** — large surface, ~50 keys. Group them: Player engine, Audio, Subtitle style, Buffer, Auto-play. Match the layout of `ui/screens/settings/PlaybackSettingsContent.kt` and its sub-screens. Numerical inputs need correct `encodeInt` / `encodeFloat` — check the Kotlin side carefully (e.g. `next_episode_threshold_percent_v2` is a `floatPreferencesKey`).
   9. **Layout settings** — ~30 keys, mostly booleans + a few ints (`poster_card_*_dp`). Catalog ordering keys are `string` (Gson-serialized lists) — read and write them as the raw JSON string the TV produces; do not try to parse and edit the catalog list as a structured array unless you also build a catalog picker UI.

**4. Collections** — `sync_push_collections(p_profile_id, p_collections_json)` overwrites the whole array. The JSON shape is camelCase (`{ id, title, backdropImageUrl, pinToTop, focusGlowEnabled, viewMode, showAllTab, folders[] }` etc — see `lib/data/collections.ts` for the TS interfaces and `app/src/main/java/com/omnio/tv/data/local/CollectionsDataStore.kt:170-201` for the Kotlin Serializable* form). For v2, allow: rename collection/folder, reorder, toggle `pinToTop`, set cover emoji, paste cover image URL. **Do NOT** implement image upload in v2 — Supabase Storage integration is v3.

**5. Profile CRUD** — `sync_push_profiles(p_profiles)` takes the full array of profile objects (without PIN — that uses `set_profile_pin` / `clear_profile_pin` separately). Allow: rename, change avatar color (color picker), change avatar id (queried from `avatar_catalog` table; see `app/src/main/java/com/omnio/tv/data/local/ProfileDataStore.kt`), toggle `usesPrimaryAddons` / `usesPrimaryPlugins` (only meaningful for non-primary profiles). Profile deletion uses `sync_delete_profile_data(p_profile_id)`.

**6. Danger zone** — sign out (already exists in `components/SignOutButton.tsx`); add "delete profile data" button that calls `sync_delete_profile_data` with confirmation modal; add "sign out all sessions" via `supabase.auth.admin.signOut(userId, scope: 'global')` — but that requires service role, so skip for v2. Use Supabase's `supabase.auth.signOut({ scope: 'global' })` from the client which signs out current user across all sessions of THEIR auth user (not other linked devices' auth users).

### 5.4 Re-add a working `vercel.json`

The first attempt had two problems:

1. `_comment` field — not in Vercel's schema, fails build immediately.
2. `ignoreCommand` referenced `HEAD^` which doesn't exist on Vercel's shallow first-deploy git fetch.

The fix:

```jsonc
{
  "ignoreCommand": "bash -c 'git rev-parse HEAD^ >/dev/null 2>&1 || exit 1; git diff --quiet HEAD^ HEAD -- . ../../supabase/migrations'"
}
```

`HEAD^` exists → standard path-filter check. `HEAD^` doesn't exist (initial deploy) → `exit 1` → Vercel builds.

Test by pushing a commit that touches only `app/src/main/java/...` (Android) and confirming the Vercel deploy is skipped. Then push a touch to `web/panel/` and confirm it builds.

## 6. Critical files (with paths) the next AI must read before editing

### Android-side (read-only references — do not edit unless necessary)
- `app/src/main/java/com/omnio/tv/core/sync/ProfileSettingsSyncService.kt` — blob shape, envelope encoding, debounce behavior
- `app/src/main/java/com/omnio/tv/core/sync/ProfileSyncService.kt` — profile push/pull (no auto-trigger; only fires on user edit)
- `app/src/main/java/com/omnio/tv/core/sync/AddonSyncService.kt`, `PluginSyncService.kt`, `CollectionSyncService.kt` — relational push patterns
- `app/src/main/java/com/omnio/tv/data/local/*DataStore.kt` — the 10 blob feature definitions (see table in §2.1)
- `app/src/main/java/com/omnio/tv/ui/screens/account/AccountViewModel.kt` — `pushLocalDataToRemote()` and `pullRemoteData()` orchestrate sign-in sync. v1 fixed a missing `profileSyncService.pushToRemote()` here.

### Supabase-side
- `supabase/migrations/001_initial_schema.sql` — tables, RLS policies, `current_sync_owner_id()`, `can_access_owner()`, every existing `sync_push_*` / `sync_pull_*` RPC
- `supabase/migrations/005_aio_metadata.sql` — `aio_metadata_links` table (used by AIOMetadata page if you add one)
- `supabase/migrations/006_aio_metadata_password.sql` — config password column
- `supabase/migrations/007_collections.sql` — collections RPC
- `supabase/migrations/008_profile_settings_partial_merge.sql` — partial-merge RPC for v2

### Panel-side (already exists, extend in v2)
- `web/panel/lib/settings/envelope.ts` — encode/decode helpers
- `web/panel/lib/settings/schemas.ts` — Zod schemas + encoder maps for 9 features (track_preference deliberately omitted)
- `web/panel/lib/settings/envelope.test.ts` — 14 round-trip tests; expand as you add forms
- `web/panel/lib/data/*.ts` — read helpers for each domain (extend with write helpers via Server Actions)
- `web/panel/lib/supabase/server.ts` + `browser.ts` + `middleware.ts` — auth setup; do not change auth flow
- `web/panel/app/login/actions.ts` — example of Server Action pattern
- `web/panel/app/p/[profileId]/...` — every domain page; replace read-only views with edit forms domain-by-domain
- `web/panel/CONTRIBUTING.md` — schema-drift discipline; read before editing schemas
- `web/panel/README.md` — current status, deploy info

### Vercel-side
- Project `omniotv-panel` exists, Production Branch = `dev`, Root Directory = `web/panel`. Don't change.
- `omnio-tv-login` is a separate Vercel project for `app.omnio.tv/tv-login`. Don't touch it.

## 7. Verification checklist (run at the end of each domain)

For relational domains (addons / plugins / collections / profiles):

- [ ] Save a change in the panel → wait 5s → reopen Supabase Studio → row reflects the change
- [ ] Open the TV app → trigger a manual sync (Settings → Account → Sync Now if available, otherwise restart) → TV reflects the change
- [ ] Reverse: change on TV → panel page reload shows the change
- [ ] Two browser tabs open → save in tab A → tab B's stale page should show conflict-resolution behavior on save (refetch + re-apply diff)

For blob domains:

- [ ] All of the above, plus:
- [ ] In two browser tabs of two different blob domains (e.g. TMDB and Theme), save concurrently in both tabs → `profile_settings.settings_json` should contain both changes (proves partial-merge isn't clobbering across features)
- [ ] Manually edit `settings_json` in Studio to add an `unknown_feature_xyz` key under `features` → reload the panel's Settings page → it should show "Unknown feature keys present" warning (proves the schema-drift detector works) and NOT crash

For the conflict path:

- [ ] Pull a profile_settings row's `updated_at` into a panel form
- [ ] In another tab, save a change to the same feature (this updates the timestamp)
- [ ] Submit the first form → server returns `profile_settings_conflict` → UI refetches and surfaces the diff for re-apply
- [ ] Re-submit → succeeds

For the schema:

- [ ] `npm test` from `web/panel/` passes (currently 14 tests)
- [ ] `npm run build` from `web/panel/` succeeds
- [ ] `npx tsc --noEmit` from `web/panel/` succeeds with no errors

## 8. Project conventions you must follow

- **Commits go to `dev` directly** (no feature branches required; that's the project convention). Use Conventional Commits prefixes that match recent history: `feat`, `fix`, `docs`, `refactor`, `chore`. Co-author trailer:
  ```
  Co-authored-by: Claude <claude@anthropic.com>
  ```
- **Never push to `upstream`** — that points at the public NuvioMedia/NuvioTV repo.
- **Never edit version numbers manually** — `scripts/release_beta.py` does it. The web panel doesn't have an Android-style version-bump pipeline; just trust git.
- **Hosted Supabase, not self-hosted** — older notes might say self-hosted; that's wrong. Real instance is `ttihlwxoejxoucehxstl.supabase.co` on Supabase Cloud, hosted on the user's account.
- **The panel uses cookie auth via `@supabase/ssr`** — don't introduce another auth library. Server Components read via `createServerSupabase()` from `lib/supabase/server.ts`, Client Components via `createBrowserSupabase()` from `lib/supabase/browser.ts`.
- **Minimal-scope edits** — if a bug is found in the Android side while building v2 (like the missing `profileSyncService.pushToRemote()` v1 found), fix it as a separate one-line commit and call it out. Don't bundle Android changes with web changes.

## 9. Known issues / gotchas

- **`vercel.json` schema is strict** — no `_comment` keys, no extra properties. Validate locally with `npx vercel inspect` if unsure.
- **Vercel env-var changes don't redeploy** — manually trigger from Deployments tab → ⋯ → Redeploy.
- **DNS already configured** — `account.omnio.tv` → CNAME → `cname.vercel-dns.com`. Don't touch.
- **The 5 anonymous users in `auth.users`** are orphans from incomplete TV-pairing attempts before v1. They don't have linked_devices rows. Safe to leave or to clean up via Studio. They will not affect anything.
- **`emby_device_id`** is the only key that is NEVER in the blob — never touch it from the panel. It's per-device.
- **The `next_episode_threshold_percent_v2`** key is a `floatPreferencesKey` while the legacy `next_episode_threshold_percent` (without `_v2`) is `intPreferencesKey`. Both still exist for backward compat. The schema map handles this; just don't second-guess it.
- **The `track_preference` feature has dynamic keys** — `sub_lang|<contentId>`, `audio_track_id|<contentId>`, etc. The panel deliberately does not edit it; preserve any existing values on round-trip but never construct new ones.
- **Profile sync regression history** — until commit `58945302` on `dev`, the TV did not push profiles on sign-in (only on edit). Anyone running a build older than that release will need to manually edit a profile on the TV before profiles appear in Supabase. The web panel should not assume profiles always exist.

## 10. Ship the docs too

When v2 is feature-complete:
- Update `web/panel/README.md` "Status" section from "v1 (read-only MVP)" to "v2 (full edit)" — **done**
- Add a CHANGELOG section listing what each domain's form supports — README has it
- Bump the v2 status in this V2_PLAN.md to "completed" or delete the file in favor of a v3 plan — **done** (header note above)

## 12. v2 ship notes (post-mortem)

Notes from actually shipping v2, useful to whoever picks up v3:

- **`sync_push_addons` is asymmetric with the addons table.** The RPC only writes `url` + `sort_order` (per `AddonSyncService.kt:55-65`), so any panel-side toggle of `enabled` or rename of `name` would be wiped on the TV's next push. The panel intentionally does not surface those affordances for addons. Plugins are fine — `sync_push_plugins` writes all four fields.
- **The Player and Layout dropdown options in v2 were guessed twice and corrected against the Kotlin enums in commit `<this one>`.** Don't trust hand-written option lists — grep `enum class` in `app/src/main/java/com/omnio/tv/data/local/PlayerSettingsDataStore.kt` and `app/src/main/java/com/omnio/tv/domain/model/` before editing.
- **`avatar_catalog` is not seeded by any migration.** The bucket `avatars` is created public in migration 001 but the table is empty in fresh Supabase projects. Either seed it manually (insert rows pointing at storage paths) or, in v3, ship a seed migration. The panel's avatar dropdown is empty until rows exist.
- **`emby_device_id` was a footgun in early drafts.** It's a per-device identifier excluded from sync; the panel never writes it. The Zod schema in `lib/settings/schemas.ts` does not list it — encoder will throw if a future PR tries to add it.
- **`next_episode_threshold_percent_v2` (float) coexists with the legacy `next_episode_threshold_percent` (int)** for backward compat. The Player form exposes both; the round-trip test `player form save: legacy threshold is int, _v2 threshold is float` pins the type contract so a future "let's clean these up" PR fails loudly.
- **The schema-drift detector works.** When `tmdb_settings` was extended on the Kotlin side mid-v2, `encodeFeature` threw on the unknown key, the test suite caught it before push, and the panel was updated in the same PR. This is the pattern §4 promised — keep doing it.

## 13. v3 backlog (deferred from v2)

Listed in the order they came up:

1. **Trakt OAuth on web.** Currently the panel says "re-auth on a TV". Web-side OAuth would need the redirect URI registered with Trakt and a token-exchange Edge Function.
2. **Plugin JS code editing.** v2 only edits plugin metadata; the JS lives on disk per device. Real editing means a Monaco-style editor + a Storage bucket for the code.
3. **Collections image uploads to Supabase Storage.** Currently URL-only. Add a bucket, an upload action, and a picker.
4. **Catalog source picker for Collections folders.** v2 marks `catalogSources` read-only — adding/removing requires walking the user's installed addons and listing each addon's catalogs.
5. **New-profile creation from the panel.** `sync_push_profiles` already supports it, but UX-wise it interacts with PIN management which is also TV-only.
6. **PIN management** (`set_profile_pin` / `clear_profile_pin`) from the panel.
7. **`avatar_catalog` seed migration** — see §12.
8. **Linked-device removal flow.** Currently read-only on `/devices`. The `unlink_device` RPC exists.
9. **Bulk operations** (e.g. wipe library, wipe watched items) — currently only "delete profile data" wholesale.
10. **A nice-to-have**: surface the partial-merge conflict more gracefully than "page refreshed" — show a diff of the user's pending edits vs. the server's new state and let them re-apply selectively.

## 11. Quick start for the new AI

```bash
git pull
cd web/panel
npm install
cp .env.local.example .env.local
# Edit .env.local — get values from Vercel project omniotv-panel
#   (Settings → Environment Variables → reveal each)
npm run dev          # http://localhost:3000
npm test             # 14 round-trip tests should pass
npx tsc --noEmit     # should produce no output
```

Then:
1. Apply migration 008 (§5.1)
2. Build the save-form pattern (§5.2) before touching any domain page
3. Pick one domain at a time from §5.3, in the listed order
4. After each domain: run the verification checklist (§7) before moving on
5. Push to `dev` after each domain — Vercel will auto-deploy

Good luck. Ship one domain at a time and don't skip §4.
