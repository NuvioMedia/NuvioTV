# AIOMetadata (Fly.io)

Runs the upstream [`cedya77/aiometadata`](https://github.com/cedya77/aiometadata)
addon. The Android app talks to this instance over HTTPS; Supabase only stores
the mapping `supabase_user_id → aio_uuid`.

## One-time setup

```bash
# From this directory
fly launch --copy-config --no-deploy   # change `app` in fly.toml first

# Data stores (pick one Postgres source)
fly postgres create --name nuviotv-aiometadata-db
fly postgres attach --app nuviotv-aiometadata nuviotv-aiometadata-db
# -> writes DATABASE_URL; upstream expects DATABASE_URI instead:
fly secrets set DATABASE_URI="$(fly secrets list | awk '/DATABASE_URL/ {print $2}')"

# Redis (Upstash free tier is the cheapest path)
fly redis create --name nuviotv-aiometadata-cache
fly secrets set REDIS_URL="redis://default:PASSWORD@HOST:PORT"

# App-level provider keys (users can still override per-config)
fly secrets set \
  TMDB_API_KEY=... \
  TVDB_API_KEY=... \
  FANART_API_KEY=... \
  MDBLIST_API_KEY=... \
  GEMINI_API_KEY=... \
  ADMIN_KEY="$(openssl rand -hex 32)"

# Public hostname (for Stremio manifest URLs)
fly secrets set HOST_NAME=https://nuviotv-aiometadata.fly.dev
```

## Deploy / update

```bash
fly deploy                      # uses ghcr.io/cedya77/aiometadata:testing
fly logs                        # tail
fly ssh console                 # shell in
fly status                      # machine health
```

## Pin the image

The config uses the rolling `testing` tag. After verifying a deploy works,
bump `image = ` in `fly.toml` to a dated tag (e.g.
`v.testing.20260420.1`) so future deploys are deterministic.

## Android wiring

Add to `local.properties`:

```properties
AIOMETADATA_BASE_URL=https://nuviotv-aiometadata.fly.dev/
```

The app posts to `/api/config/save` (first setup) or
`/api/config/update/{uuid}` (edits), then stores `{user_id → aio_uuid}` in
Supabase's `aio_metadata_links` table (see
`supabase/migrations/005_aio_metadata.sql`).
