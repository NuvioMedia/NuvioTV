"use client";

import { useMemo, useState, useTransition } from "react";
import { ExternalLink } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  traktSettingsSchema,
  type TraktSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: TraktSettings;
  expectedUpdatedAt: string | null;
}

const SOURCES: { value: string; label: string; blurb: string }[] = [
  {
    value: "TRAKT",
    label: "Trakt",
    blurb: "Read continue-watching from your Trakt account.",
  },
  {
    value: "NUVIO_SYNC",
    label: "Local sync",
    blurb: "Use the device-local watch progress that syncs across your TVs.",
  },
];

export default function TraktSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<TraktSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  // Preserve dismissed_next_up_keys on every save (TraktSettingsDataStore.kt
  // owns it via per-content writes; the panel never edits this set, but
  // dropping it from a partial-merge encode would erase whatever the TV had).
  const valuesToSave = useMemo<TraktSettings>(
    () => ({
      ...values,
      dismissed_next_up_keys: initial.dismissed_next_up_keys,
    }),
    [values, initial.dismissed_next_up_keys]
  );
  const validation = traktSettingsSchema.safeParse(valuesToSave);

  const daysCap = values.continue_watching_days_cap ?? 60;
  // 0 means "all"; otherwise constrained 7..365 by the TV side.
  const daysCapValid =
    daysCap === 0 || (Number.isInteger(daysCap) && daysCap >= 7 && daysCap <= 365);

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "trakt_settings",
        decodedValues: valuesToSave,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/integrations/trakt`,
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
      <section className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
        <div className="font-medium">OAuth on the TV</div>
        <p className="mt-1 text-xs text-amber-200/80">
          The Trakt access token is stored per-device on each TV (not in the
          synced profile). Sign in or re-authenticate from{" "}
          <strong>Settings → Integrations → Trakt</strong> on a TV.
          <a
            href="https://trakt.tv/settings/applications"
            target="_blank"
            rel="noreferrer"
            className="ml-1 inline-flex items-center gap-1 underline hover:text-amber-50"
          >
            trakt.tv settings <ExternalLink className="h-3 w-3" />
          </a>
        </p>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Watch progress source</h2>
        <div className="space-y-2">
          {SOURCES.map((s) => (
            <label
              key={s.value}
              className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition ${
                values.watch_progress_source === s.value
                  ? "border-primary bg-primary/10"
                  : "border-slate-700 hover:border-slate-600"
              }`}
            >
              <input
                type="radio"
                name="source"
                value={s.value}
                checked={values.watch_progress_source === s.value}
                onChange={() =>
                  setValues((v) => ({ ...v, watch_progress_source: s.value }))
                }
                className="mt-0.5 h-4 w-4"
              />
              <span>
                <span className="block text-sm text-slate-100">{s.label}</span>
                <span className="mt-0.5 block text-xs text-slate-400">{s.blurb}</span>
              </span>
            </label>
          ))}
        </div>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Continue Watching</h2>
        <label className="block">
          <span className="mb-1 block text-sm text-slate-100">Days to keep on the row</span>
          <span className="mb-2 block text-xs text-slate-400">
            <code>0</code> = no cap; otherwise 7–365.
          </span>
          <input
            type="number"
            min={0}
            max={365}
            step={1}
            value={daysCap}
            onChange={(e) =>
              setValues((v) => ({
                ...v,
                continue_watching_days_cap: Number.parseInt(e.target.value, 10) || 0,
              }))
            }
            className="w-24 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary"
          />
          {!daysCapValid && (
            <p className="mt-1 text-xs text-rose-300">
              Must be 0 (no cap) or between 7 and 365.
            </p>
          )}
        </label>

        <label className="mt-4 flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            checked={Boolean(values.show_unaired_next_up)}
            onChange={(e) =>
              setValues((v) => ({ ...v, show_unaired_next_up: e.target.checked }))
            }
            className="mt-0.5 h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
          />
          <span>
            <span className="block text-sm text-slate-100">Show unaired Next Up</span>
            <span className="mt-0.5 block text-xs text-slate-400">
              Surface upcoming episodes that haven&apos;t aired yet.
            </span>
          </span>
        </label>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Comments</h2>
        <label className="flex cursor-pointer items-start gap-3">
          <input
            type="checkbox"
            checked={Boolean(values.show_meta_comments)}
            onChange={(e) =>
              setValues((v) => ({ ...v, show_meta_comments: e.target.checked }))
            }
            className="mt-0.5 h-4 w-4 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
          />
          <span>
            <span className="block text-sm text-slate-100">Show comments on detail pages</span>
            <span className="mt-0.5 block text-xs text-slate-400">
              Pulls Trakt user comments into the metadata view.
            </span>
          </span>
        </label>
      </section>

      {(initial.dismissed_next_up_keys?.length ?? 0) > 0 && (
        <p className="text-xs text-slate-500">
          {initial.dismissed_next_up_keys?.length} dismissed Next Up entries are
          preserved on save (managed per-content from the TV).
        </p>
      )}

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={() => {
          setValues(initial);
          setState({ kind: "idle" });
        }}
        disabled={!validation.success || !daysCapValid}
      />
    </div>
  );
}
