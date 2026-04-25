"use client";

import { useMemo, useState, useTransition } from "react";
import { ExternalLink, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveAddons } from "@/lib/actions/relational";
import { SaveBar, type SaveState } from "./SaveBar";
import { SortableList } from "./SortableList";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface AddonRow {
  id: string;
  url: string;
  name: string | null;
}

interface Props {
  profileId: number;
  initial: AddonRow[];
}

function newId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `tmp-${Math.random().toString(36).slice(2)}`;
}

function isProbablyUrl(s: string): boolean {
  if (s.length === 0) return false;
  try {
    const u = new URL(s);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

export default function AddonsForm({ profileId, initial }: Props) {
  const router = useRouter();
  const [items, setItems] = useState<AddonRow[]>(initial);
  const [draftUrl, setDraftUrl] = useState("");
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialUrls = useMemo(() => initial.map((a) => a.url).join("|"), [initial]);
  const currentUrls = useMemo(() => items.map((a) => a.url).join("|"), [items]);
  const dirty = initialUrls !== currentUrls;

  useUnsavedWarning(dirty);

  const draftValid = draftUrl.length === 0 || isProbablyUrl(draftUrl);
  const draftDuplicate =
    draftUrl.length > 0 && items.some((a) => a.url === draftUrl.trim());

  const handleAdd = () => {
    const url = draftUrl.trim();
    if (!isProbablyUrl(url) || draftDuplicate) return;
    setItems((prev) => [...prev, { id: newId(), url, name: null }]);
    setDraftUrl("");
  };

  const handleRemove = (id: string) => {
    setItems((prev) => prev.filter((a) => a.id !== id));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveAddons({
        profileId,
        addonUrls: items.map((a) => a.url),
        revalidatePath: `/p/${profileId}/addons`,
      });
      if (result.ok) {
        setState({ kind: "saved", at: Date.now() });
        // Pull fresh server state so any de-dupe / re-keying is reflected.
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
    setDraftUrl("");
    setState({ kind: "idle" });
  };

  return (
    <div className="space-y-6">
      <div className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40 p-4">
        {items.length === 0 ? (
          <div className="px-2 py-4 text-sm text-slate-400">
            No addons installed. Paste a manifest URL below to add one.
          </div>
        ) : (
          <SortableList
            items={items}
            onReorder={setItems}
            renderItem={(item, i, dragHandle) => (
              <div className="flex items-center gap-3 rounded-lg border border-slate-700/40 bg-slate-900/40 p-3">
                {dragHandle}
                <span className="w-6 shrink-0 text-xs text-slate-500">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-slate-100">
                    {item.name ?? hostnameOf(item.url)}
                  </div>
                  <a
                    href={item.url}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-0.5 inline-flex max-w-full items-center gap-1 truncate text-xs text-slate-400 hover:text-primary"
                  >
                    <span className="truncate">{item.url}</span>
                    <ExternalLink className="h-3 w-3 shrink-0" />
                  </a>
                </div>
                <button
                  type="button"
                  onClick={() => handleRemove(item.id)}
                  className="flex h-8 w-8 items-center justify-center rounded text-slate-500 hover:text-rose-300"
                  aria-label="Remove addon"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            )}
          />
        )}
      </div>

      <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-4">
        <label className="mb-2 block text-xs font-medium uppercase tracking-wide text-slate-400">
          Add addon by manifest URL
        </label>
        <div className="flex gap-2">
          <input
            type="url"
            inputMode="url"
            placeholder="https://example.com/manifest.json"
            value={draftUrl}
            onChange={(e) => setDraftUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                handleAdd();
              }
            }}
            className="flex-1 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary"
          />
          <button
            type="button"
            onClick={handleAdd}
            disabled={!draftValid || draftUrl.trim().length === 0 || draftDuplicate}
            className="rounded-lg border border-slate-600 bg-slate-700/40 px-3 py-2 text-sm text-slate-100 transition hover:border-primary disabled:cursor-not-allowed disabled:opacity-40"
          >
            Add
          </button>
        </div>
        {!draftValid && (
          <p className="mt-1 text-xs text-rose-300">URL must start with http:// or https://</p>
        )}
        {draftDuplicate && (
          <p className="mt-1 text-xs text-amber-300">This URL is already in the list.</p>
        )}
      </div>

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
