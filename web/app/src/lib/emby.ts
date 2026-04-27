// Emby web client. Same envelope keys as the Android app
// (emby_credentials.{emby_server_url, emby_api_key, emby_user_id}) so the
// browser can pick up creds the user already configured on TV.

import {
  decodeStringFeature,
  encodeStringFeature,
  pullProfileSettings,
  pushFeature,
} from "./profileSettings";

export interface EmbyCreds {
  serverUrl: string;
  apiKey: string;
  userId: string;
}

const FEATURE_KEY = "emby_credentials";

const DEVICE_ID =
  (typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2)) + "-omnio-web";

function authHeader(creds: EmbyCreds): Record<string, string> {
  return {
    "x-emby-token": creds.apiKey,
    "x-emby-authorization": `MediaBrowser Client="OmnioTV-Web", Device="Browser", DeviceId="${DEVICE_ID}", Version="0.1.0", Token="${creds.apiKey}"`,
  };
}

function trimBase(url: string): string {
  return url.replace(/\/+$/, "");
}

export async function loadEmbyCreds(profileId: number): Promise<EmbyCreds | null> {
  const row = await pullProfileSettings(profileId);
  const decoded = decodeStringFeature(row?.settings_json ?? null, FEATURE_KEY);
  const serverUrl = decoded.emby_server_url;
  const apiKey = decoded.emby_api_key;
  const userId = decoded.emby_user_id;
  if (!serverUrl || !apiKey || !userId) return null;
  return { serverUrl: trimBase(serverUrl), apiKey, userId };
}

export async function saveEmbyCreds(profileId: number, creds: EmbyCreds): Promise<void> {
  const feature = encodeStringFeature({
    emby_server_url: trimBase(creds.serverUrl),
    emby_api_key: creds.apiKey,
    emby_user_id: creds.userId,
  });
  const result = await pushFeature(profileId, FEATURE_KEY, feature);
  if ("conflict" in result) {
    // Re-pull and retry once. Conflicts on this feature are rare since both
    // sides typically write only after the user explicitly hits Save.
    const fresh = await pullProfileSettings(profileId);
    await pushFeature(profileId, FEATURE_KEY, feature, fresh?.updated_at);
  }
}

export async function clearEmbyCreds(profileId: number): Promise<void> {
  await pushFeature(profileId, FEATURE_KEY, {});
}

interface EmbyAuthResponse {
  AccessToken: string;
  ServerId: string;
  User: { Id: string; Name: string };
}

export async function authenticateEmby(
  serverUrl: string,
  username: string,
  password: string
): Promise<EmbyCreds> {
  const base = trimBase(serverUrl);
  const response = await fetch(`${base}/Users/AuthenticateByName`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-emby-authorization": `MediaBrowser Client="OmnioTV-Web", Device="Browser", DeviceId="${DEVICE_ID}", Version="0.1.0"`,
    },
    body: JSON.stringify({ Username: username, Pw: password }),
  });
  if (!response.ok) {
    throw new Error(`Emby login failed (${response.status}): ${await response.text()}`);
  }
  const auth = (await response.json()) as EmbyAuthResponse;
  return {
    serverUrl: base,
    apiKey: auth.AccessToken,
    userId: auth.User.Id,
  };
}

interface EmbyItem {
  Id: string;
  Name: string;
  RunTimeTicks?: number;
}

// Emby's "find by IMDb id" endpoint. Cheap call, returns 0 or 1 item.
export async function findItemByImdb(
  creds: EmbyCreds,
  imdbId: string,
  season?: number | null,
  episode?: number | null
): Promise<EmbyItem | null> {
  const params = new URLSearchParams({
    IncludeItemTypes: season != null ? "Episode" : "Movie",
    Recursive: "true",
    AnyProviderIdEquals: `imdb.${imdbId}`,
    Fields: "ProviderIds,RunTimeTicks",
    Limit: "20",
  });
  if (season != null) {
    params.set("ParentIndexNumber", String(season));
  }
  if (episode != null) {
    params.set("IndexNumber", String(episode));
  }

  const response = await fetch(`${creds.serverUrl}/Users/${creds.userId}/Items?${params.toString()}`, {
    headers: authHeader(creds),
  });
  if (!response.ok) return null;
  const data = (await response.json()) as { Items?: EmbyItem[] };
  return data.Items?.[0] ?? null;
}

// 1 tick = 100 ns; Emby's PositionTicks expects 100-ns ticks.
function ticks(ms: number): number {
  return Math.floor(ms * 10_000);
}

interface ScrobbleArgs {
  itemId: string;
  positionMs: number;
  isPaused?: boolean;
}

export async function reportPlaying(creds: EmbyCreds, args: ScrobbleArgs): Promise<void> {
  await fetch(`${creds.serverUrl}/Sessions/Playing`, {
    method: "POST",
    headers: { ...authHeader(creds), "content-type": "application/json" },
    body: JSON.stringify({ ItemId: args.itemId, PositionTicks: ticks(args.positionMs) }),
  }).catch(() => {});
}

export async function reportProgress(creds: EmbyCreds, args: ScrobbleArgs): Promise<void> {
  await fetch(`${creds.serverUrl}/Sessions/Playing/Progress`, {
    method: "POST",
    headers: { ...authHeader(creds), "content-type": "application/json" },
    body: JSON.stringify({
      ItemId: args.itemId,
      PositionTicks: ticks(args.positionMs),
      IsPaused: !!args.isPaused,
    }),
  }).catch(() => {});
}

export async function reportStopped(creds: EmbyCreds, args: ScrobbleArgs): Promise<void> {
  await fetch(`${creds.serverUrl}/Sessions/Playing/Stopped`, {
    method: "POST",
    headers: { ...authHeader(creds), "content-type": "application/json" },
    body: JSON.stringify({ ItemId: args.itemId, PositionTicks: ticks(args.positionMs) }),
  }).catch(() => {});
}
