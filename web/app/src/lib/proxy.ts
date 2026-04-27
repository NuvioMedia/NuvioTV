export const PROXY_URL = import.meta.env.VITE_PROXY_URL ?? "/api/proxy";
export const PROBE_URL = import.meta.env.VITE_PROBE_URL ?? "/api/probe";

export interface ProbeResult {
  ok: boolean;
  status: number;
  contentType: string | null;
  contentLength: number | null;
  acceptRanges: boolean;
  // Heuristic flags inferred from headers + URL.
  isHls: boolean;
  isDash: boolean;
  isMkv: boolean;
  isMp4: boolean;
}

export async function probeStream(target: string, signal?: AbortSignal): Promise<ProbeResult> {
  const url = `${PROBE_URL}?url=${encodeURIComponent(target)}`;
  const response = await fetch(url, { signal });
  if (!response.ok) {
    throw new Error(`Probe failed (${response.status})`);
  }
  return (await response.json()) as ProbeResult;
}
