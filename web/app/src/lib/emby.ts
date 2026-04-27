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
  // The UserId="..." segment is essential — without it, sessions started by an
  // API key have session.UserId == null and Emby's activity-log handler bails,
  // so "User played X" entries never get written even though Now Playing is
  // visible. Mirrors the Android fix in commit 17249f39.
  const auth =
    `MediaBrowser Client="OmnioTV-Web", Device="Browser"` +
    `, DeviceId="${DEVICE_ID}"` +
    `, Version="0.1.0"` +
    `, Token="${creds.apiKey}"` +
    (creds.userId ? `, UserId="${creds.userId}"` : "");
  return {
    "x-emby-token": creds.apiKey,
    "x-emby-authorization": auth,
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
  if (!serverUrl || !apiKey || !userId) {
    console.info(
      `[emby] no creds for profile ${profileId} (server=${!!serverUrl} key=${!!apiKey} user=${!!userId})`
    );
    return null;
  }
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
  IndexNumber?: number;
  ParentIndexNumber?: number;
  ProviderIds?: Record<string, string>;
}

// Emby tags items with stream-provider ids (`imdb`, `tmdb`, `tvdb`). The path
// for movies vs series is different: movies have the IMDb id directly on the
// item, but episodes don't carry the show's IMDb id — only the parent Series
// item does. So for series we do a two-step lookup.
export async function findItemByImdb(
  creds: EmbyCreds,
  imdbId: string,
  season?: number | null,
  episode?: number | null
): Promise<EmbyItem | null> {
  // Movies: direct IMDb match on Movie items.
  if (season == null || episode == null) {
    const item = await searchByImdb(creds, imdbId, "Movie");
    if (!item) {
      console.info(`[emby] no Movie item with imdb=${imdbId}`);
    }
    return item;
  }

  // Series episode: find the Series first, then look up the episode by season +
  // index. We never search "Episode" by IMDb id because episodes typically
  // don't carry the show's IMDb id in ProviderIds.
  const series = await searchByImdb(creds, imdbId, "Series");
  if (!series) {
    console.info(`[emby] no Series item with imdb=${imdbId}`);
    return null;
  }

  const episodesResp = await fetch(
    `${creds.serverUrl}/Shows/${series.Id}/Episodes?` +
      new URLSearchParams({
        UserId: creds.userId,
        Season: String(season),
        Fields: "ProviderIds",
      }).toString(),
    { headers: authHeader(creds) }
  ).catch((e) => {
    console.warn("[emby] /Shows/{id}/Episodes failed", e);
    return null;
  });

  if (!episodesResp || !episodesResp.ok) {
    console.warn(
      `[emby] /Shows/{id}/Episodes returned ${episodesResp?.status ?? "no response"}`
    );
    return null;
  }

  const data = (await episodesResp.json().catch(() => null)) as { Items?: EmbyItem[] } | null;
  const found = data?.Items?.find((it) => it.IndexNumber === episode);
  if (!found) {
    console.info(
      `[emby] series ${series.Name} (${series.Id}) found but no S${season}E${episode} on this server`
    );
  }
  return found ?? null;
}

async function searchByImdb(
  creds: EmbyCreds,
  imdbId: string,
  itemType: "Movie" | "Series"
): Promise<EmbyItem | null> {
  // Different Emby versions store the provider name with different casing
  // ("Imdb" vs "imdb") and the AnyProviderIdEquals query is sometimes
  // case-sensitive. Try the common variants in order.
  const variants = [`imdb.${imdbId}`, `Imdb.${imdbId}`, `IMDB.${imdbId}`];
  for (const variant of variants) {
    const item = await runItemsSearch(creds, {
      IncludeItemTypes: itemType,
      Recursive: "true",
      AnyProviderIdEquals: variant,
      Fields: "ProviderIds,RunTimeTicks",
      Limit: "5",
    });
    if (item) return item;
  }

  // Fall back to a wider scan — pull every item of this type and filter by
  // ProviderIds client-side. Slow on large libraries but reliable when
  // AnyProviderIdEquals isn't matching for whatever server-version reason.
  console.info(`[emby] AnyProviderIdEquals returned nothing for ${imdbId}; falling back to scan`);
  const candidates = await runItemsSearchMany(creds, {
    IncludeItemTypes: itemType,
    Recursive: "true",
    Fields: "ProviderIds",
    Limit: "10000",
  });
  const target = imdbId.toLowerCase();
  const match = candidates.find((it) => {
    const ids = it.ProviderIds ?? {};
    return Object.entries(ids).some(([key, value]) => {
      if (key.toLowerCase() !== "imdb") return false;
      return String(value).toLowerCase() === target;
    });
  });
  if (match) {
    console.info(`[emby] scan found ${itemType} ${match.Name} for ${imdbId}`);
  }
  return match ?? null;
}

