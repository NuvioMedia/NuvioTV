// Web Worker that loads + executes Stremio-style JS plugins. Mirrors the
// Android core/plugin contract: cheerio + crypto-js + a host-mediated fetch.
// All HTTP goes through the /api/proxy origin to dodge CORS without exposing
// the user's IP.

import { expose } from "comlink";
import * as cheerioMod from "cheerio";
import CryptoJS from "crypto-js";

interface HostBridge {
  proxyUrl: string;
}

interface ResolveArgs {
  tmdbId: string;
  mediaType: "movie" | "series";
  season?: number;
  episode?: number;
}

interface PluginStream {
  url: string;
  title?: string;
  description?: string;
  filename?: string;
  // Anything else the plugin returns; we forward it verbatim.
  [k: string]: unknown;
}

interface PluginAPI {
  load(source: string, hostBridge: HostBridge): Promise<{ ok: true } | { ok: false; error: string }>;
  resolve(args: ResolveArgs): Promise<PluginStream[]>;
  unload(): void;
}

let bridge: HostBridge | null = null;
let pluginGetStreams: ((args: ResolveArgs) => Promise<PluginStream[]>) | null = null;

const HOST_HARNESS_TIMEOUT_MS = 30_000;

// host.fetch is the only path plugins are allowed to use for HTTP. The plugin's
// own `fetch` global is shadowed at sandbox-build time so even creative scripts
// can't hit user-private endpoints.
async function hostFetch(url: string, init?: { method?: string; headers?: Record<string, string>; body?: string }): Promise<{
  ok: boolean;
  status: number;
  text: () => Promise<string>;
  json: () => Promise<unknown>;
  headers: Record<string, string>;
}> {
  if (!bridge) throw new Error("plugin host bridge not set");

  const proxied = `${bridge.proxyUrl}?url=${encodeURIComponent(url)}`;
  const response = await fetch(proxied, {
    method: init?.method ?? "GET",
    headers: init?.headers,
    body: init?.body,
    signal: AbortSignal.timeout(HOST_HARNESS_TIMEOUT_MS),
  });

  // Materialize the body once — plugins may call .text() and .json() either way
  // and we don't want to keep the underlying stream alive across yields.
  const text = await response.text();
  const headers: Record<string, string> = {};
  response.headers.forEach((v, k) => {
    headers[k] = v;
  });

  return {
    ok: response.ok,
    status: response.status,
    text: async () => text,
    json: async () => JSON.parse(text),
    headers,
  };
}

function makeSandbox() {
  // The harness is what we expose to plugin code. Anything not listed here is
  // unreachable from inside the plugin (modulo browser globals that always exist).
  const harness = {
    fetch: hostFetch,
    cheerio: cheerioMod,
    CryptoJS,
    btoa: globalThis.btoa.bind(globalThis),
    atob: globalThis.atob.bind(globalThis),
    encodeURIComponent: encodeURIComponent,
    decodeURIComponent: decodeURIComponent,
    URLSearchParams,
    URL,
    JSON,
    console: { log: console.log.bind(console), warn: console.warn.bind(console) },
  };
  return harness;
}

const api: PluginAPI = {
  async load(source, hostBridge) {
    bridge = hostBridge;
    pluginGetStreams = null;

    try {
      // Build a function whose only externally-visible globals are the harness
      // members. We intentionally don't pass the global `fetch` or `XMLHttpRequest`.
      const harness = makeSandbox();
      const harnessKeys = Object.keys(harness);
      const harnessVals = harnessKeys.map((k) => (harness as Record<string, unknown>)[k]);

      // The plugin contract: assign `module.exports.getStreams = async (args) => [...]`
      // Same shape as the Android QuickJS runtime.
      const moduleObj = { exports: {} as Record<string, unknown> };
      const factory = new Function(
        ...harnessKeys,
        "module",
        "exports",
        `"use strict";\n${source}\nreturn module.exports;`
      );

      const exports = factory.call(undefined, ...harnessVals, moduleObj, moduleObj.exports);
      const getStreams = (exports?.getStreams ?? moduleObj.exports?.getStreams) as
        | ((args: ResolveArgs) => Promise<PluginStream[]>)
        | undefined;

      if (typeof getStreams !== "function") {
        return { ok: false, error: "plugin must export getStreams(args)" };
      }

      pluginGetStreams = getStreams;
      return { ok: true };
    } catch (e) {
      return { ok: false, error: (e as Error).message };
    }
  },

  async resolve(args) {
    if (!pluginGetStreams) throw new Error("plugin not loaded");
    const result = await Promise.race([
      pluginGetStreams(args),
      new Promise<PluginStream[]>((_, reject) =>
        setTimeout(() => reject(new Error("plugin timeout")), HOST_HARNESS_TIMEOUT_MS)
      ),
    ]);
    return Array.isArray(result) ? result : [];
  },

  unload() {
    pluginGetStreams = null;
    bridge = null;
  },
};

expose(api);

export type { PluginAPI, PluginStream, ResolveArgs, HostBridge };
