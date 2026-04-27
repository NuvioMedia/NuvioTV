// Vercel Edge function — probes a stream URL with a HEAD request and returns a
// codec/container hint. Used by the stream picker before deciding native MSE
// vs the (Phase 3) transcode escape hatch. Validation inlined — see proxy.ts.

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

interface ProbeResult {
  ok: boolean;
  status: number;
  contentType: string | null;
  contentLength: number | null;
  acceptRanges: boolean;
  isHls: boolean;
  isDash: boolean;
  isMkv: boolean;
  isMp4: boolean;
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, OPTIONS",
    "access-control-allow-headers": "Content-Type",
    "cache-control": "public, max-age=60",
  };
}

export default async function handler(request: Request): Promise<Response> {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders() });
  }

  const url = new URL(request.url);
  const target = url.searchParams.get("url");
  const validation = validateProxyTarget(target);
  if (!validation.ok) {
    return new Response(JSON.stringify({ error: validation.reason }), {
      status: 400,
      headers: { ...corsHeaders(), "content-type": "application/json" },
    });
  }

  let upstream: Response;
  try {
    upstream = await fetch(validation.url.toString(), {
      method: "HEAD",
      redirect: "follow",
      headers: { "user-agent": "OmnioTV/web (+https://omnio.tv)" },
    });
  } catch {
    // Some hosts reject HEAD; fall back to a tiny ranged GET.
    try {
      upstream = await fetch(validation.url.toString(), {
        method: "GET",
        redirect: "follow",
        headers: { range: "bytes=0-0", "user-agent": "OmnioTV/web (+https://omnio.tv)" },
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: (err as Error).message }), {
        status: 502,
        headers: { ...corsHeaders(), "content-type": "application/json" },
      });
    }
  }

  const ct = upstream.headers.get("content-type");
  const cl = upstream.headers.get("content-length");
  const ar = upstream.headers.get("accept-ranges");

  const lowerUrl = validation.url.pathname.toLowerCase();
  const result: ProbeResult = {
    ok: upstream.ok,
    status: upstream.status,
    contentType: ct,
    contentLength: cl ? Number(cl) : null,
    acceptRanges: ar?.toLowerCase() === "bytes",
    isHls: /application\/(x-mpegurl|vnd\.apple\.mpegurl)/i.test(ct ?? "") || lowerUrl.endsWith(".m3u8"),
    isDash: /application\/dash\+xml/i.test(ct ?? "") || lowerUrl.endsWith(".mpd"),
    isMkv: /video\/x-matroska/i.test(ct ?? "") || lowerUrl.endsWith(".mkv"),
    isMp4: /video\/mp4/i.test(ct ?? "") || lowerUrl.endsWith(".mp4"),
  };

  return new Response(JSON.stringify(result), {
    status: 200,
    headers: { ...corsHeaders(), "content-type": "application/json" },
  });
}
