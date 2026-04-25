"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import { tmdbSettingsSchema, type TmdbSettings } from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: TmdbSettings;
  expectedUpdatedAt: string | null;
}

interface ToggleSpec {
  key: keyof TmdbSettings;
  label: string;
  blurb?: string;
}

const PRIMARY_TOGGLES: ToggleSpec[] = [
  {
    key: "tmdb_enabled",
    label: "Enable TMDB",
    blurb: "Master switch. Disabling stops all TMDB enrichment.",
  },
  {
    key: "tmdb_modern_home_enabled",
    label: "Modern home",
    blurb: "Use TMDB-powered home rows instead of legacy catalog rows.",
  },
  {
    key: "tmdb_enrich_continue_watching",
    label: "Enrich Continue Watching",
    blurb: "Pull artwork and episode metadata for in-progress shows.",
  },
];

const FIELD_TOGGLES: ToggleSpec[] = [
  { key: "tmdb_use_artwork", label: "Artwork" },
  { key: "tmdb_use_basic_info", label: "Basic info (title, overview, year)" },
  { key: "tmdb_use_details", label: "Details (runtime, status, genres)" },
  { key: "tmdb_use_release_dates", label: "Release dates" },
  { key: "tmdb_use_credits", label: "Cast & crew" },
  { key: "tmdb_use_productions", label: "Production companies" },
  { key: "tmdb_use_networks", label: "Networks" },
  { key: "tmdb_use_episodes", label: "Episode metadata" },
  { key: "tmdb_use_more_like_this", label: "More like this" },
  { key: "tmdb_use_collections", label: "Collections (movie franchises)" },
];

const LANGUAGE_OPTIONS = [
  { code: "en", label: "English" },
  { code: "de", label: "German" },
  { code: "fr", label: "French" },
  { code: "es", label: "Spanish" },
  { code: "it", label: "Italian" },
  { code: "pt", label: "Portuguese" },
  { code: "ru", label: "Russian" },
  { code: "ja", label: "Japanese" },
  { code: "ko", label: "Korean" },
  { code: "zh", label: "Chinese" },
  { code: "tr", label: "Turkish" },
  { code: "pl", label: "Polish" },
  { code: "nl", label: "Dutch" },
  { code: "sv", label: "Swedish" },
];

function signature(s: TmdbSettings): string {
  // Object key order is stable within a session because we only mutate values,
  // never re-create the object with reshuffled keys, so plain JSON.stringify
  // is a sufficient dirty-detection signature.
  return JSON.stringify(s);
}

export default function TmdbSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<TmdbSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => signature(initial), [initial]);
  const currentSig = useMemo(() => signature(values), [values]);
  const dirty = initialSig !== currentSig;

  useUnsavedWarning(dirty);

  // Validate before save so the SaveBar can disable submit.
  const validation = tmdbSettingsSchema.safeParse(values);

  const update = <K extends keyof TmdbSettings>(key: K, value: TmdbSettings[K]) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "tmdb_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/integrations/tmdb`,
      });
      if (result.ok) {
        setState({ kind: "saved", at: Date.now() });
        router.refresh();
        setTimeout(() => {
          setState((s) => (s.kind === "saved" ? { kind: "idle" } : s));
        }, 2000);
      } else if ("conflict" in result && result.conflict) {
        setState({ kind: "conflict" });
        router.refresh();
      } else {
        setState({ kind: "error", message: result.error });
      }
    });
  };

  const handleDiscard = () => {
    setValues(initial);
    setState({ kind: "idle" });
  };

  const masterDisabled = values.tmdb_enabled === false;

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">General</h2>
        <div className="space-y-3">
          {PRIMARY_TOGGLES.map((t) => (
            <ToggleRow
              key={t.key}
              label={t.label}
              blurb={t.blurb}
              checked={Boolean(values[t.key])}
              onChange={(checked) => update(t.key, checked as never)}
              disabled={t.key !== "tmdb_enabled" && masterDisabled}
            />
          ))}
        </div>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-1 text-lg font-medium">Language</h2>
        <p className="mb-3 text-xs text-slate-400">
          ISO 639-1 code used for TMDB metadata lookups (e.g. <code>en</code>,
          <code>de</code>). Leave blank for TMDB&apos;s default.
        </p>
        <div className="flex gap-2">
          <select
            value={
              LANGUAGE_OPTIONS.some((o) => o.code === values.tmdb_language)
                ? values.tmdb_language
                : "__custom"
            }
            onChange={(e) => {
              if (e.target.value === "__custom") return;
              update("tmdb_language", e.target.value);
            }}
            disabled={masterDisabled}
            className="rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm text-slate-100 outline-none focus:border-primary disabled:opacity-50"
          >
            {LANGUAGE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label} ({o.code})
              </option>
            ))}
            <option value="__custom">Custom…</option>
          </select>
          <input
            type="text"
            placeholder="ISO 639-1"
            value={values.tmdb_language ?? ""}
            onChange={(e) => update("tmdb_language", e.target.value)}
            disabled={masterDisabled}
            className="w-32 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 text-sm font-mono text-slate-100 outline-none focus:border-primary disabled:opacity-50"
          />
        </div>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-1 text-lg font-medium">Override fields with TMDB data</h2>
        <p className="mb-3 text-xs text-slate-400">
          Pick which metadata fields TMDB is allowed to fill in. Disable any field
          you prefer to keep from the addon source.
        </p>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {FIELD_TOGGLES.map((t) => (
            <ToggleRow
              key={t.key}
              label={t.label}
              checked={Boolean(values[t.key])}
              onChange={(checked) => update(t.key, checked as never)}
              disabled={masterDisabled}
              compact
            />
          ))}
        </div>
      </section>

      {!validation.success && (
        <div className="rounded-2xl border border-rose-500/40 bg-rose-500/10 p-4 text-sm text-rose-200">
          Invalid values:
          <ul className="ml-4 mt-1 list-disc text-xs">
            {validation.error.issues.map((i, n) => (
              <li key={n}>
                <code>{i.path.join(".")}</code>: {i.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      <SaveBar
        dirty={dirty}
        state={isPending ? { kind: "saving" } : state}
        onSave={handleSave}
        onDiscard={handleDiscard}
        disabled={!validation.success}
      />
    </div>
  );
}

function ToggleRow({
  label,
  blurb,
  checked,
  onChange,
  disabled,
  compact,
}: {
  label: string;
  blurb?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  compact?: boolean;
}) {
  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-lg border border-transparent ${
        compact ? "p-2" : "p-3"
      } transition hover:border-slate-700/60 hover:bg-slate-900/30 ${
        disabled ? "cursor-not-allowed opacity-50" : ""
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={disabled}
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-slate-600 bg-slate-900 text-primary focus:ring-primary"
      />
      <span className="min-w-0 flex-1">
        <span className="block text-sm text-slate-100">{label}</span>
        {blurb && !compact && (
          <span className="mt-0.5 block text-xs text-slate-400">{blurb}</span>
        )}
      </span>
    </label>
  );
}
