"use client";

import { useMemo, useState, useTransition } from "react";
import { Eye, EyeOff } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  animeskipSettingsSchema,
  type AnimeskipSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: AnimeskipSettings;
  expectedUpdatedAt: string | null;
}

export default function AnimeskipSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<AnimeskipSettings>(initial);
  const [showKey, setShowKey] = useState(false);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;

  useUnsavedWarning(dirty);

  const validation = animeskipSettingsSchema.safeParse(values);

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "animeskip_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/integrations/animeskip`,
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
        <label className="flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            checked={Boolean(values.animeskip_enabled)}
            onChange={(e) =>
              setValues((v) => ({ ...v, animeskip_enabled: e.target.checked }))
            }
            className="mt-0.5 h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
          />
          <span>
            <span className="block text-sm text-slate-100">Enable AnimeSkip</span>
            <span className="mt-0.5 block text-xs text-slate-400">
              Auto-skip intros and outros for anime using community-curated timestamps.
            </span>
          </span>
        </label>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-1 text-lg font-medium">Client ID</h2>
        <p className="mb-3 text-xs text-slate-400">
          Get one at{" "}
          <a
            href="https://api.aniskip.com"
            target="_blank"
            rel="noreferrer"
            className="underline hover:text-primary"
          >
            api.aniskip.com
          </a>
          .
        </p>
        <div className="flex gap-2">
          <input
            type={showKey ? "text" : "password"}
            placeholder="paste client id"
            value={values.animeskip_client_id ?? ""}
            onChange={(e) =>
              setValues((v) => ({ ...v, animeskip_client_id: e.target.value }))
            }
            disabled={!values.animeskip_enabled}
            autoComplete="off"
            spellCheck={false}
            className="flex-1 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-primary disabled:opacity-50"
          />
          <button
            type="button"
            onClick={() => setShowKey((v) => !v)}
            className="flex h-10 w-10 items-center justify-center rounded-lg border border-slate-700 text-slate-400 hover:text-slate-100"
            aria-label={showKey ? "Hide client id" : "Show client id"}
          >
            {showKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        </div>
      </section>

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={() => {
          setValues(initial);
          setState({ kind: "idle" });
        }}
        disabled={!validation.success}
      />
    </div>
  );
}
