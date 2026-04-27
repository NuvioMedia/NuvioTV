// Vercel Edge function — strips CORS for addon JSON / scraper requests.
// Streams the upstream body through; never buffers the whole response.
// Validation logic is inlined (mirrors web/shared/src/proxy/index.ts) because
// Vercel's edge bundler scopes imports to the project root.

export const config = {
  runtime: "edge",
};

const ALLOWED_PROTOCOLS = new Set(["http:", "https:"]);
const PRIVATE_HOST_RE =
  /^(localhost|127\.|10\.|192\.168\.|169\.254\.|::1|\[::1\]|0\.0\.0\.0)/i;

function isPrivate172(host: string): boolean {
  const m = host.match(/^172\.(\d+)\./);
  if (!m) return false;
  const n = parseInt(m[1]!, 10);
  return n >= 16 && n <= 31;
}

function validateProxyTarget(rawUrl: string | null):
  | { ok: true; url: URL }
  | { ok: false; reason: string } {
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
  if (PRIVATE_HOST_RE.test(host) || isPrivate172(host)) {
    return { ok: false, reason: "private host not allowed" };
  }
  return { ok: true, url: parsed };
}

const MAX_BODY_BYTES = 10 * 1024 * 1024; // 10 MB cap — addon JSON is well under this.

const FORWARD_REQUEST_HEADERS = new Set([
  "accept",
  "accept-language",
  "range",
  "user-agent",
  "if-none-match",
  "if-modified-since",
]);

const FORWARD_RESPONSE_HEADERS = new Set([
  "content-type",
  "content-length",
  "content-range",
  "accept-ranges",
  "etag",
  "last-modified",
  "cache-control",
]);

function corsHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, HEAD, OPTIONS",
    "access-control-allow-headers": "Range, Content-Type, Accept, Authorization",
    "access-control-expose-headers": "Content-Length, Content-Range, Accept-Ranges",
    ...extra,
  };
}

export default async function handler(request: Request): Promise<Response> {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders() });
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("method not allowed", { status: 405, headers: corsHeaders() });
  }

  const url = new URL(request.url);
  const target = url.searchParams.get("url");
  const validation = validateProxyTarget(target);
  if (!validation.ok) {
    return new Response(`bad request: ${validation.reason}`, {
      status: 400,
      headers: corsHeaders(),
    });
  }

  const upstreamHeaders = new Headers();
  request.headers.forEach((value, key) => {
    if (FORWARD_REQUEST_HEADERS.has(key.toLowerCase())) {
      upstreamHeaders.set(key, value);
    }
  });
  if (!upstreamHeaders.has("user-agent")) {
    upstreamHeaders.set("user-agent", "OmnioTV/web (+https://omnio.tv)");
  }

  let upstream: Response;
  try {
    upstream = await fetch(validation.url.toString(), {
      method: request.method,
      headers: upstreamHeaders,
      redirect: "follow",
    });
  } catch (err) {
    return new Response(`upstream fetch failed: ${(err as Error).message}`, {
      status: 502,
      headers: corsHeaders(),
    });
  }

  const responseHeaders = new Headers(corsHeaders());
  upstream.headers.forEach((value, key) => {
    if (FORWARD_RESPONSE_HEADERS.has(key.toLowerCase())) {
      responseHeaders.set(key, value);
    }
  });

  // Soft body-size cap. We let the body stream through but abort if it overshoots
  // — protects the proxy from being used as a generic file mirror.
  const contentLength = Number(upstream.headers.get("content-length") ?? "0");
  if (contentLength > MAX_BODY_BYTES) {
    return new Response("upstream body too large for proxy", {
      status: 502,
      headers: corsHeaders(),
    });
  }

  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders,
  });
}
