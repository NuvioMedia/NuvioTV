"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  themeSettingsSchema,
  type ThemeSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: ThemeSettings;
  expectedUpdatedAt: string | null;
}

// Mirrors domain/model/AppTheme.kt and AppFont.kt — the TV stores the enum
// `name()` as a string, so the panel must write the same uppercase tokens.
const THEMES: { value: string; label: string }[] = [
  { value: "CRIMSON", label: "Crimson" },
  { value: "OCEAN", label: "Ocean" },
  { value: "VIOLET", label: "Violet" },
  { value: "EMERALD", label: "Emerald" },
  { value: "AMBER", label: "Amber" },
  { value: "ROSE", label: "Rose" },
  { value: "WHITE", label: "White" },
];

const FONTS: { value: string; label: string }[] = [
  { value: "INTER", label: "Inter" },
  { value: "DM_SANS", label: "DM Sans" },
  { value: "OPEN_SANS", label: "Open Sans" },
];

export default function ThemeSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<ThemeSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  const validation = themeSettingsSchema.safeParse(values);

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "theme_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/settings/theme`,
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
        <h2 className="mb-3 text-lg font-medium">Theme</h2>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {THEMES.map((t) => (
            <label
              key={t.value}
              className={`flex cursor-pointer items-center gap-2 rounded-lg border p-3 transition ${
                values.selected_theme === t.value
                  ? "border-primary bg-primary/10"
                  : "border-slate-700 hover:border-slate-600"
              }`}
            >
              <input
                type="radio"
                name="theme"
                value={t.value}
                checked={values.selected_theme === t.value}
                onChange={() =>
                  setValues((v) => ({ ...v, selected_theme: t.value }))
                }
                className="h-4 w-4"
              />
              <span className="text-sm text-slate-100">{t.label}</span>
            </label>
          ))}
        </div>
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Font</h2>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          {FONTS.map((f) => (
            <label
              key={f.value}
              className={`flex cursor-pointer items-center gap-2 rounded-lg border p-3 transition ${
                values.selected_font === f.value
                  ? "border-primary bg-primary/10"
                  : "border-slate-700 hover:border-slate-600"
              }`}
            >
              <input
                type="radio"
                name="font"
                value={f.value}
                checked={values.selected_font === f.value}
                onChange={() =>
                  setValues((v) => ({ ...v, selected_font: f.value }))
                }
                className="h-4 w-4"
              />
              <span className="text-sm text-slate-100">{f.label}</span>
            </label>
          ))}
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
