import { useState } from "react";
import { createFileRoute } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2, Loader2, FolderOpen } from "lucide-react";
import {
  newFolder,
  pullCollections,
  pushCollections,
  type CollectionFolder,
} from "@/lib/collections";
import { parseProfileId } from "@/lib/profileContext";

export const Route = createFileRoute("/p/$profileId/settings/collections")({
  component: CollectionsSettingsPage,
});

function CollectionsSettingsPage() {
  const params = Route.useParams();
  const profileId = parseProfileId(params.profileId);
  const queryClient = useQueryClient();

  const { data: folders = [], isLoading } = useQuery({
    queryKey: ["collections", profileId],
    queryFn: () => pullCollections(profileId),
  });

  const save = useMutation({
    mutationFn: async (next: CollectionFolder[]) => {
      await pushCollections(profileId, next);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["collections", profileId] }),
  });

  const [newName, setNewName] = useState("");
  const [renameId, setRenameId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");

  function commit(next: CollectionFolder[]) {
    save.mutate(next);
  }

  function handleAdd(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!newName.trim()) return;
    const next = [...folders, newFolder(newName)];
    setNewName("");
    commit(next);
  }

  function handleRename(folder: CollectionFolder, name: string) {
    if (!name.trim()) return;
    const next = folders.map((f) =>
      f.id === folder.id ? { ...f, name: name.trim(), updatedAt: Date.now() } : f
    );
    setRenameId(null);
    setRenameValue("");
    commit(next);
  }

  function handleDelete(folder: CollectionFolder) {
    if (!confirm(`Delete collection "${folder.name}" and its ${folder.items.length} items?`)) return;
    commit(folders.filter((f) => f.id !== folder.id));
  }

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">Collections</h2>
      <p className="text-sm text-slate-400">
        Custom watchlists/folders. Items are added from the detail page on the TV; the web view
        lets you create folders and rename or delete them.
      </p>

      <form onSubmit={handleAdd} className="flex gap-2 rounded-lg border border-slate-800 bg-slate-900/40 p-3">
        <input
          type="text"
          required
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          placeholder="New collection name"
          className="flex-1 rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm outline-none focus:border-primary"
        />
        <button
          type="submit"
          disabled={save.isPending}
          className="flex items-center gap-1 rounded-md bg-primary px-3 py-2 text-sm font-medium text-white hover:bg-primary-hover disabled:opacity-50"
        >
          {save.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
          Create
        </button>
      </form>

      {isLoading ? (
        <div className="text-slate-500">Loading…</div>
      ) : folders.length === 0 ? (
        <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-4 text-sm text-slate-500">
          No collections yet.
        </div>
      ) : (
        <ul className="divide-y divide-slate-800/60 rounded-lg border border-slate-800 bg-slate-900/40">
          {folders.map((f) => (
            <li key={f.id} className="flex items-center gap-3 p-3">
              <FolderOpen className="h-5 w-5 text-amber-400" />
              <div className="min-w-0 flex-1">
                {renameId === f.id ? (
                  <input
                    autoFocus
                    type="text"
                    value={renameValue}
                    onChange={(e) => setRenameValue(e.target.value)}
                    onBlur={() => handleRename(f, renameValue)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") handleRename(f, renameValue);
                      if (e.key === "Escape") {
                        setRenameId(null);
                        setRenameValue("");
                      }
                    }}
                    className="w-full rounded-md border border-primary bg-slate-900 px-2 py-1 text-sm outline-none"
                  />
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      setRenameId(f.id);
                      setRenameValue(f.name);
                    }}
                    className="block w-full truncate text-left text-sm text-slate-200 hover:text-primary"
                  >
                    {f.name}
                  </button>
                )}
                <div className="text-xs text-slate-500">
                  {f.items.length} {f.items.length === 1 ? "item" : "items"}
                  {" · updated "}
                  {new Date(f.updatedAt).toLocaleDateString()}
                </div>
              </div>
              <button
                type="button"
                onClick={() => handleDelete(f)}
                title="Delete"
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
