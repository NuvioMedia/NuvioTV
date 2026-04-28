# AIOMetadata (self-hosted on Hetzner)

Alternative to the Fly deploy at `infra/aiometadata/`. Same upstream image
(`ghcr.io/cedya77/aiometadata`), runs as a `docker compose` stack with local
Postgres and Redis. Fronted by whatever reverse proxy lives on the host
(its config is kept out of this repo — see "Reverse proxy" below). CI
deploys via a self-hosted GitHub Actions runner on the same box.

## One-time setup on the box

Prereqs: Docker Engine + Compose plugin installed, and a user in the
`docker` group that the runner will run as.

### Install the self-hosted runner

```bash
# 1. Get a one-time registration token (60 minutes) from GitHub
TOKEN=$(gh api -X POST /repos/TheMrClaus/OmnioTV/actions/runners/registration-token --jq .token)

# 2. Download and unpack the runner
mkdir -p ~/git/actions-runner && cd ~/git/actions-runner
curl -fsSL -o runner.tar.gz \
  https://github.com/actions/runner/releases/download/v2.334.0/actions-runner-linux-x64-2.334.0.tar.gz
tar xzf runner.tar.gz && rm runner.tar.gz

# 3. Configure (labels matter — workflow targets `self-hosted, aiometadata`)
./config.sh \
  --url https://github.com/TheMrClaus/OmnioTV \
  --token "$TOKEN" \
  --name aiometadata-runner \
  --labels self-hosted,aiometadata \
  --unattended

# 4. Install + start as a systemd service running as the current user
sudo ./svc.sh install "$USER"
sudo ./svc.sh start
sudo ./svc.sh status      # verify it's "active (running)"
```

The runner now polls GitHub over outbound HTTPS — no inbound SSH or open
ports needed. It survives reboots via systemd. To rotate the registration
token or re-register on a new repo, run `./config.sh remove --token <new>`
followed by `./config.sh ...` again.

### Create the host-private override file (optional but usually needed)

The committed `docker-compose.yml` does not publish ports or join any
external network — that wiring is host-specific and stays off the repo.
Put it in `~/aiometadata-override.yml`; the workflow copies it into the
working directory as `docker-compose.override.yml` if present, and Compose
auto-merges it.

See "Reverse proxy / network exposure" below for the two common shapes.

## Reverse proxy / network exposure

The committed `docker-compose.yml` deliberately does **not** publish any
ports or attach to any external network — that wiring depends on how your
host's reverse proxy reaches services and is kept out of the repo.

Drop a `docker-compose.override.yml` in the same directory on the host
(it's gitignored). Compose auto-merges it on `up`. Two common shapes:

```yaml
# Option A: reverse proxy reaches the addon on a shared Docker network
services:
  aiometadata:
    networks:
      - default
      - <your-proxy-network>

networks:
  <your-proxy-network>:
    external: true
    name: <your-proxy-network>
```

```yaml
# Option B: reverse proxy lives on the host and reaches loopback
services:
  aiometadata:
    ports:
      - "127.0.0.1:3232:3232"
```

The proxy resource itself should:
- Front `aiometadata.omnio.tv` (must match `HOST_NAME` in `.env` — Stremio
  clients persist this hostname into saved manifest URLs).
- Terminate TLS via Let's Encrypt (or whatever the proxy provides).
- Be configured as **public / no auth** — Stremio clients can't do
  interactive SSO.

## GitHub Secrets

Four new secrets, plus two that already exist for the Fly deploy:

| Secret | Value |
|---|---|
| `AIOMETADATA_POSTGRES_PASSWORD` | `openssl rand -hex 24` |
| `AIOMETADATA_REDIS_PASSWORD` | `openssl rand -hex 24` |
| `AIOMETADATA_ADMIN_KEY` | `openssl rand -hex 32` |
| `AIOMETADATA_HOST_NAME` | `https://aiometadata.omnio.tv` |
| `TRAKT_CLIENT_ID` | (already exists) |
| `TRAKT_CLIENT_SECRET` | (already exists) |

Provider keys (TMDB / TVDB / Fanart / MDBList / Gemini) are intentionally
absent — same reasoning as the Fly setup, see the parent `infra/aiometadata/README.md`.

Set them with `gh secret set` from the box itself:

```bash
gh secret set AIOMETADATA_POSTGRES_PASSWORD --repo TheMrClaus/OmnioTV --body "$(openssl rand -hex 24)"
gh secret set AIOMETADATA_REDIS_PASSWORD    --repo TheMrClaus/OmnioTV --body "$(openssl rand -hex 24)"
gh secret set AIOMETADATA_ADMIN_KEY         --repo TheMrClaus/OmnioTV --body "$(openssl rand -hex 32)"
gh secret set AIOMETADATA_HOST_NAME         --repo TheMrClaus/OmnioTV --body "https://aiometadata.omnio.tv"
```

## First deploy

Manually trigger the workflow from the Actions tab (`workflow_dispatch`) or
edit any file under `infra/aiometadata-selfhost/` and push to `dev`. The
workflow runs on the self-hosted runner: it renders `.env` from secrets,
copies in the override (if present), and runs `docker compose pull && up -d`
from the runner's checkout directory. Named volumes
(`aiometadata_postgres_data`, etc.) live under `/var/lib/docker/volumes/`
so they survive across runs.

Verify after the run completes:

```bash
docker ps --filter "name=aiometadata"     # three containers, all healthy
docker logs -f aiometadata-aiometadata-1  # addon logs
curl -s http://127.0.0.1:3232/configure/ | head -20   # only works if override binds the port
```

## Cutover from Fly

When you're ready to retire the Fly deploy:

1. **Decide on data migration.** Two options:
   - *Fresh start* (easier) — every existing user re-saves their config in
     the Android app on first launch. Their old `aio_uuid` becomes orphaned;
     `aio_metadata_links` in Supabase gets overwritten when they save again.
   - *Preserve UUIDs* — `pg_dump` the Neon database, `pg_restore` into the
     local Postgres before flipping DNS. Keeps existing Android sessions
     working without user action.
2. **Flip DNS** — `aiometadata.omnio.tv` A/AAAA records → Pangolin's exit
   IPs. Wait for propagation; both Fly and the Hetzner box will serve the
   hostname during the overlap, with Pangolin TLS on the new path.
3. **Decommission Fly** — `flyctl apps destroy nuviotv-aiometadata` once
   you've confirmed the Hetzner box is serving real traffic for at least a
   few hours. Disable or delete `.github/workflows/deploy-aiometadata.yml`
   so it can't accidentally redeploy.

## Updating the addon image

To pull a new upstream version, edit the `image:` tag in
`docker-compose.yml` (use a SHA-suffixed tag from
https://github.com/cedya77/aiometadata/pkgs/container/aiometadata, never a
floating tag like `latest` or a date tag — those can be re-pushed) and
commit. CI runs `docker compose pull && up -d`, which recreates only the
`aiometadata` service. `~/aiometadata-override.yml` on the host is never
touched by the workflow.

## Backups

`pg_dump` nightly to a Hetzner Storage Box via cron on the host — the volume
itself is on the local SSD with no offsite copy. Restic to a Storage Box is
about €3/mo for 1TB and is the cheapest credible disaster-recovery target.
