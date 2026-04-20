import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.8";
import { corsHeaders } from "../_shared/cors.ts";
import { loadConfigByToken } from "./lib/config.ts";
import type { AioConfig } from "./lib/config.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const jsonHeaders: HeadersInit = {
  ...corsHeaders,
  "Content-Type": "application/json",
};

function jsonResponse(status: number, body: unknown, cache?: string) {
  const headers: Record<string, string> = { ...jsonHeaders };
  if (cache) headers["Cache-Control"] = cache;
  return new Response(JSON.stringify(body), { status, headers });
}

function adminClient() {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
}

// Redact the :token segment from a pathname for logging.
function redactTokenFromPath(pathname: string): string {
  return pathname.replace(
    /\/aiometa\/[^/]+/,
    "/aiometa/[redacted]",
  );
}

type Route =
  | { kind: "manifest"; token: string }
  | { kind: "meta"; token: string; type: string; id: string }
  | { kind: "catalog"; token: string; type: string; id: string; extra?: string }
  | { kind: "not_found" };

function matchRoute(pathname: string): Route {
  // Edge Functions route under /functions/v1/aiometa/... but the runtime still
  // surfaces the full pathname; we anchor on /aiometa/ to be forgiving.
  const parts = pathname.split("/").filter(Boolean);
  const i = parts.indexOf("aiometa");
  if (i < 0 || !parts[i + 1]) return { kind: "not_found" };

  const token = parts[i + 1];
  const rest = parts.slice(i + 2);

  if (rest.length === 1 && rest[0] === "manifest.json") {
    return { kind: "manifest", token };
  }
  if (rest.length === 3 && rest[0] === "meta") {
    const type = rest[1];
    const id = rest[2].replace(/\.json$/, "");
    return { kind: "meta", token, type, id };
  }
  if ((rest.length === 3 || rest.length === 4) && rest[0] === "catalog") {
    const type = rest[1];
    const id = rest[2].replace(/\.json$/, "");
    const extra = rest[3]?.replace(/\.json$/, "");
    return { kind: "catalog", token, type, id, extra };
  }
  return { kind: "not_found" };
}

async function buildManifest(config: AioConfig): Promise<unknown> {
  // TODO(plan §1): replace with vendored upstream manifest builder.
  return {
    id: "community.aiometa.nuviotv",
    version: "0.1.0",
    name: "AIOMetadata (NuvioTV)",
    description: "Aggregated metadata from multiple providers. Per-user config.",
    resources: ["meta"],
    types: ["movie", "series"],
    idPrefixes: ["tt", "tmdb:"],
    catalogs: [],
    behaviorHints: { configurable: false, configurationRequired: false },
    _user: config.user_id,
  };
}

async function buildMeta(
  config: AioConfig,
  type: string,
  id: string,
): Promise<unknown> {
  // TODO(plan §3): call vendored provider fetchers using config.provider_keys /
  // config.providers and merge the response.
  return {
    meta: {
      id,
      type,
      name: "AIOMetadata placeholder",
      description: "Edge Function skeleton. Wire up provider fetchers.",
    },
    _providers: Object.keys(config.providers ?? {}),
  };
}

async function buildCatalog(
  _config: AioConfig,
  type: string,
  id: string,
  _extra: string | undefined,
): Promise<unknown> {
  // TODO(plan §3): implement catalog rendering per upstream.
  return { metas: [], _type: type, _id: id };
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders });
  }
  if (request.method !== "GET") {
    return jsonResponse(405, { error: "Method not allowed" });
  }
  if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY) {
    return jsonResponse(500, { error: "Edge Function env is incomplete" });
  }

  const url = new URL(request.url);
  const route = matchRoute(url.pathname);
  if (route.kind === "not_found") {
    return jsonResponse(404, { error: "Not found" });
  }

  const admin = adminClient();
  const config = await loadConfigByToken(admin, route.token);
  if (!config) {
    console.warn("aiometa: unknown token", redactTokenFromPath(url.pathname));
    return jsonResponse(404, { error: "Unknown token" });
  }
  if (!config.enabled) {
    return jsonResponse(403, { error: "Disabled" });
  }

  try {
    switch (route.kind) {
      case "manifest":
        return jsonResponse(200, await buildManifest(config), "public, s-maxage=300");
      case "meta":
        return jsonResponse(
          200,
          await buildMeta(config, route.type, route.id),
          "public, s-maxage=1800",
        );
      case "catalog":
        return jsonResponse(
          200,
          await buildCatalog(config, route.type, route.id, route.extra),
          "public, s-maxage=900",
        );
    }
  } catch (err) {
    console.error("aiometa: handler error", err);
    return jsonResponse(500, { error: "Internal error" });
  }
});
