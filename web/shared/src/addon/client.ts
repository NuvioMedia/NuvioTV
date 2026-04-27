import type {
  AddonManifest,
  CatalogResponse,
  MetaResponse,
  StreamResponse,
} from "./types";

// Wrap an addon URL with the proxy. Addon URLs end in /manifest.json or with no
// trailing path; we strip /manifest.json if present and treat the rest as base.
export function normalizeAddonBase(url: string): string {
  const trimmed = url.replace(/\/+$/, "");
  return trimmed.endsWith("/manifest.json") ? trimmed.slice(0, -"/manifest.json".length) : trimmed;
}

export interface AddonClientOptions {
  // Pass-through proxy that adds CORS headers. Must accept ?url=<encoded URL>.
  proxyUrl?: string;
  fetchImpl?: typeof fetch;
  signal?: AbortSignal;
}

function buildUrl(target: string, opts: AddonClientOptions): string {
  if (!opts.proxyUrl) return target;
  return `${opts.proxyUrl}?url=${encodeURIComponent(target)}`;
}

async function fetchJson<T>(target: string, opts: AddonClientOptions): Promise<T> {
  const fetchFn = opts.fetchImpl ?? fetch;
  const response = await fetchFn(buildUrl(target, opts), { signal: opts.signal });
  if (!response.ok) {
    throw new Error(`Addon request failed (${response.status}): ${target}`);
  }
  return (await response.json()) as T;
}

export async function fetchManifest(
  baseUrl: string,
  opts: AddonClientOptions = {}
): Promise<AddonManifest> {
  const base = normalizeAddonBase(baseUrl);
  return fetchJson<AddonManifest>(`${base}/manifest.json`, opts);
}

export async function fetchCatalog(
  baseUrl: string,
  type: string,
  catalogId: string,
  extras: Record<string, string> = {},
  opts: AddonClientOptions = {}
): Promise<CatalogResponse> {
  const base = normalizeAddonBase(baseUrl);
  const extraStr = Object.entries(extras)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join("&");
  const target =
    extraStr.length > 0
      ? `${base}/catalog/${type}/${catalogId}/${extraStr}.json`
      : `${base}/catalog/${type}/${catalogId}.json`;
  return fetchJson<CatalogResponse>(target, opts);
}

export async function fetchMeta(
  baseUrl: string,
  type: string,
  id: string,
  opts: AddonClientOptions = {}
): Promise<MetaResponse> {
  const base = normalizeAddonBase(baseUrl);
  return fetchJson<MetaResponse>(`${base}/meta/${type}/${id}.json`, opts);
}

export async function fetchStreams(
  baseUrl: string,
  type: string,
  id: string,
  opts: AddonClientOptions = {}
): Promise<StreamResponse> {
  const base = normalizeAddonBase(baseUrl);
  return fetchJson<StreamResponse>(`${base}/stream/${type}/${id}.json`, opts);
}