async function runItemsSearch(
  creds: EmbyCreds,
  query: Record<string, string>
): Promise<EmbyItem | null> {
  const items = await runItemsSearchMany(creds, query);
  return items[0] ?? null;
}

async function runItemsSearchMany(
  creds: EmbyCreds,
  query: Record<string, string>
): Promise<EmbyItem[]> {
  const params = new URLSearchParams(query);
  const url = `${creds.serverUrl}/Users/${creds.userId}/Items?${params.toString()}`;
  const response = await fetch(url, { headers: authHeader(creds) }).catch((e) => {
    console.warn(`[emby] /Users/{id}/Items search failed`, e);
    return null;
  });
  if (!response || !response.ok) {
    console.warn(
      `[emby] /Users/{id}/Items returned ${response?.status ?? "no response"} for query=${params.toString()}`
    );
    return [];
  }
  const data = (await response.json().catch(() => null)) as { Items?: EmbyItem[] } | null;
  return data?.Items ?? [];
}

// PlaybackInfo response shape — only the bits we consume.
interface EmbyMediaStream {
  Type: "Video" | "Audio" | "Subtitle" | string;
  Codec?: string;
  Language?: string;
  DisplayTitle?: string;
  IsDefault?: boolean;
}

interface EmbyMediaSource {
  Id: string;
  Name: string;
  Container?: string;
  Size?: number;
  Path?: string;
  Bitrate?: number;
  TranscodingUrl?: string;
  TranscodingSubProtocol?: string;
  DirectStreamUrl?: string;
  SupportsDirectPlay?: boolean;
  SupportsDirectStream?: boolean;
  SupportsTranscoding?: boolean;
  MediaStreams?: EmbyMediaStream[];
}

interface PlaybackInfoResponse {
  MediaSources?: EmbyMediaSource[];
  PlaySessionId?: string;
}

// Permissive device profile — tells Emby we can play H.264/AAC directly via MSE,
// AV1 + Opus where the browser supports them. Anything else gets transcoded
// to HLS H.264/AAC via TranscodingUrl. Subtitles are requested as external VTT
// (browser <track>) or ASS (rendered by JASSUB on our side).
function deviceProfile() {
  return {
    MaxStreamingBitrate: 20_000_000,
    MaxStaticBitrate: 100_000_000,
    MusicStreamingTranscodingBitrate: 192_000,
    DirectPlayProfiles: [
      {
        Container: "mp4,m4v,webm",
        Type: "Video",
        VideoCodec: "h264,vp9,av1",
        AudioCodec: "aac,mp3,opus,flac",
      },
      {
        Container: "mp3,aac,m4a,flac,opus,ogg",
        Type: "Audio",
      },
    ],
    TranscodingProfiles: [
      {
        Container: "ts",
        Type: "Video",
        AudioCodec: "aac",
        VideoCodec: "h264",
        Protocol: "hls",
        MaxAudioChannels: "2",
        MinSegments: 2,
        BreakOnNonKeyFrames: true,
      },
    ],
    ContainerProfiles: [],
    CodecProfiles: [],
    ResponseProfiles: [],
    SubtitleProfiles: [
      { Format: "vtt", Method: "External" },
      { Format: "ass", Method: "External" },
      { Format: "ssa", Method: "External" },
      { Format: "srt", Method: "External" },
    ],
  };
}

export interface EmbyStreamCandidate {
  url: string;
  itemId: string;
  mediaSourceId: string;
  title: string;
  filename?: string;
  size?: number;
  videoCodec?: string;
  audioCodec?: string;
  audioLanguages: string[];
  subtitleLanguages: string[];
  isDirectPlay: boolean;
  // Emby will transcode server-side if direct play isn't possible — meaning
  // AC3/DTS/MKV all become browser-playable HLS at the edge of the LAN.
  isTranscoding: boolean;
}

// Diagnostic state surfaced to the picker so the user can see *why* Emby
// returned no streams. Without this, a CORS rejection or a missing series
// looks identical to "Emby disabled" — opaque and frustrating.
export type EmbyLookupState =
  | { kind: "no-creds" }
  | { kind: "network-error"; step: string; message: string }
  | { kind: "not-found"; what: "movie" | "series" | "episode"; imdbId: string }
  | { kind: "no-media-sources"; itemName: string }
  | { kind: "ok"; count: number };

export interface EmbyLookupResult {
  streams: EmbyStreamCandidate[];
  state: EmbyLookupState;
}

