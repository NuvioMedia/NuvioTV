import { wrap, type Remote } from "comlink";
import type { PluginAPI, PluginStream, ResolveArgs } from "./runtime.worker";
import { PROXY_URL } from "@/lib/proxy";

// One Worker per plugin URL. Unloaded eagerly when the user disables a plugin
// so we don't leak workers across navigations.
const workerCache = new Map<
  string,
  { worker: Worker; api: Remote<PluginAPI>; loaded: Promise<{ ok: true } | { ok: false; error: string }> }
>();

async function spawn(pluginUrl: string): Promise<{ api: Remote<PluginAPI>; loaded: { ok: true } | { ok: false; error: string } }> {
  const cached = workerCache.get(pluginUrl);
  if (cached) {
    return { api: cached.api, loaded: await cached.loaded };
  }

  const worker = new Worker(new URL("./runtime.worker.ts", import.meta.url), { type: "module" });
  const api = wrap<PluginAPI>(worker);

  // Plugin source is fetched through the proxy — the script may live on a
  // host without CORS headers, and we want a single network code path.
  const sourceUrl = `${PROXY_URL}?url=${encodeURIComponent(pluginUrl)}`;
  const sourceResp = await fetch(sourceUrl);
  if (!sourceResp.ok) {
    worker.terminate();
    return { api, loaded: { ok: false, error: `plugin fetch failed (${sourceResp.status})` } };
  }
  const source = await sourceResp.text();

  const loadedPromise = api.load(source, { proxyUrl: PROXY_URL });
  workerCache.set(pluginUrl, { worker, api, loaded: loadedPromise });
  const loaded = await loadedPromise;
  return { api, loaded };
}

export async function callPlugin(
  pluginUrl: string,
  args: ResolveArgs
): Promise<{ ok: true; streams: PluginStream[] } | { ok: false; error: string }> {
  try {
    const { api, loaded } = await spawn(pluginUrl);
    if (!loaded.ok) return { ok: false, error: loaded.error };
    const streams = await api.resolve(args);
    return { ok: true, streams };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}

export function unloadPlugin(pluginUrl: string): void {
  const cached = workerCache.get(pluginUrl);
  if (cached) {
    void cached.api.unload().catch(() => {});
    cached.worker.terminate();
    workerCache.delete(pluginUrl);
  }
}

export function unloadAllPlugins(): void {
  for (const url of [...workerCache.keys()]) unloadPlugin(url);
}
