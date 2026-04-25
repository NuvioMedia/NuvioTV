# OmnioTV regional infrastructure plan

## Context

User is in **Spain**, with **worldwide** end-users (the Android TV app is shipped via GitHub Releases publicly). Most self-hosted infra is currently in **Northern Virginia (`iad`)**, which is suboptimal for the user (~150 ms RTT) and provides no clear benefit for non-US end-users. The aiometadata service in particular sits on the hot path for any catalog/meta call from rows sourced through it ([infra/aiometadata/fly.toml:7](infra/aiometadata/fly.toml#L7) pins `primary_region = 'iad'`).

Goal: pick the best regional placement for each layer of the stack, balancing (a) the user's own developer/admin experience in Spain, (b) end-user perceived latency worldwide, and (c) cost — staying in the cheap/free tier of every provider.

**The strategy in one sentence**: pin every self-hosted piece to **Madrid** (or Frankfurt where Madrid isn't available), and let **Cloudflare's free global edge** cover non-EU users. Multi-region replication is rejected as not worth the cost/complexity for this scale.

---

## Recommended target region: Madrid (`mad` / `mad1` / `eu-south-2`)

| Provider | Madrid SKU | Fallback if Madrid unavailable |
|---|---|---|
| Fly.io | `mad` | `cdg` (Paris) |
| Vercel | `mad1` | `cdg1` (Paris) |
| Neon Postgres | `eu-central-1` (Frankfurt) — Madrid not GA | — |
| Upstash Redis | `eu-west-1` (Ireland) or `eu-central-1` (Frankfurt) | — |
| Cloudflare DNS | global edge (POP-routed) | — |

**Why Madrid over Frankfurt:**
- ~5 ms RTT from Spain vs ~30 ms from Frankfurt — meaningful for admin/developer experience
- For end-users the choice is statistically irrelevant: Madrid → US East ≈ 110 ms, Frankfurt → US East ≈ 95 ms; Asia is ≥250 ms from anywhere in Europe and is solved by CDN, not by region choice
- Symbolic "home" region for a Spain-led project

**Why not multi-region:**
- Doubles per-machine runtime cost
- Requires DB write-replication (cedya77/aiometadata may not support it cleanly)
- Cloudflare's 200+ POPs in front of a single-region origin gives ~80 % of the benefit for free

---

## Per-service plan

### 1. Fly aiometadata machine — **MOVE iad → mad**

**Current** ([fly.toml:7](infra/aiometadata/fly.toml#L7)): `primary_region = 'iad'`, single machine, `min_machines_running = 1`, volume `aiometadata_data` mounted at `/data` (region-locked to iad).

**Target**: `primary_region = 'mad'`, same VM size (`shared-cpu-1x@512mb`), same auto-stop/min config.

**Action**:
```bash
# 1. Create a new volume in Madrid
fly volumes create aiometadata_data --app nuviotv-aiometadata --region mad --size 1

# 2. Edit infra/aiometadata/fly.toml — change primary_region to 'mad'

# 3. Deploy. Fly will create a new machine in mad pinned to the new volume.
fly deploy --config infra/aiometadata/fly.toml --app nuviotv-aiometadata

# 4. Verify the new machine is running, the old one is gone:
fly machine list --app nuviotv-aiometadata

# 5. Destroy the old iad volume once verified:
fly volumes list --app nuviotv-aiometadata
fly volumes destroy <old-iad-vol-id>
```

**Volume data**: the mounted `/data` is upstream cedya77/aiometadata's on-disk cache. It rebuilds itself from upstream sources on cache miss — **don't bother migrating its contents**. Accept ~15 min of cold-cache slowness post-cutover.

**Effort**: 10 min. **Impact**: ~150 ms latency reduction from Spain. **Cost delta**: zero.

---

### 2. aiometadata Postgres (currently Neon, per README)

**Current**: Region unknown. Connection string is in `DATABASE_URI` Fly secret. To find out, run:
```bash
fly ssh console --app nuviotv-aiometadata
echo "$DATABASE_URI" | sed -E 's|.*@([^/:]+).*|\1|'   # prints the host
exit
```

The hostname tells you the region:
- `*.us-east-2.aws.neon.tech` → US Ohio → **migrate**
- `*.eu-central-1.aws.neon.tech` → Frankfurt → **keep as-is**
- `*.eu-west-2.aws.neon.tech` → London → **keep as-is**
- `*.eu-west-1.aws.neon.tech` → Dublin → **keep as-is**

**If migration is needed** (Neon → Neon, EU region):
```bash
# 1. Create a new Neon project in eu-central-1 (Frankfurt) via Neon dashboard.
#    Free tier: 0.5 GB storage, autoscale, fine for this load.

# 2. Dump and restore (database is small — likely <100 MB):
pg_dump --format=custom --no-owner --no-acl \
  "$OLD_DATABASE_URI" > aio.dump
pg_restore --no-owner --no-acl --dbname="$NEW_DATABASE_URI" aio.dump

# 3. Update GitHub repo secret AIOMETADATA_DATABASE_URI to the new pooled
#    connection string.

# 4. Trigger the deploy workflow:
gh workflow run deploy-aiometadata.yml --repo TheMrClaus/OmnioTV
# (or push any change to infra/aiometadata/)

# 5. After verifying, archive the old Neon project.
```

**Downtime**: 1-2 min during cutover (write the new connection string, redeploy, brief restart).

**Effort**: 30 min. **Impact**: removes trans-Atlantic Postgres latency on every aiometadata request that touches the DB (~100 ms saved on cache-miss paths). **Cost delta**: zero.

---

### 3. aiometadata Redis (currently Upstash, per README)

**Current**: `REDIS_URL` secret. Same `fly ssh` trick to find host. Likely `fly-iad-redis.upstash.io` or `*.upstash.io`.

**Target**: Upstash region nearest Madrid. Upstash supports `eu-west-1` (Ireland) and `eu-central-1` (Frankfurt). Neither is Madrid but both are sub-30 ms from a Madrid Fly machine — fine.

**Action**:
```bash
# Option A: Fly's managed Upstash (simplest if already on it)
fly redis create --name nuviotv-aiometadata-cache-eu --region fra
# (the EU region for fly redis is fra; mad isn't supported)

# Option B: Upstash direct — sign up at upstash.com, create a database in
# eu-central-1, copy the rediss:// URL.

# Update GitHub secret AIOMETADATA_REDIS_URL to the new URL, redeploy.
# Old cache is empty after switch — first 5-10 min of requests go cold to upstreams.
```

**Effort**: 10 min. **Impact**: removes trans-Atlantic Redis hops; brief cache-warmup pain after switch. **Cost delta**: zero (free tier).

---

### 4. Supabase project — **CHECK FIRST, MAYBE MIGRATE**

This is the highest-risk decision in the plan. Supabase region is set at project creation and **cannot be changed in-place**.

**Find your current region**: open https://supabase.com/dashboard/project/ttihlwxoejxoucehxstl → Settings → General → "Region" field.

**Decision matrix:**

| Current region | Action | Reason |
|---|---|---|
| `eu-west-1` (Ireland) | **Keep** | Already EU, ~25 ms to Madrid |
| `eu-west-2` (London) | **Keep** | Already EU |
| `eu-west-3` (Paris) | **Keep** | Already EU, ~20 ms to Madrid |
| `eu-central-1` (Frankfurt) | **Keep** | Already EU |
| `eu-central-2` (Zurich) | **Keep** | Already EU |
| `us-east-*` / `us-west-*` | **Migrate** | ~150 ms Atlantic crossing on every request |
| `ap-*` / `sa-*` / `af-*` / `me-*` | **Migrate** | Long latencies from Spain |

**If migration is needed** (heavy lift, ~3-4 hours including verification):

```bash
# 1. Create a NEW Supabase project in eu-west-3 (Paris — closest to Madrid).
#    The new project gets a new URL: https://NEWID.supabase.co.

# 2. Apply ALL migrations to the new project, in order (see supabase/migrations/):
#    Use the SQL editor or `supabase db push` from the CLI.

# 3. Run all 008+ migrations including 008_profile_settings_partial_merge.sql
#    that you applied recently.

# 4. Dump the data tables from the old project. Keys — anything that has
#    real user data: profiles, profile_settings, addons, plugins,
#    library_items, watch_progress, watched_items, linked_devices,
#    sync_codes, collections, aio_metadata_links, avatar_catalog.
#
#    Use the Supabase dashboard "Database → Backups" feature, or:
pg_dump --format=plain --no-owner --no-acl \
  --schema=public --data-only \
  "postgres://postgres:[OLD_PASSWORD]@db.OLDID.supabase.co:5432/postgres" \
  > supabase_data.sql

# 5. Restore data into the new project:
psql "postgres://postgres:[NEW_PASSWORD]@db.NEWID.supabase.co:5432/postgres" \
  < supabase_data.sql

# 6. Migrate auth.users — Supabase has a paid feature for this; on free tier,
#    every user must re-sign-up OR you ship a migration that uses the admin
#    API to recreate auth.users entries with their UUIDs preserved (so RLS
#    policies still match). This is the painful part.
#
#    For a small user base, the simpler answer is: tell users to sign in
#    again, accept the disruption, ship a one-time `pgsupabase auth users
#    create` script for each existing user_id.

# 7. Update the consumers:
#    - Vercel: Project → Settings → Env Vars → update both
#      NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY.
#      Trigger a manual redeploy (env var changes don't auto-redeploy).
#    - GitHub Secrets: SUPABASE_URL, SUPABASE_ANON_KEY (used by the Android
#      release workflow).
#    - infra/aiometadata: only impacted if it stores anything in the old
#      Supabase — currently it does NOT (it uses Neon).
#    - Trigger a new beta release so existing TVs pick up the new URL.
#      Old TVs continue to fail auth until they update.

# 8. Update CLAUDE.md, README files, V2_PLAN.md to reference the new
#    Supabase project ID.

# 9. Verify everything end-to-end before destroying the old project.
#    Keep the old project around for a week before deletion.
```

**If you decide it's not worth it**: a Supabase project in `us-east-1` or similar adds ~150 ms to every Supabase call. For the panel's Server Actions that's ~1-2 round trips per save (300-600 ms felt), and for the TV during sync it's per-RPC-call. Annoying but not broken. The decision becomes "is 150 ms perceived save latency worth a 3-hour migration with risk?"

**Recommendation**: if currently outside EU, **migrate**. If in any EU region, leave it alone.

---

### 5. Vercel web panel — **PIN preferredRegion**

**Current** ([web/panel/vercel.json](web/panel/vercel.json)): no region config. Server Components + Server Actions default to Vercel's "Washington DC" region (iad1).

**Target**: pin to the same region as Supabase post-migration. If Supabase ends up in `eu-west-3` (Paris), set Vercel preferredRegion to `cdg1` (Paris) so Server Actions hit Supabase locally. If Supabase is in `eu-west-1` (Dublin), use `dub1`. Always co-locate Vercel functions with Supabase.

**Action**: Add at the top of [web/panel/app/layout.tsx](web/panel/app/layout.tsx) (or any Server Component file that needs it):

```ts
// Co-locate Server Components and Server Actions with the Supabase region
// to keep RPC and PostgREST calls in-region.
export const preferredRegion = ['cdg1']; // or 'mad1', 'fra1', 'dub1', etc.
```

For App Router this propagates to child routes. Alternatively, set per-page on every server-side file under `app/`.

Static pages and middleware are unaffected — they remain global edge-served.

**Effort**: 5 min. **Impact**: 100-150 ms saved on every panel save action. **Cost delta**: zero.

---

### 6. Cloudflare in front of `aiometadata.omnio.tv` — **THE GLOBAL-USERS LEVER**

This is what makes single-region aiometadata acceptable for worldwide users.

**Action**:
```
1. Sign up for a free Cloudflare account (or use existing).
2. Add omnio.tv as a zone (free plan).
3. Update the omnio.tv nameservers at your registrar to point at the
   Cloudflare-assigned nameservers.
4. In CF DNS, recreate aiometadata.omnio.tv as a CNAME or A/AAAA record
   pointing at the Fly app, with the orange-cloud (proxied) toggle ON.
5. Note: account.omnio.tv (Vercel) and any other subdomains can stay
   un-proxied (grey-cloud) since Vercel already does global edge.
6. SSL: set CF SSL mode to "Full (strict)". Fly's auto-provisioned cert
   handles origin TLS.
7. Re-issue the Fly cert if needed:
   fly certs add aiometadata.omnio.tv --app nuviotv-aiometadata
   fly certs show aiometadata.omnio.tv --app nuviotv-aiometadata
8. Configure caching:
   - Caching → Configuration → "Browser Cache TTL" = "Respect Existing Headers"
   - Page Rules:
     • aiometadata.omnio.tv/stremio/*/catalog/* → "Cache Level: Cache Everything",
       Edge Cache TTL: 1 day
     • aiometadata.omnio.tv/stremio/*/meta/* → same
9. Test that an Asian user (use a free VPN to a Tokyo exit node) gets
   sub-100 ms response on a previously-cached catalog URL.
```

**Verify aiometadata's response headers** before flipping page rules: `curl -I https://aiometadata.omnio.tv/stremio/{uuid}/catalog/movie/popular.json`. If the upstream returns `Cache-Control: private, no-store`, Cloudflare respects that — the page rules above force-override it. If it returns sane public cache headers, the page rules just reinforce them.

**Effort**: 30 min. **Impact**: global users get edge-cached responses from CF's nearest POP (~50 ms anywhere on Earth) instead of crossing oceans to Madrid. Your Fly machine takes drastically less load. **Cost delta**: zero (CF free plan).

---

### 7. Things to leave alone

- **GitHub Releases / APK distribution** — already global via GitHub's CDN. No action.
- **Static assets on Vercel** (images, JS bundles) — already global edge by default. No action.
- **Vercel project itself** — only Server Components/Actions need region pinning (see #5). Static pages are global.
- **Third-party APIs** (TMDB, Trakt, AnimeSkip, MDBList, ARM, GitHub API) — out of your control. Their CDNs handle global routing.
- **Stremio addons users install** (Cinemeta, OpenSubtitles, etc.) — out of your control. Each addon publisher owns their hosting.
- **Supabase Storage `avatars` bucket** — bound to the Supabase project's region. Migrates when/if Supabase project migrates.
- **`infra/aiometadata/.github/workflows/deploy-aiometadata.yml`** — already region-agnostic; deploys whatever fly.toml says.

---

## Migration sequence (recommended order)

Do these in order — each step is independently reversible and the sequence minimizes user-visible disruption:

| # | Step | Downtime | Risk | Effort |
|---|---|---|---|---|
| 1 | Cloudflare DNS in front of `aiometadata.omnio.tv` | None (DNS propagation only) | Low | 30 min |
| 2 | Fly machine: `iad` → `mad` (new volume, fresh cache) | ~5 min while machine boots in mad | Low | 10 min |
| 3 | Redis: move to `eu-central-1` Upstash | Brief cache-empty window (~5-10 min slow responses) | Low | 10 min |
| 4 | Postgres (aiometadata's Neon): move to EU if not already | 1-2 min during cutover | Medium (data migration) | 30 min |
| 5 | Vercel `preferredRegion` pinned to nearest-Supabase region | Auto-deploy on next push | Low | 5 min |
| 6 | (Conditional) Supabase project migration to EU | Real disruption — see #4 above | High (auth migration is the painful bit) | 3-4 hours |

The first five are safe and combined will deliver most of the perceptible improvement. Step 6 is high-effort and only justified if Supabase is currently in the US/elsewhere.

---

## Files to be modified

- [`infra/aiometadata/fly.toml`](infra/aiometadata/fly.toml) — `primary_region = 'iad'` → `primary_region = 'mad'`
- [`web/panel/app/layout.tsx`](web/panel/app/layout.tsx) — add `export const preferredRegion = [...]`
- GitHub repo Secrets (no file): `AIOMETADATA_DATABASE_URI`, `AIOMETADATA_REDIS_URL` if migrated
- GitHub repo Secrets (no file): `SUPABASE_URL`, `SUPABASE_ANON_KEY` if Supabase migrated
- Vercel dashboard env vars (no file): same as above
- Cloudflare dashboard (no file): zone, page rules
- DNS at registrar (no file): nameservers + records
- [`README.md`](README.md), [`web/panel/README.md`](web/panel/README.md), [`infra/aiometadata/README.md`](infra/aiometadata/README.md) — region notes updated to reflect the new state

---

## Verification

After each step:

```bash
# Latency from Spain to Fly:
time curl -s -o /dev/null -w "%{time_total}\n" https://aiometadata.omnio.tv/health

# Latency from Spain to Supabase:
time curl -s -o /dev/null -w "%{time_total}\n" "$NEXT_PUBLIC_SUPABASE_URL/rest/v1/"

# Verify Cloudflare is fronting (look for cf-cache-status header):
curl -sI https://aiometadata.omnio.tv/stremio/{uuid}/manifest.json | grep -i 'cf-\|server'

# Cold-cache vs hot-cache catalog test:
URL="https://aiometadata.omnio.tv/stremio/{uuid}/catalog/movie/popular.json"
time curl -s -o /dev/null "$URL"   # cold
time curl -s -o /dev/null "$URL"   # hot — should be ~10x faster

# Global latency check (free tool):
# https://check-host.net/check-http?host=https://aiometadata.omnio.tv/health
# https://www.dotcom-tools.com/ — checks from 25+ global locations
```

End-to-end test from the TV emulator after all steps complete:
1. Sign in to the OmnioTV app — Supabase auth should feel snappy from Spain
2. Open a row sourced from aiometadata — first-time load goes through CF→Fly→TMDB; subsequent loads are CF edge cache
3. Open the web panel at `account.omnio.tv` from Spain — page should render in <500 ms
4. Save a setting in the panel — should land in <300 ms felt latency

---

## What you give up by NOT going multi-region

If a real user in Sydney complains that browsing is slow:
- The catalog JSON crosses Madrid → Sydney even when CF-cached because the first hit per Cloudflare POP still fetches from origin (Madrid). Subsequent users at the same POP see edge cache.
- The actual stream playback is unaffected — that talks to whatever CDN the source addon points at, never your Fly.
- Realistic perceived impact: first row load ~600 ms, subsequent <100 ms. Acceptable for an addon-based metadata layer.

If this becomes a real problem later, multi-region Fly is a 1-day project (clone the machine to `syd`/`nrt`/`sin`, set up Postgres read replicas, add machine selection logic). Not worth pre-empting now.