// Resolve the IMDb id to one or more Emby playback URLs. We hit
// /Items/{id}/PlaybackInfo so the server tells us which sources can direct-play
// vs need transcode, and gives us the TranscodingUrl with a fresh PlaySessionId.
export async function getEmbyStreams(
  creds: EmbyCreds,
  imdbId: string,
  season: number | null,
  episode: number | null
): Promise<EmbyLookupResult> {
  let item: EmbyItem | null;
  try {
    item = await findItemByImdb(creds, imdbId, season, episode);
  } catch (e) {
    const msg = (e as Error).message ?? String(e);
    console.warn("[emby] findItemByImdb threw", e);
    return {
      streams: [],
      state: { kind: "network-error", step: "find-item", message: msg },
    };
  }
  if (!item) {
    const what = season != null ? (episode != null ? "episode" : "series") : "movie";
    return { streams: [], state: { kind: "not-found", what, imdbId } };
  }

  let response: Response | null = null;
  try {
    response = await fetch(
      `${creds.serverUrl}/Items/${item.Id}/PlaybackInfo?UserId=${encodeURIComponent(creds.userId)}`,
      {
        method: "POST",
        headers: { ...authHeader(creds), "content-type": "application/json" },
        body: JSON.stringify({ DeviceProfile: deviceProfile() }),
      }
    );
  } catch (e) {
    const msg = (e as Error).message ?? String(e);
    console.warn("[emby] /PlaybackInfo POST failed", e);
    return {
      streams: [],
      state: { kind: "network-error", step: "playback-info", message: msg },
    };
  }
  if (!response.ok) {
    console.warn(`[emby] /PlaybackInfo returned ${response.status}`);
    return {
      streams: [],
      state: {
        kind: "network-error",
        step: "playback-info",
        message: `HTTP ${response.status}`,
      },
    };
  }

  const info = (await response.json().catch(() => null)) as PlaybackInfoResponse | null;
  if (!info?.MediaSources?.length) {
    console.info(`[emby] item ${item.Id} (${item.Name}) has no MediaSources`);
    return {
      streams: [],
      state: { kind: "no-media-sources", itemName: item.Name },
    };
  }

  const itemRef = item;
  const streams = info.MediaSources.map((source): EmbyStreamCandidate => {
    const videoStream = source.MediaStreams?.find((s) => s.Type === "Video");
    const audioStreams = source.MediaStreams?.filter((s) => s.Type === "Audio") ?? [];
    const subtitleStreams = source.MediaStreams?.filter((s) => s.Type === "Subtitle") ?? [];

    const isDirectPlay = !!source.SupportsDirectPlay && !!source.SupportsDirectStream;
    const isTranscoding = !isDirectPlay;

    // For direct play we use the static stream endpoint; for everything else
    // we use Emby's TranscodingUrl (server emits browser-friendly HLS).
    let url: string;
    if (isDirectPlay) {
      url = `${creds.serverUrl}/Videos/${source.Id}/stream?Static=true&MediaSourceId=${encodeURIComponent(source.Id)}&api_key=${encodeURIComponent(creds.apiKey)}`;
    } else if (source.TranscodingUrl) {
      url = source.TranscodingUrl.startsWith("http")
        ? source.TranscodingUrl
        : `${creds.serverUrl}${source.TranscodingUrl}`;
      // TranscodingUrl from Emby usually omits api_key; append it.
      const sep = url.includes("?") ? "&" : "?";
      if (!url.includes("api_key=")) {
        url = `${url}${sep}api_key=${encodeURIComponent(creds.apiKey)}`;
      }
    } else {
      // Last-ditch: master.m3u8 universal endpoint.
      url = `${creds.serverUrl}/Videos/${itemRef.Id}/master.m3u8?MediaSourceId=${encodeURIComponent(source.Id)}&VideoCodec=h264&AudioCodec=aac&MaxAudioChannels=2&api_key=${encodeURIComponent(creds.apiKey)}`;
    }

    return {
      url,
      itemId: itemRef.Id,
      mediaSourceId: source.Id,
      title: source.Name || itemRef.Name,
      filename: source.Path?.split("/").pop() ?? undefined,
      size: source.Size,
      videoCodec: videoStream?.Codec,
      audioCodec: audioStreams[0]?.Codec,
      audioLanguages: audioStreams.map((a) => a.Language ?? "und").filter((l) => l !== "und"),
      subtitleLanguages: subtitleStreams.map((s) => s.Language ?? "und").filter((l) => l !== "und"),
      isDirectPlay,
      isTranscoding,
    };
  });

  return { streams, state: { kind: "ok", count: streams.length } };
}

// 1 tick = 100 ns; Emby's PositionTicks expects 100-ns ticks.
function ticks(ms: number): number {
  return Math.floor(ms * 10_000);
}

const PROGRESS_INTERVAL_MS = 10_000;

