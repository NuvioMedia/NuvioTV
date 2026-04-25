"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle } from "lucide-react";
import { deleteProfileData } from "@/lib/actions/relational";
import type { Profile, AvatarEntry } from "@/lib/data/profiles";
import ProfilesForm from "./ProfilesForm";

interface Props {
  profiles: Profile[];
  avatarCatalog: AvatarEntry[];
  currentProfileId: number;
}

export default function ProfilesManager({
  profiles,
  avatarCatalog,
  currentProfileId,
}: Props) {
  const router = useRouter();
  const [pendingDelete, setPendingDelete] = useState<{
    profileIndex: number;
    name: string;
  } | null>(null);
  const [confirmText, setConfirmText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  const onConfirmDelete = () => {
    if (!pendingDelete) return;
    if (confirmText !== "DELETE") {
      setError(`Type DELETE to confirm.`);
      return;
    }
    setError(null);
    startTransition(async () => {
      const result = await deleteProfileData({
        profileId: pendingDelete.profileIndex,
        revalidatePath: `/profiles`,
      });
      if (result.ok) {
        // If we just deleted the profile we are viewing, redirect to picker.
        if (pendingDelete.profileIndex === currentProfileId) {
          router.push("/profiles");
        } else {
          router.refresh();
        }
        setPendingDelete(null);
        setConfirmText("");
      } else if ("conflict" in result && result.conflict) {
        setError("Conflict — page will refresh.");
        router.refresh();
      } else {
        setError(result.error);
      }
    });
  };

  return (
    <>
      <ProfilesForm
        profiles={profiles}
        avatarCatalog={avatarCatalog}
        onRequestDelete={(profileIndex, name) => {
          setPendingDelete({ profileIndex, name });
          setConfirmText("");
          setError(null);
        }}
      />

      {pendingDelete && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 p-4">
          <div className="w-full max-w-md rounded-2xl border border-rose-500/40 bg-slate-900 p-6 shadow-2xl">
            <div className="mb-3 flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-rose-300" />
              <h2 className="text-lg font-semibold text-slate-100">
                Delete profile {pendingDelete.profileIndex}
                {pendingDelete.name && ` (“${pendingDelete.name}”)`}?
              </h2>
            </div>
            <p className="mb-4 text-sm text-slate-300">
              This permanently removes the profile and its addons, plugins, library,
              watch progress, watched items, and synced settings from the cloud.
              Local data on TVs that haven&apos;t synced yet is not affected.
            </p>
            <label className="mb-4 block text-sm">
              <span className="mb-1 block text-xs uppercase tracking-wide text-slate-400">
                Type DELETE to confirm
              </span>
              <input
                type="text"
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                autoFocus
                className="w-full rounded-lg border border-slate-700 bg-slate-950/50 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-rose-400"
              />
            </label>
            {error && <p className="mb-3 text-xs text-rose-300">{error}</p>}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setPendingDelete(null);
                  setConfirmText("");
                  setError(null);
                }}
                disabled={isPending}
                className="rounded-lg border border-slate-700 px-3 py-1.5 text-sm text-slate-300 hover:border-slate-600"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={onConfirmDelete}
                disabled={isPending || confirmText !== "DELETE"}
                className="rounded-lg bg-rose-500 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-rose-400 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {isPending ? "Deleting…" : "Delete profile"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
