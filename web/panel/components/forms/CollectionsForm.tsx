"use client";

import { useMemo, useState, useTransition } from "react";
import { Pin, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveCollections } from "@/lib/actions/relational";
import type {
  Collection,
  CollectionFolder,
} from "@/lib/data/collections";
import { SaveBar, type SaveState } from "./SaveBar";
import { SortableList } from "./SortableList";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: Collection[];
}

const TILE_SHAPES = ["POSTER", "LANDSCAPE", "SQUARE"] as const;

export default function CollectionsForm({ profileId, initial }: Props) {
  const router = useRouter();
  const [items, setItems] = useState<Collection[]>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(items), [items]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  const updateCollection = (id: string, patch: Partial<Collection>) => {
    setItems((prev) => prev.map((c) => (c.id === id ? { ...c, ...patch } : c)));
  };

  const removeCollection = (id: string) => {
    setItems((prev) => prev.filter((c) => c.id !== id));
  };

  const updateFolder = (
    collectionId: string,
    folderId: string,
    patch: Partial<CollectionFolder>
  ) => {
    setItems((prev) =>
      prev.map((c) =>
        c.id !== collectionId
          ? c
          : {
              ...c,
              folders: (c.folders ?? []).map((f) =>
                f.id === folderId ? { ...f, ...patch } : f
              ),
            }
      )
    );
  };

  const removeFolder = (collectionId: string, folderId: string) => {
    setItems((prev) =>
      prev.map((c) =>
        c.id !== collectionId
          ? c
          : { ...c, folders: (c.folders ?? []).filter((f) => f.id !== folderId) }
      )
    );
  };

  const reorderFolders = (collectionId: string, next: CollectionFolder[]) => {
    setItems((prev) =>
      prev.map((c) => (c.id !== collectionId ? c : { ...c, folders: next }))
    );
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveCollections({
        profileId,
        collectionsJson: items,
        revalidatePath: `/p/${profileId}/collections`,
      });
      if (result.ok) {
        setState({ kind: "saved", at: Date.now() });
        router.refresh();
        setTimeout(() => setState((s) => (s.kind === "saved" ? { kind: "idle" } : s)), 2000);
      } else if ("conflict" in result && result.conflict) {
        setState({ kind: "conflict" });
        router.refresh();
      } else {
        setState({ kind: "error", message: result.error });
      }
    });
  };

  return (
    <div className="space-y-6">
      {items.length === 0 ? (
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-6 text-sm text-slate-400">
          No collections yet. Create them on a TV — adding new collections from
          the panel requires the catalog picker, planned for v3.
        </div>
      ) : (
        <SortableList
          items={items}
          onReorder={(next) => setItems(next)}
          renderItem={(c, _i, dragHandle) => {
            const folders = c.folders ?? [];
            return (
              <div className="overflow-hidden rounded-2xl border border-slate-700/50 bg-slate-800/40">
                <div className="flex items-start gap-3 border-b border-slate-700/40 p-4">
                  {dragHandle}
                  <div className="flex-1 space-y-3">
                    <input
                      type="text"
                      value={c.title}
                      onChange={(e) => updateCollection(c.id, { title: e.target.value })}
                      className="w-full rounded border border-slate-700 bg-slate-900/40 px-2 py-1 text-base font-semibold text-slate-100 outline-none focus:border-primary"
                      placeholder="Collection title"
                    />
                    <div className="flex flex-wrap items-center gap-3">
                      <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-300">
                        <input
                          type="checkbox"
                          checked={Boolean(c.pinToTop)}
                          onChange={(e) =>
                            updateCollection(c.id, { pinToTop: e.target.checked })
                          }
                          className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                        />
                        <Pin className="h-3 w-3" /> Pin to top
                      </label>
                      <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-300">
                        <input
                          type="checkbox"
                          checked={Boolean(c.showAllTab ?? true)}
                          onChange={(e) =>
                            updateCollection(c.id, { showAllTab: e.target.checked })
                          }
                          className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                        />
                        Show &quot;All&quot; tab
                      </label>
                      <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-300">
                        <input
                          type="checkbox"
                          checked={Boolean(c.focusGlowEnabled ?? true)}
                          onChange={(e) =>
                            updateCollection(c.id, { focusGlowEnabled: e.target.checked })
                          }
                          className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                        />
                        Focus glow
                      </label>
                    </div>
                    <label className="block">
                      <span className="mb-1 block text-xs uppercase tracking-wide text-slate-500">
                        Backdrop image URL
                      </span>
                      <input
                        type="url"
                        placeholder="https://…"
                        value={c.backdropImageUrl ?? ""}
                        onChange={(e) =>
                          updateCollection(c.id, {
                            backdropImageUrl: e.target.value || null,
                          })
                        }
                        className="w-full rounded border border-slate-700 bg-slate-900/40 px-2 py-1 font-mono text-xs text-slate-100 outline-none focus:border-primary"
                      />
                    </label>
                  </div>
                  <button
                    type="button"
                    onClick={() => removeCollection(c.id)}
                    className="flex h-8 w-8 items-center justify-center rounded text-slate-500 hover:text-rose-300"
                    aria-label="Remove collection"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>

                <div className="space-y-2 p-4">
                  <div className="flex items-baseline justify-between">
                    <h3 className="text-xs uppercase tracking-wide text-slate-400">
                      Folders ({folders.length})
                    </h3>
                  </div>
                  {folders.length > 0 && (
                    <SortableList
                      items={folders as (CollectionFolder & { id: string })[]}
                      onReorder={(next) => reorderFolders(c.id, next)}
                      renderItem={(f, _i, fh) => {
                        return (
                          <div className="rounded-lg border border-slate-700/40 bg-slate-900/40 p-3">
                            <div className="flex items-start gap-3">
                              {fh}
                              <div className="flex-1 space-y-2">
                                <div className="flex gap-2">
                                  <input
                                    type="text"
                                    value={f.coverEmoji ?? ""}
                                    onChange={(e) =>
                                      updateFolder(c.id, f.id, {
                                        coverEmoji: e.target.value || null,
                                      })
                                    }
                                    placeholder="🎬"
                                    maxLength={4}
                                    className="w-16 rounded border border-slate-700 bg-slate-950/40 px-2 py-1 text-center text-base text-slate-100 outline-none focus:border-primary"
                                  />
                                  <input
                                    type="text"
                                    value={f.title}
                                    onChange={(e) =>
                                      updateFolder(c.id, f.id, { title: e.target.value })
                                    }
                                    className="flex-1 rounded border border-slate-700 bg-slate-950/40 px-2 py-1 text-sm text-slate-100 outline-none focus:border-primary"
                                    placeholder="Folder title"
                                  />
                                </div>
                                <div className="flex flex-wrap items-center gap-3">
                                  <label className="flex items-center gap-1 text-xs text-slate-400">
                                    <span>Shape</span>
                                    <select
                                      value={f.tileShape ?? "SQUARE"}
                                      onChange={(e) =>
                                        updateFolder(c.id, f.id, {
                                          tileShape: e.target.value,
                                        })
                                      }
                                      className="rounded border border-slate-700 bg-slate-950/40 px-1 py-0.5 text-xs text-slate-100"
                                    >
                                      {TILE_SHAPES.map((s) => (
                                        <option key={s} value={s}>
                                          {s.toLowerCase()}
                                        </option>
                                      ))}
                                    </select>
                                  </label>
                                  <label className="flex cursor-pointer items-center gap-1 text-xs text-slate-300">
                                    <input
                                      type="checkbox"
                                      checked={Boolean(f.hideTitle)}
                                      onChange={(e) =>
                                        updateFolder(c.id, f.id, {
                                          hideTitle: e.target.checked,
                                        })
                                      }
                                      className="h-3.5 w-3.5 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                                    />
                                    Hide title
                                  </label>
                                  <span className="text-xs text-slate-500">
                                    {(f.catalogSources ?? []).length} source
                                    {(f.catalogSources ?? []).length === 1 ? "" : "s"}
                                  </span>
                                </div>
                                <input
                                  type="url"
                                  placeholder="cover image URL"
                                  value={f.coverImageUrl ?? ""}
                                  onChange={(e) =>
                                    updateFolder(c.id, f.id, {
                                      coverImageUrl: e.target.value || null,
                                    })
                                  }
                                  className="w-full rounded border border-slate-700 bg-slate-950/40 px-2 py-1 font-mono text-xs text-slate-100 outline-none focus:border-primary"
                                />
                              </div>
                              <button
                                type="button"
                                onClick={() => removeFolder(c.id, f.id)}
                                className="flex h-7 w-7 items-center justify-center rounded text-slate-500 hover:text-rose-300"
                                aria-label="Remove folder"
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                              </button>
                            </div>
                          </div>
                        );
                      }}
                    />
                  )}
                </div>
              </div>
            );
          }}
        />
      )}

      <p className="text-xs text-slate-500">
        Catalog sources are read-only here. Add or remove sources from the TV
        (catalog picker comes in v3). Image upload to Supabase Storage is also v3 —
        paste a URL for now.
      </p>

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={() => {
          setItems(initial);
          setState({ kind: "idle" });
        }}
      />
    </div>
  );
}