// Stateful Emby playback session — mirrors Android's EmbySessionService.
// Emby's Start/Progress/Stop endpoints are stateful: PlaySessionId must be
// generated once on Start and reused throughout. Without that, Now Playing
// shows the live session but the activity-log handler can't reconcile a
// missing PlaySessionId and skips the "User played X" entry.
//
// We expose this as a class instance per Player mount. The browser's
// page-unload race is handled with `keepalive: true` on the Stop request,
// the web equivalent of the SupervisorJob singleton-scope fix in
// commit 77704c4d.
export class EmbySession {
  private creds: EmbyCreds;
  private currentItemId: string | null = null;
  private currentMediaSourceId: string | null = null;
  private currentPlaySessionId: string | null = null;
  private hasReportedStart = false;
  private lastProgressReportMs = 0;

  constructor(creds: EmbyCreds) {
    this.creds = creds;
  }

  async reportStart(itemId: string, mediaSourceId: string, positionMs = 0): Promise<void> {
    if (
      this.hasReportedStart &&
      this.currentItemId === itemId &&
      this.currentMediaSourceId === mediaSourceId
    ) {
      return;
    }

    const playSessionId = crypto.randomUUID();
    const payload = {
      ItemId: itemId,
      MediaSourceId: mediaSourceId,
      PlaySessionId: playSessionId,
      PositionTicks: ticks(positionMs),
      CanSeek: true,
      IsPaused: false,
      PlayMethod: "DirectStream" as const,
      QueueableMediaTypes: ["Video"],
    };

    try {
      const response = await fetch(`${this.creds.serverUrl}/Sessions/Playing`, {
        method: "POST",
        headers: { ...authHeader(this.creds), "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (response.ok) {
        this.currentItemId = itemId;
        this.currentMediaSourceId = mediaSourceId;
        this.currentPlaySessionId = playSessionId;
        this.hasReportedStart = true;
        this.lastProgressReportMs = Date.now();
        console.info(`[emby] reported playback start: ${itemId}`);
      } else {
        console.warn(`[emby] /Sessions/Playing returned ${response.status}`);
      }
    } catch (err) {
      console.warn("[emby] reportStart failed", err);
    }
  }

  async reportProgress(positionMs: number, isPaused = false, force = false): Promise<void> {
    if (!this.hasReportedStart) return;
    if (!this.currentItemId || !this.currentMediaSourceId || !this.currentPlaySessionId) return;

    const now = Date.now();
    if (!force && now - this.lastProgressReportMs < PROGRESS_INTERVAL_MS) return;

    const payload = {
      ItemId: this.currentItemId,
      MediaSourceId: this.currentMediaSourceId,
      PlaySessionId: this.currentPlaySessionId,
      PositionTicks: ticks(positionMs),
      CanSeek: true,
      IsPaused: isPaused,
      PlayMethod: "DirectStream" as const,
      EventName: "TimeUpdate" as const,
    };

    try {
      const response = await fetch(`${this.creds.serverUrl}/Sessions/Playing/Progress`, {
        method: "POST",
        headers: { ...authHeader(this.creds), "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (response.ok) {
        this.lastProgressReportMs = now;
      }
    } catch (err) {
      console.warn("[emby] reportProgress failed", err);
    }
  }

  reportStop(positionMs = 0): void {
    const itemId = this.currentItemId;
    const mediaSourceId = this.currentMediaSourceId;
    const playSessionId = this.currentPlaySessionId;
    const wasStarted = this.hasReportedStart;
    this.reset();

    if (!wasStarted || !itemId || !mediaSourceId || !playSessionId) return;

    // `keepalive: true` is the browser equivalent of Android's SupervisorJob:
    // it tells the browser to finish this fetch even if the page is
    // unloading, so navigating away from /player still triggers the stop
    // event and Emby writes its activity-log entry.
    const payload = {
      ItemId: itemId,
      MediaSourceId: mediaSourceId,
      PlaySessionId: playSessionId,
      PositionTicks: ticks(positionMs),
    };

    fetch(`${this.creds.serverUrl}/Sessions/Playing/Stopped`, {
      method: "POST",
      headers: { ...authHeader(this.creds), "content-type": "application/json" },
      body: JSON.stringify(payload),
      keepalive: true,
    })
      .then((r) => {
        if (r.ok) {
          console.info(`[emby] reported playback stopped at ${positionMs}ms`);
        } else {
          console.warn(`[emby] /Sessions/Playing/Stopped returned ${r.status}`);
        }
      })
      .catch((err) => console.warn("[emby] reportStop failed", err));
  }

  reset(): void {
    this.currentItemId = null;
    this.currentMediaSourceId = null;
    this.currentPlaySessionId = null;
    this.hasReportedStart = false;
    this.lastProgressReportMs = 0;
  }
}
