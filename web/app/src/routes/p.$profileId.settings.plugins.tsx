import { useState } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Power, Trash2, Loader2 } from "lucide-react";
import type { PluginRow } from "@/lib/usePlugins";
import { supabase } from "@/lib/supabase";
import { unloadPlugin } from "@/plugin/manager";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/settings/plugins")({
  component: PluginsSettingsPage,
});

function PluginsSettingsPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const queryClient = useQueryClient();

  const { data: plugins = [], isLoading } = useQuery({
    queryKey: ["plugins", profileId, "all"],
    queryFn: async (): Promise<PluginRow[]> => {
      const { data, error } = await supabase
        .from("plugins")
        .select("*")
        .eq("profile_id", profileId)
        .order("sort_order", { ascending: true });
      if (error) throw error;
      return (data ?? []) as PluginRow[];
    },
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["plugins", profileId] });

  const addPlugin = useMutation({
    mutationFn: async ({ url, name }: { url: string; name: string | null }) => {
      const { data: userResp } = await supabase.auth.getUser();
      const userId = userResp.user?.id;
      if (!userId) throw new Error("not signed in");
      const sortOrder = (plugins[plugins.length - 1]?.sort_order ?? 0) + 1;
      const { error } = await supabase.from("plugins").insert({
        user_id: userId,
        profile_id: profileId,
        url,
        name,
        enabled: true,
        sort_order: sortOrder,
      });
      if (error) throw error;
    },
    onSuccess: () => invalidate(),
  });

  const togglePlugin = useMutation({
    mutationFn: async (plugin: PluginRow) => {
      const { error } = await supabase
        .from("plugins")
        .update({ enabled: !plugin.enabled })
        .eq("id", plugin.id);
      if (error) throw error;
      if (plugin.enabled) unloadPlugin(plugin.url);
    },
    onSuccess: () => invalidate(),
  });

  const deletePlugin = useMutation({
    mutationFn: async (plugin: PluginRow) => {
      const { error } = await supabase.from("plugins").delete().eq("id", plugin.id);
      if (error) throw error;
      unloadPlugin(plugin.url);
    },
    onSuccess: () => invalidate(),
  });

  const [newUrl, setNewUrl] = useState("");
  const [newName, setNewName] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function handleAdd(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    try {
      new URL(newUrl);
    } catch {
      setError("Plugin URL must be a full https://… address");
      return;
    }
    try {
      await addPlugin.mutateAsync({ url: newUrl.trim(), name: newName.trim() || null });
      setNewUrl("");
      setNewName("");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">Plugins</h2>
      <p className="text-sm text-slate-400">
        Plugins are JS scripts that resolve streams. Anything you add here syncs to your TV
        profile and runs in a sandboxed Web Worker in the browser.
      </p>

      <form onSubmit={handleAdd} className="space-y-2 rounded-lg border border-slate-800 bg-slate-900/40 p-3">
        <div className="grid gap-2 sm:grid-cols-[1fr_2fr_auto]">
          <input
            type="text"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="Name (optional)"
            className="rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
          />
          <input
            type="url"
            required
            value={newUrl}
            onChange={(e) => setNewUrl(e.target.value)}
            placeholder="https://example.com/plugin.js"
            className="rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
          />
          <button
            type="submit"
            disabled={addPlugin.isPending}
            className="flex items-center gap-1 rounded-md bg-primary px-3 py-2 text-sm font-medium text-white hover:bg-primary-hover disabled:opacity-50"
          >
            {addPlugin.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Plus className="h-4 w-4" />
            )}
            Add
          </button>
        </div>
        {error && <div className="text-xs text-red-300">{error}</div>}
      </form>

      {isLoading ? (
        <div className="text-slate-500">Loading…</div>
      ) : plugins.length === 0 ? (
        <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-4 text-sm text-slate-500">
          No plugins yet.
        </div>
      ) : (
        <ul className="divide-y divide-slate-800/60 rounded-lg border border-slate-800 bg-slate-900/40">
          {plugins.map((p) => (
            <li key={p.id} className="flex items-center gap-3 p-3">
              <div
                className={`h-2 w-2 rounded-full ${p.enabled ? "bg-emerald-400" : "bg-slate-600"}`}
                aria-label={p.enabled ? "enabled" : "disabled"}
              />
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm text-slate-200">{p.name || p.url}</div>
                <div className="truncate text-xs text-slate-500">{p.url}</div>
              </div>
              <button
                type="button"
                onClick={() => togglePlugin.mutate(p)}
                title={p.enabled ? "Disable" : "Enable"}
                className="rounded-md border border-slate-700 p-1.5 text-slate-300 hover:border-primary"
              >
                <Power className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={() => {
                  if (confirm(`Remove plugin "${p.name || p.url}"?`)) deletePlugin.mutate(p);
                }}
                title="Remove"
                className="rounded-md border border-slate-700 p-1.5 text-slate-300 hover:border-red-500/60 hover:text-red-300"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
