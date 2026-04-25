"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  layoutSettingsSchema,
  type LayoutSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { FieldRow, type FieldSpec } from "./FieldRow";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: LayoutSettings;
  expectedUpdatedAt: string | null;
}

type Spec = FieldSpec<keyof LayoutSettings & string>;

const SECTIONS: { title: string; fields: Spec[] }[] = [
  {
    title: "Layout style",
    fields: [
      {
        key: "selected_layout",
        label: "Layout",
        kind: "select",
        options: ["MODERN", "CLASSIC", "GRID"],
      },
      {
        key: "has_chosen_layout",
        label: "User chose layout (vs default)",
        kind: "boolean",
      },
      {
        key: "modern_sidebar_enabled",
        label: "Modern sidebar",
        kind: "boolean",
      },
      {
        key: "modern_sidebar_blur_enabled",
        label: "Sidebar blur",
        kind: "boolean",
      },
      {
        key: "glass_sidepanel_enabled",
        label: "Glass side panel",
        kind: "boolean",
      },
      {
        key: "sidebar_collapsed_by_default",
        label: "Collapse sidebar by default",
        kind: "boolean",
      },
      {
        key: "modern_landscape_posters_enabled",
        label: "Landscape posters",
        kind: "boolean",
      },
      {
        key: "modern_hero_full_screen_backdrop",
        label: "Full-screen hero backdrop",
        kind: "boolean",
      },
    ],
  },
  {
    title: "Home rows",
    fields: [
      { key: "hero_section_enabled", label: "Show hero row", kind: "boolean" },
      {
        key: "search_discover_enabled",
        label: "Show Search & Discover row",
        kind: "boolean",
      },
      {
        key: "hero_catalog_key",
        label: "Single hero catalog key",
        kind: "string",
        hint: "Legacy single-catalog hero. Use the keys field for multi-source.",
      },
      {
        key: "hero_catalog_keys",
        label: "Hero catalog keys (Gson JSON)",
        kind: "string",
        hint: "Raw Gson-serialized list. Edit on the TV unless you know the format.",
      },
      {
        key: "home_catalog_order_keys",
        label: "Home catalog order (Gson JSON)",
        kind: "string",
        hint: "Catalog ordering. Reorder via the TV settings UI.",
      },
      {
        key: "disabled_home_catalog_keys",
        label: "Disabled home catalogs (Gson JSON)",
        kind: "string",
      },
    ],
  },
  {
    title: "Posters",
    fields: [
      {
        key: "poster_card_width_dp",
        label: "Poster width (dp)",
        kind: "int",
        min: 60,
        max: 360,
      },
      {
        key: "poster_card_height_dp",
        label: "Poster height (dp)",
        kind: "int",
        min: 80,
        max: 480,
      },
      {
        key: "poster_card_corner_radius_dp",
        label: "Poster corner radius (dp)",
        kind: "int",
        min: 0,
        max: 64,
      },
      { key: "poster_labels_enabled", label: "Poster labels", kind: "boolean" },
      {
        key: "catalog_addon_name_enabled",
        label: "Show addon name on catalogs",
        kind: "boolean",
      },
      {
        key: "catalog_type_suffix_enabled",
        label: "Show type suffix on catalogs",
        kind: "boolean",
      },
      {
        key: "blur_unwatched_episodes",
        label: "Blur unwatched episodes",
        kind: "boolean",
      },
      {
        key: "blur_continue_watching_next_up",
        label: "Blur Continue Watching next up",
        kind: "boolean",
      },
    ],
  },
  {
    title: "Focused poster",
    fields: [
      {
        key: "focused_poster_backdrop_expand_enabled",
        label: "Expand backdrop on focus",
        kind: "boolean",
      },
      {
        key: "focused_poster_backdrop_expand_delay_seconds",
        label: "Expand delay (s)",
        kind: "int",
        min: 0,
        max: 30,
      },
      {
        key: "focused_poster_backdrop_trailer_enabled",
        label: "Play trailer on focus",
        kind: "boolean",
      },
      {
        key: "focused_poster_backdrop_trailer_muted",
        label: "Trailer muted",
        kind: "boolean",
      },
      {
        key: "focused_poster_backdrop_trailer_playback_target",
        label: "Trailer playback target",
        kind: "select",
        options: ["HERO_MEDIA", "EXPANDED_CARD"],
        hint: "HERO_MEDIA = full hero backdrop; EXPANDED_CARD = inside the focused card.",
      },
    ],
  },
  {
    title: "Detail page",
    fields: [
      {
        key: "detail_page_trailer_button_enabled",
        label: "Trailer button on detail",
        kind: "boolean",
      },
      {
        key: "prefer_external_meta_addon_detail",
        label: "Prefer external meta addon",
        kind: "boolean",
      },
      {
        key: "hide_unreleased_content",
        label: "Hide unreleased content",
        kind: "boolean",
      },
      {
        key: "show_full_release_date",
        label: "Show full release date",
        kind: "boolean",
      },
    ],
  },
];

export default function LayoutSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<LayoutSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  const validation = layoutSettingsSchema.safeParse(values);

  const update = <K extends keyof LayoutSettings>(key: K, value: LayoutSettings[K]) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "layout_settings",
        decodedValues: values,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/settings/layout`,
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
      {SECTIONS.map((section) => (
        <section
          key={section.title}
          className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5"
        >
          <h2 className="mb-3 text-lg font-medium">{section.title}</h2>
          <div className="space-y-3">
            {section.fields.map((spec) => (
              <FieldRow
                key={spec.key}
                spec={spec}
                value={values[spec.key]}
                onChange={(v) => update(spec.key, v as LayoutSettings[typeof spec.key])}
              />
            ))}
          </div>
        </section>
      ))}

      {!validation.success && (
        <div className="rounded-2xl border border-rose-500/40 bg-rose-500/10 p-4 text-sm text-rose-200">
          Invalid values:
          <ul className="ml-4 mt-1 list-disc text-xs">
            {validation.error.issues.slice(0, 6).map((i, n) => (
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
        onDiscard={() => {
          setValues(initial);
          setState({ kind: "idle" });
        }}
        disabled={!validation.success}
      />
    </div>
  );
}
