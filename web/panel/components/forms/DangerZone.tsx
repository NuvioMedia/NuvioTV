"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, LogOut } from "lucide-react";
import { deleteProfileData } from "@/lib/actions/relational";
import { createBrowserSupabase } from "@/lib/supabase/browser";

interface Props {
  profileId: number;
  profileName: string;
}

export default function DangerZone({ profileId, profileName }: Props) {
  const router = useRouter();
  const [open, setOpen] = useState<null | "delete" | "globalSignOut">(null);
  const [confirmText, setConfirmText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  const handleDeleteData = () => {
    if (confirmText !== "DELETE") {
      setError("Type DELETE to confirm.");
      return;
    }
    setError(null);
    startTransition(async () => {
      const result = await deleteProfileData({
        profileId,
        revalidatePath: `/p/${profileId}/danger`,
      });
      if (result.ok) {
        if (profileId !== 1) {
          router.push("/profiles");
        } else {
          router.refresh();
          setOpen(null);
        }
      } else if ("conflict" in result && result.conflict) {
        setError("Conflict — page will refresh.");
        router.refresh();
      } else {
        setError(result.error);
      }
    });
  };

  const handleGlobalSignOut = () => {
    setError(null);
    startTransition(async () => {
      const supabase = createBrowserSupabase();
      const { error } = await supabase.auth.signOut({ scope: "global" });
      if (error) {
        setError(error.message);
        return;
      }
      router.push("/login");
    });
  };

  return (
    <>
      <section className="rounded-2xl border border-rose-500/40 bg-rose-500/5 p-5">
        <div className="mb-2 flex items-center gap-2">
          <AlertTriangle className="h-5 w-5 text-rose-300" />
          <h2 className="text-lg font-medium text-rose-100">Delete profile data</h2>
        </div>
        <p className="mb-4 text-sm text-rose-200/80">
          Removes all addons, plugins, library, watch progress, watched items, and
          synced settings for{" "}
          <strong>
            profile {profileId}
            {profileName && ` (“${profileName}”)`}
          </strong>{" "}
          from the cloud.
          {profileId === 1
            ? " The primary profile row stays (its PIN is cleared)."
            : " The profile row itself is also deleted."}{" "}
          Local data on TVs that haven&apos;t synced yet is unaffected.
        </p>
        <button
          type="button"
          onClick={() => {
            setOpen("delete");
            setConfirmText("");
            setError(null);
          }}
          className="rounded-lg border border-rose-500/50 bg-rose-500/10 px-3 py-1.5 text-sm font-medium text-rose-100 hover:bg-rose-500/20"
        >
          Delete profile {profileId} data
        </button>
      </section>

      <section className="rounded-2xl border border-amber-500/40 bg-amber-500/5 p-5">
        <div className="mb-2 flex items-center gap-2">
          <LogOut className="h-5 w-5 text-amber-300" />
          <h2 className="text-lg font-medium text-amber-100">
            Sign out everywhere (this account)
          </h2>
        </div>
        <p className="mb-4 text-sm text-amber-200/80">
          Invalidates every browser session for your account. TVs signed in with
          email/password will need to re-authenticate. Linked devices (paired via
          QR code) keep working — they have their own auth users.
        </p>
        <button
          type="button"
          onClick={() => {
            setOpen("globalSignOut");
            setError(null);
          }}
          className="rounded-lg border border-amber-500/50 bg-amber-500/10 px-3 py-1.5 text-sm font-medium text-amber-100 hover:bg-amber-500/20"
        >
          Sign out from all browsers
        </button>
      </section>

      {open === "delete" && (
        <Modal
          title={`Delete data for profile ${profileId}?`}
          danger
          onClose={() => {
            setOpen(null);
            setConfirmText("");
            setError(null);
          }}
        >
          <p className="text-sm text-slate-300">
            This is permanent. Type <code className="font-mono">DELETE</code> to
            confirm.
          </p>
          <input
            type="text"
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            autoFocus
            className="mt-3 w-full rounded-lg border border-slate-700 bg-slate-950/50 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-rose-400"
          />
          {error && <p className="mt-2 text-xs text-rose-300">{error}</p>}
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => {
                setOpen(null);
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
              onClick={handleDeleteData}
              disabled={isPending || confirmText !== "DELETE"}
              className="rounded-lg bg-rose-500 px-3 py-1.5 text-sm font-medium text-white hover:bg-rose-400 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {isPending ? "Deleting…" : "Delete"}
            </button>
          </div>
        </Modal>
      )}

      {open === "globalSignOut" && (
        <Modal title="Sign out everywhere?" onClose={() => setOpen(null)}>
          <p className="text-sm text-slate-300">
            You&apos;ll be redirected to the login page. Other browsers and TVs
            that signed in with email/password will be signed out on their next
            request.
          </p>
          {error && <p className="mt-2 text-xs text-rose-300">{error}</p>}
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setOpen(null)}
              disabled={isPending}
              className="rounded-lg border border-slate-700 px-3 py-1.5 text-sm text-slate-300 hover:border-slate-600"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleGlobalSignOut}
              disabled={isPending}
              className="rounded-lg bg-amber-500 px-3 py-1.5 text-sm font-medium text-slate-900 hover:bg-amber-400 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {isPending ? "Signing out…" : "Sign out everywhere"}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}

function Modal({
  title,
  danger,
  children,
  onClose,
}: {
  title: string;
  danger?: boolean;
  children: React.ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 p-4">
      <div
        className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl ${
          danger ? "border-rose-500/40 bg-slate-900" : "border-amber-500/40 bg-slate-900"
        }`}
      >
        <h2 className="mb-3 text-lg font-semibold text-slate-100">{title}</h2>
        {children}
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="absolute right-4 top-4 text-slate-500 hover:text-slate-200"
        >
          ×
        </button>
      </div>
    </div>
  );
}
