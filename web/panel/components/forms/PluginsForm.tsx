"use client";

import { useMemo, useState, useTransition } from "react";
import { ExternalLink, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { savePlugins } from "@/lib/actions/relational";
import { SaveBar, type SaveState } from "./SaveBar";
import { SortableList } from "./SortableList";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface PluginRow {
  id: string;
  url: string;
  name: string | null;
  enabled: boolean;
}

interface Props {
  profileId: number;
  initial: PluginRow[];
}

function signature(rows: PluginRow[]): string {
  return rows
    .map((p) => `${p.url}|${p.name ?? ""}|${p.enabled ? "1" : "0"}`)
    .join("//");
}

export default function PluginsForm({ profileId, initial }: Props) {
  const router = useRouter();
  const [items, setItems] = useState<PluginRow[]>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => signature(initial), [initial]);
  const currentSig = useMemo(() => signature(items), [items]);
  const dirty = initialSig !== currentSig;

  useUnsavedWarning(dirty);

  const updateItem = (id: string, patch: Partial<PluginRow>) => {
    setItems((prev) => prev.map((p) => (p.id === id ? { ...p, ...patch } : p)));
  };

  const handleRemove = (id: string) => {
    setItems((prev) => prev.filter((p) => p.id !== id));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await savePlugins({
        profileId,
        plugins: items.map((p) => ({
          url: p.url,
          name: p.name?.trim() || null,
          enabled: p.enabled,
        })),
        revalidatePath: `/p/${profileId}/plugins`,
      });
      if (result.ok) {
        setState({ kind: "saved", at: Date.now() });
        router.refresh();
        setTimeout(() => {
          setState((s) => (s.kind === "saved" ? { kind: "idle" } : s));
        }, 2000);
      } else if ("conflict" in result && result.conflict) {
        setState({ kind: "conflict" });
      } else {
        setState({ kind: "error", message: result.error });
      }
    });
  };

  const handleDiscard = () => {
    setItems(initial);
    setState({ kind: "idle" });
  };

  return (
    <div className="space-y-6">
      <div className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40 p-4">
        {items.length === 0 ? (
          <div className="px-2 py-4 text-sm text-slate-400">
            No plugins installed. Plugins are added from a TV (their JS code lives on
            the device).
          </div>
        ) : (
          <SortableList
            items={items}
            onReorder={setItems}
            renderItem={(item, i, dragHandle) => (
              <div className="flex items-start gap-3 rounded-lg border border-slate-700/40 bg-slate-900/40 p-3">
                {dragHandle}
                <span className="mt-2 w-6 shrink-0 text-xs text-slate-500">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1 space-y-2">
                  <input
                    type="text"
                    value={item.name ?? ""}
                    placeholder={hostnameOf(item.url)}
                    onChange={(e) => updateItem(item.id, { name: e.target.value })}
                    className="w-full rounded border border-slate-700 bg-slate-950/40 px-2 py-1 text-sm text-slate-100 outline-none focus:border-primary"
                  />
                  <a
                    href={item.url}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex max-w-full items-center gap-1 truncate text-xs text-slate-400 hover:text-primary"
                  >
                    <span className="truncate">{item.url}</span>
                    <ExternalLink className="h-3 w-3 shrink-0" />
                  </a>
                </div>
                <label className="mt-2 flex shrink-0 cursor-pointer items-center gap-2 text-xs text-slate-300">
                  <input
                    type="checkbox"
                    checked={item.enabled}
                    onChange={(e) => updateItem(item.id, { enabled: e.target.checked })}
                    className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                  />
                  enabled
                </label>
                <button
                  type="button"
                  onClick={() => handleRemove(item.id)}
                  className="mt-1 flex h-8 w-8 items-center justify-center rounded text-slate-500 hover:text-rose-300"
                  aria-label="Remove plugin"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            )}
          />
        )}
      </div>

      <p className="text-xs text-slate-500">
        The plugin JS code is not synced — it stays on each TV. Removing a plugin
        here only removes the metadata row from the cloud; you must also uninstall
        the plugin on the TV to fully remove it.
      </p>

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={handleDiscard}
      />
    </div>
  );
}

function hostnameOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}
