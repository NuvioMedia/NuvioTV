"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Trash2, User } from "lucide-react";
import { saveProfiles } from "@/lib/actions/relational";
import type { Profile, AvatarEntry } from "@/lib/data/profiles";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface ProfileEdit {
  id: string;
  profile_index: number;
  name: string;
  avatar_color_hex: string;
  uses_primary_addons: boolean;
  uses_primary_plugins: boolean;
  avatar_id: string | null;
}

interface Props {
  profiles: Profile[];
  avatarCatalog: AvatarEntry[];
  onRequestDelete: (profileIndex: number, name: string) => void;
}

const COLOR_PRESETS = [
  "#1E88E5",
  "#43A047",
  "#FB8C00",
  "#E53935",
  "#8E24AA",
  "#00ACC1",
  "#FDD835",
  "#5E35B1",
];

export default function ProfilesForm({
  profiles,
  avatarCatalog,
  onRequestDelete,
}: Props) {
  const router = useRouter();
  const initial: ProfileEdit[] = profiles.map((p) => ({
    id: p.id,
    profile_index: p.profile_index,
    name: p.name,
    avatar_color_hex: p.avatar_color_hex,
    uses_primary_addons: p.uses_primary_addons,
    uses_primary_plugins: p.uses_primary_plugins,
    avatar_id: p.avatar_id,
  }));

  const [items, setItems] = useState<ProfileEdit[]>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(items), [items]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  const avatarById = useMemo(
    () => Object.fromEntries(avatarCatalog.map((a) => [a.id, a])),
    [avatarCatalog],
  );

  const update = (idx: number, patch: Partial<ProfileEdit>) => {
    setItems((prev) =>
      prev.map((p) => (p.profile_index === idx ? { ...p, ...patch } : p))
    );
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveProfiles({
        profiles: items.map((p) => ({
          profile_index: p.profile_index,
          name: p.name,
          avatar_color_hex: p.avatar_color_hex,
          uses_primary_addons: p.uses_primary_addons,
          uses_primary_plugins: p.uses_primary_plugins,
          avatar_id: p.avatar_id,
        })),
        revalidatePath: `/profiles`,
      });
      if (result.ok) {
        setState({ kind: "saved", at: Date.now() });
        router.refresh();
        setTimeout(() => setState((s) => (s.kind === "saved" ? { kind: "idle" } : s)), 2000);
      } else if ("conflict" in result && result.conflict) {
        setState({ kind: "conflict" });
      } else {
        setState({ kind: "error", message: result.error });
      }
    });
  };

  return (
    <div className="space-y-6">
      {items.map((p) => {
        const isPrimary = p.profile_index === 1;
        const avatar = p.avatar_id ? avatarById[p.avatar_id] : null;
        return (
          <section
            key={p.profile_index}
            className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5"
          >
            <div className="mb-4 flex items-start gap-4">
              <div
                className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-full text-2xl font-semibold text-white"
                style={{ backgroundColor: avatar?.bg_color ?? p.avatar_color_hex }}
              >
                {avatar ? (
                  <img
                    src={avatar.image_url}
                    alt={avatar.display_name}
                    className="h-full w-full object-cover"
                  />
                ) : p.name ? (
                  p.name[0]?.toUpperCase()
                ) : (
                  <User className="h-7 w-7" />
                )}
              </div>
              <div className="flex-1 space-y-3">
                <div className="flex items-baseline gap-2">
                  <h2 className="text-lg font-medium text-slate-100">
                    Profile {p.profile_index}
                  </h2>
                  {isPrimary && (
                    <span className="rounded-full bg-amber-500/20 px-2 py-0.5 text-xs text-amber-300">
                      primary
                    </span>
                  )}
                </div>
                <input
                  type="text"
                  value={p.name}
                  placeholder={`Profile ${p.profile_index}`}
                  onChange={(e) => update(p.profile_index, { name: e.target.value })}
                  className="w-full rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary"
                />
              </div>
              {!isPrimary && (
                <button
                  type="button"
                  onClick={() => onRequestDelete(p.profile_index, p.name)}
                  className="flex h-8 w-8 items-center justify-center rounded text-slate-500 hover:text-rose-300"
                  aria-label="Delete profile"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              )}
            </div>

            <div className="mb-4">
              <span className="mb-2 block text-xs font-medium uppercase tracking-wide text-slate-400">
                Avatar colour
              </span>
              <div className="flex flex-wrap items-center gap-2">
                {COLOR_PRESETS.map((color) => (
                  <button
                    key={color}
                    type="button"
                    onClick={() => update(p.profile_index, { avatar_color_hex: color })}
                    className={`h-8 w-8 rounded-full border-2 transition ${
                      p.avatar_color_hex.toLowerCase() === color.toLowerCase()
                        ? "border-white"
                        : "border-transparent hover:border-slate-500"
                    }`}
                    style={{ backgroundColor: color }}
                    aria-label={`Set color ${color}`}
                  />
                ))}
                <label className="flex items-center gap-2">
                  <input
                    type="color"
                    value={p.avatar_color_hex}
                    onChange={(e) =>
                      update(p.profile_index, { avatar_color_hex: e.target.value })
                    }
                    className="h-8 w-8 cursor-pointer rounded border border-slate-700 bg-transparent"
                  />
                  <span className="font-mono text-xs text-slate-400">
                    {p.avatar_color_hex}
                  </span>
                </label>
              </div>
            </div>

            <div className="mb-4">
              <span className="mb-2 block text-xs font-medium uppercase tracking-wide text-slate-400">
                Avatar
              </span>
              <select
                value={p.avatar_id ?? ""}
                onChange={(e) =>
                  update(p.profile_index, { avatar_id: e.target.value || null })
                }
                className="rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary"
              >
                <option value="">(initial letter)</option>
                {avatarCatalog.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.display_name} ({a.category})
                  </option>
                ))}
              </select>
            </div>

            {!isPrimary && (
              <div className="space-y-2">
                <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-300">
                  <input
                    type="checkbox"
                    checked={p.uses_primary_addons}
                    onChange={(e) =>
                      update(p.profile_index, { uses_primary_addons: e.target.checked })
                    }
                    className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                  />
                  Share addons with primary profile
                </label>
                <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-300">
                  <input
                    type="checkbox"
                    checked={p.uses_primary_plugins}
                    onChange={(e) =>
                      update(p.profile_index, { uses_primary_plugins: e.target.checked })
                    }
                    className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
                  />
                  Share plugins with primary profile
                </label>
              </div>
            )}
          </section>
        );
      })}

      <p className="text-xs text-slate-500">
        Adding new profiles is done from the TV. PIN management is also TV-only
        for now.
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
