// Helpers for the Vercel Edge /proxy and /probe endpoints. Shared between the
// edge function source and the client that calls it.

const ALLOWED_PROTOCOLS = new Set(["http:", "https:"]);

// Block requests to private/loopback ranges so the proxy can't be used as an
// internal-network scanner. Also block file:/javascript:/data: schemes.
const PRIVATE_HOST_RE =
  /^(localhost|127\.|10\.|192\.168\.|169\.254\.|::1|\[::1\]|0\.0\.0\.0)/i;

const PRIVATE_CIDR_172 = (host: string) => {
  const m = host.match(/^172\.(\d+)\./);
  if (!m) return false;
  const n = parseInt(m[1]!, 10);
  return n >= 16 && n <= 31;
};

export interface ProxyValidation {
  ok: boolean;
  url?: URL;
  reason?: string;
}

export function validateProxyTarget(rawUrl: string | null): ProxyValidation {
  if (!rawUrl) return { ok: false, reason: "missing url param" };
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { ok: false, reason: "invalid url" };
  }
  if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) {
    return { ok: false, reason: "protocol not allowed" };
  }
  const host = parsed.hostname.toLowerCase();
  if (PRIVATE_HOST_RE.test(host) || PRIVATE_CIDR_172(host)) {
    return { ok: false, reason: "private host not allowed" };
  }
  return { ok: true, url: parsed };
}

// Browser side: build the proxy URL we'll fetch.
export function proxyUrl(base: string, target: string): string {
  return `${base}?url=${encodeURIComponent(target)}`;
}
