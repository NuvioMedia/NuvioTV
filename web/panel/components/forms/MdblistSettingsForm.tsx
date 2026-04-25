"use client";

import { useMemo, useState, useTransition } from "react";
import { Eye, EyeOff } from "lucide-react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import { mdblistSettingsSchema, type MdblistSettings } from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: MdblistSettings;
  expectedUpdatedAt: string | null;
}

interface ToggleSpec {
  key: keyof MdblistSettings;
  label: string;
}

const SOURCE_TOGGLES: ToggleSpec[] = [
  { key: "mdblist_show_imdb", label: "IMDb" },
  { key: "mdblist_show_trakt", label: "Trakt" },
  { key: "mdblist_show_tmdb", label: "TMDB" },
  { key: "mdblist_show_letterboxd", label: "Letterboxd" },
  { key: "mdblist_show_tomatoes", label: "Rotten Tomatoes" },
  { key: "mdblist_show_audience", label: "RT Audience" },
  { key: "mdblist_show_metacritic", label: "Metacritic" },
];

export default function MdblistSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<MdblistSettings>(initial);
  const [showKey, setShowKey] = useState(false);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;

  useUnsavedWarning(dirty);

  const validation = mdblistSettingsSchema.safeParse(values);

  const update = <K extends keyof MdblistSettings>(key: K, value: MdblistSettings[K]) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "mdblist_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/integrations/mdblist`,
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

  const masterDisabled = values.mdblist_enabled === false;
  const apiKey = values.mdblist_api_key ?? "";

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">General</h2>
        <ToggleRow
          label="Enable MDBList"
          blurb="Master switch. Disabling stops all rating lookups and hides the badge row on detail pages."
          checked={Boolean(values.mdblist_enabled)}
          onChange={(checked) => update("mdblist_enabled", checked)}
        />
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-1 text-lg font-medium">API key</h2>
        <p className="mb-3 text-xs text-slate-400">
          Get one free at{" "}
          <a
            href="https://mdblist.com/preferences"
            target="_blank"
            rel="noreferrer"
            className="underline hover:text-primary"
          >
            mdblist.com/preferences
          </a>
          . Without a key, MDBList silently returns no data.
        </p>
        <div className="flex gap-2">
          <input
            type={showKey ? "text" : "password"}
            placeholder="paste API key"
            value={apiKey}
            onChange={(e) => update("mdblist_api_key", e.target.value)}
            disabled={masterDisabled}
            autoComplete="off"
            spellCheck={false}
            className="flex-1 rounded-lg border border-slate-700 bg-slate-900/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none focus:border-primary disabled:opacity-50"
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
        {!masterDisabled && apiKey.length === 0 && (
          <p className="mt-2 text-xs text-amber-300">
            MDBList is enabled but no API key is set — no ratings will load.
          </p>
        )}
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-1 text-lg font-medium">Sources to show</h2>
        <p className="mb-3 text-xs text-slate-400">
          Pick which rating sources appear in the MDBList badge row.
        </p>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {SOURCE_TOGGLES.map((t) => (
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
