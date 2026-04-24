# NuvioTV Web Panel

Web control panel for NuvioTV accounts. Lets a logged-in user view and (in v2)
edit every setting the TV app exposes: profiles, addons, plugins, integrations,
collections, home layout, playback, linked devices.

**Production:** https://account.omnio.tv

Backed by the same self-hosted Supabase the TV app uses.

## Status — v1 (read-only MVP)

What ships:

- Email/password login + signup (Supabase Auth), with a redirect-aware
  middleware that gates every route except `/login`.
- Profile picker at `/profiles` listing every profile on the account.
- Profile-scoped shell at `/p/[profileId]` with a sidebar nav.
- Read-only views for: Overview, Addons, Plugins, Integrations status,
  Collections, Library + Watched, Settings (theme/layout/playback/trakt),
  Linked Devices.
- Migration `supabase/migrations/008_profile_settings_partial_merge.sql` —
  partial-merge RPC, ready for v2 writes.
- Envelope + Zod schemas mirroring the 10 TV-side `*DataStore.kt` files,
  with 14 round-trip tests.

What does **not** ship in v1:

- Any write — every form / button is read-only or surfaces "lands in v2".
- Trakt OAuth on web (still TV-side).
- Plugin JS code editing.
- Collections image uploads (external URLs only).

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
