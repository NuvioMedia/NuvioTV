# NuvioTV Web Panel

Web control panel for NuvioTV accounts. Lets a logged-in user view and (in v2)
edit every setting the TV app exposes: profiles, addons, plugins, integrations,
collections, home layout, playback, linked devices.

**Production:** https://account.omnio.tv

Backed by the same self-hosted Supabase the TV app uses.

## Status — v2 (full edit)

Writes everywhere. Every domain has a Save bar; every blob domain uses the
`sync_push_profile_settings_partial` RPC so concurrent edits to other
features in the blob don't clobber each other. 17 round-trip tests.

What ships in v2:

- **Addons** — drag-to-reorder, add by manifest URL, remove. (No enable
  toggle: the TV's `sync_push_addons` RPC writes only `url`+`sort_order`,
  so any panel-side enable would be wiped on the next TV push.)
- **Plugins** — drag-to-reorder, enable toggle, inline rename, remove.
  Plugin JS code stays on the TV.
- **Integrations** — sub-pages for TMDB, MDBList, AnimeSkip, Emby, Trakt.
  Trakt OAuth still happens on the TV.
- **Settings** — sub-pages for Theme, Layout, Trailers, Player.
- **Collections** — reorder collections and folders, rename, pin to top,
  cover emoji + image URL, tile shape. Catalog source picker stays TV-only
  (deferred to v3).
- **Manage profiles** — rename, recolor, change avatar (from `avatar_catalog`),
  toggle `uses_primary_addons` / `uses_primary_plugins`, delete.
- **Danger zone** — delete a profile's synced data; sign out from all
  browsers (`supabase.auth.signOut({ scope: 'global' })`).
- **`vercel.json`** — `ignoreCommand` skips deploys that don't touch
  `web/panel/` or `supabase/migrations/`.

What does **not** ship in v2 (deferred to v3):

- Trakt OAuth on web.
- Plugin JS code editing.
- Collections image uploads (Supabase Storage integration).
- Catalog source picker for Collections folders.
- New-profile creation from the panel (TV-only).
- PIN management (`set_profile_pin` / `clear_profile_pin`) from the panel.

## Schema-drift discipline

Every key in every TV-side `*DataStore.kt` is mirrored in
`lib/settings/schemas.ts`. `encodeFeature` hard-fails on unknown keys —
that's the schema-drift detector. See `CONTRIBUTING.md`.

## Local Development

```bash
cd web/panel
npm install
cp .env.local.example .env.local   # fill in Supabase URL + anon key
npm run dev
npm test
```

## Tech Stack

- Next.js 15 (App Router)
- React 18
- TypeScript (strict)
- Tailwind CSS
- `@supabase/ssr` + `@supabase/supabase-js`
- Zod (settings validation)
- Vitest (unit tests)

## Vercel Deployment

1. Push to GitHub (this repo).
2. Import in Vercel.
3. Set Root Directory to `web/panel`.
4. Add env vars `NEXT_PUBLIC_SUPABASE_URL` and `NEXT_PUBLIC_SUPABASE_ANON_KEY`.
5. Deploy.
