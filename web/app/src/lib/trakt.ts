// Trakt OAuth + scrobble helpers. Tokens live in localStorage scoped per
// profile so that linked accounts on a shared device don't bleed history.
// The token exchange runs on the Edge function /api/trakt/token where the
// client_secret lives — the SPA never sees the secret.

export const TRAKT_AUTHORIZE = "https://trakt.tv/oauth/authorize";

export interface TraktTokenSet {
  access_token: string;
  refresh_token: string;
  expires_at: number; // unix ms
  scope: string;
  token_type: string;
}

interface RawTraktToken {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  created_at: number;
  scope: string;
  token_type: string;
}

function tokenKey(profileId: number): string {
  return `omnio.trakt.${profileId}`;
}

function stateKey(profileId: number): string {
  return `omnio.trakt.state.${profileId}`;
}

export function loadTraktTokens(profileId: number): TraktTokenSet | null {
  try {
    const raw = localStorage.getItem(tokenKey(profileId));
    if (!raw) return null;
    return JSON.parse(raw) as TraktTokenSet;
  } catch {
    return null;
  }
}

export function saveTraktTokens(profileId: number, raw: RawTraktToken): TraktTokenSet {
  const tokens: TraktTokenSet = {
    access_token: raw.access_token,
    refresh_token: raw.refresh_token,
    expires_at: (raw.created_at + raw.expires_in) * 1000,
    scope: raw.scope,
    token_type: raw.token_type,
  };
  localStorage.setItem(tokenKey(profileId), JSON.stringify(tokens));
  return tokens;
}

export function clearTraktTokens(profileId: number): void {
  localStorage.removeItem(tokenKey(profileId));
}

export function buildAuthorizeUrl(opts: {
  clientId: string;
  redirectUri: string;
  state: string;
}): string {
  const params = new URLSearchParams({
    response_type: "code",
    client_id: opts.clientId,
    redirect_uri: opts.redirectUri,
    state: opts.state,
  });
  return `${TRAKT_AUTHORIZE}?${params.toString()}`;
}

export function startTraktAuth(profileId: number): void {
  const clientId = import.meta.env.VITE_TRAKT_CLIENT_ID;
  if (!clientId) {
    throw new Error(
      "VITE_TRAKT_CLIENT_ID is not set. Add it to .env.local — see Trakt OAuth app settings."
    );
  }
  const redirectUri = `${window.location.origin}/auth/trakt/callback`;
  const state = `${profileId}:${crypto.randomUUID()}`;
  sessionStorage.setItem(stateKey(profileId), state);
  window.location.assign(
    buildAuthorizeUrl({ clientId, redirectUri, state })
  );
}

export function consumeStoredState(profileId: number): string | null {
  const v = sessionStorage.getItem(stateKey(profileId));
  if (v) sessionStorage.removeItem(stateKey(profileId));
  return v;
}

interface ExchangeRequest {
  code: string;
  redirect_uri: string;
}

export async function exchangeTraktCode(req: ExchangeRequest): Promise<RawTraktToken> {
  const response = await fetch("/api/trakt/token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...req, grant_type: "authorization_code" }),
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Trakt token exchange failed (${response.status}): ${body}`);
  }
  return (await response.json()) as RawTraktToken;
}

export async function refreshTraktToken(refreshToken: string): Promise<RawTraktToken> {
  const response = await fetch("/api/trakt/token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ refresh_token: refreshToken, grant_type: "refresh_token" }),
  });
  if (!response.ok) throw new Error(`Trakt refresh failed (${response.status})`);
  return (await response.json()) as RawTraktToken;
}

export async function getValidTraktToken(profileId: number): Promise<string | null> {
  let tokens = loadTraktTokens(profileId);
  if (!tokens) return null;
  // Refresh 60 seconds before expiry to dodge clock skew.
  if (tokens.expires_at - Date.now() < 60_000) {
    try {
      const fresh = await refreshTraktToken(tokens.refresh_token);
      tokens = saveTraktTokens(profileId, fresh);
    } catch (err) {
      console.warn("Trakt refresh failed", err);
      clearTraktTokens(profileId);
      return null;
    }
  }
  return tokens.access_token;
}

// Scrobble helpers — Trakt uses progress as 0..100 (percentage).

interface ScrobbleArgs {
  profileId: number;
  contentType: string; // "movie" | "series"
  imdbId: string; // tt-prefixed
  season?: number | null;
  episode?: number | null;
  progressPct: number; // 0..100
}

async function trakt(
  endpoint: string,
  body: unknown,
  profileId: number
): Promise<Response | null> {
  const clientId = import.meta.env.VITE_TRAKT_CLIENT_ID;
  if (!clientId) return null;
  const accessToken = await getValidTraktToken(profileId);
  if (!accessToken) return null;

  const response = await fetch(`https://api.trakt.tv${endpoint}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "trakt-api-version": "2",
      "trakt-api-key": clientId,
      authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify(body),
  });
  return response;
}

function scrobbleBody(args: ScrobbleArgs) {
  if (args.contentType === "series" && args.season != null && args.episode != null) {
    return {
      show: { ids: { imdb: args.imdbId } },
      episode: { season: args.season, number: args.episode },
      progress: clampPct(args.progressPct),
    };
  }
  return {
    movie: { ids: { imdb: args.imdbId } },
    progress: clampPct(args.progressPct),
  };
}

function clampPct(v: number): number {
  if (!Number.isFinite(v)) return 0;
  return Math.min(100, Math.max(0, v));
}

export async function scrobbleStart(args: ScrobbleArgs) {
  return trakt("/scrobble/start", scrobbleBody(args), args.profileId);
}

export async function scrobblePause(args: ScrobbleArgs) {
  return trakt("/scrobble/pause", scrobbleBody(args), args.profileId);
}

export async function scrobbleStop(args: ScrobbleArgs) {
  return trakt("/scrobble/stop", scrobbleBody(args), args.profileId);
}
