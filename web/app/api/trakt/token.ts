// Vercel Edge function — Trakt OAuth token exchange / refresh proxy.
//
// We hide TRAKT_CLIENT_SECRET on the server. The SPA POSTs either
//   { grant_type: "authorization_code", code, redirect_uri }
//   { grant_type: "refresh_token", refresh_token }
// We forward to https://api.trakt.tv/oauth/token with the secret added.

export const config = {
  runtime: "edge",
};

interface ExchangeBody {
  grant_type: "authorization_code" | "refresh_token";
  code?: string;
  redirect_uri?: string;
  refresh_token?: string;
}

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return new Response("method not allowed", { status: 405 });
  }

  const clientId = process.env.TRAKT_CLIENT_ID;
  const clientSecret = process.env.TRAKT_CLIENT_SECRET;
  if (!clientId || !clientSecret) {
    return json(500, { error: "trakt env not configured on server" });
  }

  let body: ExchangeBody;
  try {
    body = (await request.json()) as ExchangeBody;
  } catch {
    return json(400, { error: "invalid json" });
  }

  const upstreamBody: Record<string, string> = {
    client_id: clientId,
    client_secret: clientSecret,
    grant_type: body.grant_type,
  };

  if (body.grant_type === "authorization_code") {
    if (!body.code || !body.redirect_uri) {
      return json(400, { error: "missing code or redirect_uri" });
    }
    upstreamBody.code = body.code;
    upstreamBody.redirect_uri = body.redirect_uri;
  } else if (body.grant_type === "refresh_token") {
    if (!body.refresh_token) {
      return json(400, { error: "missing refresh_token" });
    }
    upstreamBody.refresh_token = body.refresh_token;
    upstreamBody.redirect_uri = `${new URL(request.url).origin}/auth/trakt/callback`;
  } else {
    return json(400, { error: "unsupported grant_type" });
  }

  const upstream = await fetch("https://api.trakt.tv/oauth/token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(upstreamBody),
  });

  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: {
      "content-type": upstream.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
    },
  });
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}
