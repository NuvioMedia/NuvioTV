# AIOMetadata (Fly.io)

Runs the upstream [`cedya77/aiometadata`](https://github.com/cedya77/aiometadata)
addon. The Android app talks to this instance over HTTPS; Supabase only stores
the mapping `supabase_user_id → aio_uuid`.

## Secrets management

Secrets live in **GitHub Secrets** and are pushed to Fly on every deploy by
`.github/workflows/deploy-aiometadata.yml`. You never need to run
`fly secrets set` manually — just update the GitHub Secret and re-run the
workflow (or push a change to `infra/aiometadata/`).

| GitHub Secret | Fly env var | Notes |
|---|---|---|
| `FLY_API_TOKEN` | — | Fly personal token for CI auth |
| `AIOMETADATA_DATABASE_URI` | `DATABASE_URI` | Postgres connection string (Neon pooled) |
| `AIOMETADATA_REDIS_URL` | `REDIS_URL` | `redis://` or `rediss://` URL |
| `AIOMETADATA_HOST_NAME` | `HOST_NAME` | Public base URL, e.g. `https://aiometadata.omnio.tv` |
| `AIOMETADATA_ADMIN_KEY` | `ADMIN_KEY` | Random hex, `openssl rand -hex 32` |
| `TRAKT_CLIENT_ID` | `TRAKT_CLIENT_ID` | OAuth client credential, shared by design |
| `TRAKT_CLIENT_SECRET` | `TRAKT_CLIENT_SECRET` | OAuth client credential, shared by design |

### Provider API keys are NOT pushed to Fly

`TMDB_API_KEY`, `TVDB_API_KEY`, `FANART_API_KEY`, `MDBLIST_API_KEY`, and
`GEMINI_API_KEY` must **not** be set as Fly secrets on this app. The upstream
`cedya77/aiometadata` addon reads those env vars and bakes them into the
public `/configure/` form, which would expose them to anyone who hits the URL
(this is especially dangerous for billing-enabled keys like Gemini).

Each user supplies their own keys through the Android settings flow; those
are stored per-UUID in Postgres and travel with the user's saved config.
RPDB always defaults to the free community key (`t0-free-rpdb`) on the
client side — see `AioMetadataDefaultConfig.kt`.

(`TMDB_API_KEY` is still kept as a GitHub Secret because the Android build
itself uses it via `app/build.gradle.kts` and `.github/workflows/beta-release.yml`
— just don't push it to Fly.)

## One-time infrastructure setup

Only needed when provisioning a brand-new Fly app — not for routine deploys.

```bash
# From this directory
fly launch --copy-config --no-deploy   # change `app` in fly.toml first

# Volume for upstream's on-disk cache (the fly.toml mounts it at /data)
fly volumes create aiometadata_data --app nuviotv-aiometadata --region iad --size 1

# Postgres — Neon free tier works great; copy the pooled connection string
# Redis — Fly's managed Upstash (or Upstash direct). Must be redis:// or rediss://, not https://.
fly redis create --name nuviotv-aiometadata-cache --region iad
```

Then populate the GitHub Secrets listed above and trigger the deploy workflow.

## Deploy / update

```bash
fly deploy                      # uses ghcr.io/cedya77/aiometadata:testing
fly logs                        # tail
fly ssh console                 # shell in
fly status                      # machine health
```

## Pin the image

The upstream builds both a date-based tag (`v.testing.YYYYMMDD.N`) and a
commit-SHA-suffixed variant (`v.testing.YYYYMMDD.N-<gitsha>`); list them at
https://github.com/cedya77/aiometadata/pkgs/container/aiometadata. Pin
`image = ` in `fly.toml` to the SHA-suffixed form (or an `@sha256:` digest)
so the tag can't be re-pushed out from under you. The version reported by
`/health` (`0.0.0-testing.YYYYMMDD.N`) identifies the build but is not
itself a valid registry tag.

## Custom domain — `aiometadata.omnio.tv`

The upstream bakes `HOST_NAME` into every Stremio manifest URL it emits, and
those URLs get persisted in clients. Front the Fly instance with a custom
subdomain so you're not dependent on the `.fly.dev` hostname forever.

```bash
# 1. Register the hostname with Fly (issues a Let's Encrypt cert)
fly certs add aiometadata.omnio.tv --app nuviotv-aiometadata

# 2. Fly prints the DNS records you need to add at your provider. Typically:
#      A     aiometadata   -> Fly's IPv4
#      AAAA  aiometadata   -> Fly's IPv6
#    Add them in omnio.tv's zone.

# 3. Wait for cert + DNS propagation
fly certs show aiometadata.omnio.tv --app nuviotv-aiometadata

# 4. Flip HOST_NAME so new manifests reference the custom domain
fly secrets set --app nuviotv-aiometadata \
  HOST_NAME="https://aiometadata.omnio.tv"

# 5. Update the GitHub Secret used by the Android build
#      AIOMETADATA_BASE_URL=https://aiometadata.omnio.tv/
```

The `.fly.dev` hostname keeps working as a fallback, but you're no longer
locked to it.

## Android wiring

Two options:

- **GitHub Secrets** (preferred for CI release builds). Set a repo secret
  named `AIOMETADATA_BASE_URL` to `https://aiometadata.omnio.tv/`. The
  release workflow maps it to an env var, which `build.gradle.kts` reads
  into `BuildConfig.AIOMETADATA_BASE_URL`.
- **`local.properties`** (for local debug builds). Add:

  ```properties
  AIOMETADATA_BASE_URL=https://aiometadata.omnio.tv/
  ```

The app posts to `/api/config/save` (first setup) or
`/api/config/update/{uuid}` (edits), then stores `{user_id → aio_uuid}` in
Supabase's `aio_metadata_links` table (see
`supabase/migrations/005_aio_metadata.sql`).
