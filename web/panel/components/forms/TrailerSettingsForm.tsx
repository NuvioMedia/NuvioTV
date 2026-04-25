"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  trailerSettingsSchema,
  type TrailerSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: TrailerSettings;
  expectedUpdatedAt: string | null;
}

export default function TrailerSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<TrailerSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  const validation = trailerSettingsSchema.safeParse(values);
  const delayInRange =
    values.trailer_delay_seconds === undefined ||
    (Number.isInteger(values.trailer_delay_seconds) &&
      values.trailer_delay_seconds >= 0 &&
      values.trailer_delay_seconds <= 60);

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "trailer_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/settings/trailer`,
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
            checked={Boolean(values.trailer_enabled)}
            onChange={(e) =>
              setValues((v) => ({ ...v, trailer_enabled: e.target.checked }))
            }
            className="mt-0.5 h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
          />
          <span>
            <span className="block text-sm text-slate-100">Auto-play trailers</span>
            <span className="mt-0.5 block text-xs text-slate-400">
              Plays the YouTube trailer over a focused poster after a short delay.
            </span>
          </span>
        </label>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <label className="block">
          <span className="mb-1 block text-sm text-slate-100">Delay before play</span>
          <span className="mb-2 block text-xs text-slate-400">Seconds (0–60).</span>
          <input
            type="number"
            min={0}
            max={60}
            step={1}
            value={values.trailer_delay_seconds ?? 7}
            onChange={(e) =>
              setValues((v) => ({
                ...v,
                trailer_delay_seconds: Number.parseInt(e.target.value, 10) || 0,
              }))
            }
            disabled={!values.trailer_enabled}
            className="w-24 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary disabled:opacity-50"
          />
        </label>
        {!delayInRange && (
          <p className="mt-1 text-xs text-rose-300">Delay must be 0–60 seconds.</p>
        )}
      </section>

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={() => {
          setValues(initial);
          setState({ kind: "idle" });
        }}
        disabled={!validation.success || !delayInRange}
      />
    </div>
  );
}
