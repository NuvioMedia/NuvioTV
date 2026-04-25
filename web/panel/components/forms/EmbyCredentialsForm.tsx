"use client";

import { useMemo, useState, useTransition } from "react";
import { Eye, EyeOff } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  embyCredentialsSchema,
  type EmbyCredentials,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: EmbyCredentials;
  expectedUpdatedAt: string | null;
}

export default function EmbyCredentialsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<EmbyCredentials>(initial);
  const [showKey, setShowKey] = useState(false);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;

  useUnsavedWarning(dirty);

  // Trim trailing slash from URL on save (matches EmbyCredentialsDataStore.kt:75).
  const normalized = useMemo<EmbyCredentials>(
    () => ({
      emby_server_url: values.emby_server_url?.trim().replace(/\/+$/, ""),
      emby_api_key: values.emby_api_key?.trim(),
      emby_user_id: values.emby_user_id?.trim(),
    }),
    [values]
  );

  const validation = embyCredentialsSchema.safeParse(normalized);
  const urlValid =
    !normalized.emby_server_url ||
    /^https?:\/\//.test(normalized.emby_server_url);

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "emby_credentials",
        decodedValues: normalized,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/integrations/emby`,
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
      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Server</h2>
        <label className="block">
          <span className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-400">
            Server URL
          </span>
          <input
            type="url"
            placeholder="https://emby.example.com"
            value={values.emby_server_url ?? ""}
            onChange={(e) =>
              setValues((v) => ({ ...v, emby_server_url: e.target.value }))
            }
            className="w-full rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-primary"
          />
        </label>
        {!urlValid && (
          <p className="mt-1 text-xs text-rose-300">
            URL must start with http:// or https://
          </p>
        )}
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Authentication</h2>
        <label className="block">
          <span className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-400">
            API key
          </span>
          <div className="flex gap-2">
            <input
              type={showKey ? "text" : "password"}
              placeholder="paste API key from Emby dashboard"
              value={values.emby_api_key ?? ""}
              onChange={(e) =>
                setValues((v) => ({ ...v, emby_api_key: e.target.value }))
              }
              autoComplete="off"
              spellCheck={false}
              className="flex-1 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-primary"
            />
            <button
              type="button"
              onClick={() => setShowKey((v) => !v)}
              className="flex h-10 w-10 items-center justify-center rounded-lg border border-slate-700 text-slate-400 hover:text-slate-100"
              aria-label={showKey ? "Hide API key" : "Show API key"}
            >
              {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </label>
        <label className="mt-4 block">
          <span className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-400">
            User ID
          </span>
          <input
            type="text"
            placeholder="GUID from Emby user profile"
            value={values.emby_user_id ?? ""}
            onChange={(e) =>
              setValues((v) => ({ ...v, emby_user_id: e.target.value }))
            }
            autoComplete="off"
            spellCheck={false}
            className="w-full rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-primary"
          />
        </label>
        <p className="mt-3 text-xs text-slate-500">
          The per-device <code>emby_device_id</code> is managed by each TV and is
          not edited from the panel.
        </p>
      </section>

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={() => {
          setValues(initial);
          setState({ kind: "idle" });
        }}
        disabled={!validation.success || !urlValid}
      />
    </div>
  );
}
