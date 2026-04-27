# OmnioTV — Web App (`web/app`)

Browser companion to the Android TV app. Vite + React + TanStack Router/Query +
Tailwind. Plays Stremio-addon-resolved streams via `<video>` + `hls.js`. All
heavy lifting (demux, decode, subs) runs in the browser; the only server-side
component is a small Vercel Edge proxy that strips CORS for addon JSON.

## Phase 1 (this scaffold)

- Email/password auth via Supabase
- Profile picker (reads `profiles` table, same RLS as Android client)
- Home page with three Cinemeta catalog rows
- Detail page (movie + series with episode list)
- Stream picker with browser-codec scoring
- Player route: `<video>` + `hls.js`, watch progress synced to Supabase

Phase 2+ (per `/Users/marcelloc/.claude/plans/make-a-plan-on-majestic-pascal.md`)
adds full search/discover, audio/sub track switching, Trakt scrobble, JASSUB
subtitles, plugin Web Worker, and the optional Fly.io transcoder.

## Local dev

```bash
cd web/app
npm install
cp .env.example .env.local   # fill in VITE_SUPABASE_URL + ANON_KEY
npm run dev                  # http://localhost:5173
```

To exercise the Edge proxy locally, run the Vercel CLI: `npx vercel dev`.
Without it, `/api/proxy` and `/api/probe` won't be served, and addon requests
will fail with CORS errors. Set `VITE_PROXY_URL=https://your-proxy.example.com`
in `.env.local` to point at a deployed proxy instead.

## Deploy

Production target is Vercel. Push to a branch, link the project to the
`web/app` directory, and set:

- `VITE_SUPABASE_URL`
- `VITE_SUPABASE_ANON_KEY`

The Edge functions in `api/` deploy automatically.

## Architecture

See [`/Users/marcelloc/.claude/plans/make-a-plan-on-majestic-pascal.md`](../../../.claude/plans/make-a-plan-on-majestic-pascal.md)
for the full architecture, codec strategy, capacity/cost, and phased rollout.

Key facts:

- Supabase schema is unchanged. Web is a peer client of the Android app.
- Stream payload bytes flow addon-CDN → user; Vercel only proxies addon JSON.
- Codec compatibility is scored client-side (`@omnio/shared/codec`) and confirmed
  via a HEAD probe (`/api/probe`).
- AC3/DTS/MKV streams are flagged in the picker; Phase 3 adds an opt-in
  Fly.io ffmpeg transcoder for users who insist on those releases.
