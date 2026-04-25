"use client";

import { useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { saveBlobFeature } from "@/lib/actions/blob";
import {
  playerSettingsSchema,
  type PlayerSettings,
} from "@/lib/settings/schemas";
import { SaveBar, type SaveState } from "./SaveBar";
import { FieldRow, type FieldSpec } from "./FieldRow";
import { useUnsavedWarning } from "./useUnsavedWarning";

interface Props {
  profileId: number;
  initial: PlayerSettings;
  expectedUpdatedAt: string | null;
}

type Spec = FieldSpec<keyof PlayerSettings & string>;

const SECTIONS: { title: string; fields: Spec[] }[] = [
  {
    title: "Player engine",
    fields: [
      {
        key: "player_preference",
        label: "Preferred player",
        kind: "select",
        options: ["INTERNAL", "EXTERNAL", "ASK_EVERY_TIME"],
      },
      {
        key: "internal_player_engine",
        label: "Internal engine",
        kind: "select",
        options: ["EXOPLAYER", "MVP_PLAYER"],
        hint: "MVP_PLAYER is the mpv-based engine (libmpv).",
      },
      {
        key: "auto_switch_internal_player_on_error",
        label: "Auto-switch on error",
        kind: "boolean",
      },
      {
        key: "decoder_priority",
        label: "Decoder priority",
        kind: "int",
        hint: "0 = prefer software, 1+ = prefer hardware",
        min: 0,
        max: 5,
      },
      { key: "tunneling_enabled", label: "Video tunneling", kind: "boolean" },
      {
        key: "mpv_hardware_decode_mode",
        label: "MPV hwdec",
        kind: "select",
        options: [
          "AUTO_SAFE",
          "HARDWARE_COPY",
          "HARDWARE_DIRECT",
          "LEGACY_DIRECT_COPY",
          "DISABLED",
        ],
      },
      { key: "map_dv7_to_hevc", label: "Map Dolby Vision Profile 7 → HEVC", kind: "boolean" },
      { key: "frame_rate_matching", label: "Frame-rate matching", kind: "boolean" },
      {
        key: "frame_rate_matching_mode",
        label: "FRM mode",
        kind: "select",
        options: ["OFF", "START", "START_STOP"],
      },
      { key: "resolution_matching_enabled", label: "Resolution matching", kind: "boolean" },
      {
        key: "resize_mode",
        label: "Resize mode",
        kind: "int",
        hint: "0=fit, 1=fixed-width, 2=fixed-height, 3=fill, 4=zoom",
        min: 0,
        max: 4,
      },
    ],
  },
  {
    title: "Audio",
    fields: [
      { key: "skip_silence", label: "Skip silence", kind: "boolean" },
      {
        key: "audio_amplification_db",
        label: "Amplification (dB)",
        kind: "int",
        min: 0,
        max: 30,
      },
      {
        key: "persist_audio_amplification",
        label: "Persist amplification",
        kind: "boolean",
      },
      {
        key: "preferred_audio_language",
        label: "Preferred audio language",
        kind: "string",
        hint: "ISO 639-2 code (eng, jpn, deu, …)",
      },
      {
        key: "secondary_preferred_audio_language",
        label: "Secondary audio language",
        kind: "string",
      },
    ],
  },
  {
    title: "Subtitles",
    fields: [
      { key: "use_libass", label: "Use libass for ASS/SSA", kind: "boolean" },
      {
        key: "libass_render_type",
        label: "libass render type",
        kind: "select",
        options: [
          "OVERLAY_OPEN_GL",
          "OVERLAY_CANVAS",
          "EFFECTS_OPEN_GL",
          "EFFECTS_CANVAS",
          "CUES",
        ],
        hint: "OVERLAY_OPEN_GL is the recommended default (HDR-aware, GPU).",
      },
      {
        key: "subtitle_organization_mode",
        label: "Sub list grouping",
        kind: "select",
        options: ["NONE", "BY_LANGUAGE", "BY_ADDON"],
      },
      {
        key: "addon_subtitle_startup_mode",
        label: "Addon sub startup",
        kind: "select",
        options: ["FAST_STARTUP", "PREFERRED_ONLY", "ALL_SUBTITLES"],
      },
      {
        key: "subtitle_preferred_language",
        label: "Preferred sub language",
        kind: "string",
      },
      {
        key: "subtitle_secondary_language",
        label: "Secondary sub language",
        kind: "string",
      },
      { key: "subtitle_size", label: "Size (%)", kind: "int", min: 50, max: 200 },
      {
        key: "subtitle_vertical_offset",
        label: "Vertical offset",
        kind: "int",
        min: -50,
        max: 50,
      },
      { key: "subtitle_bold", label: "Bold", kind: "boolean" },
      {
        key: "subtitle_text_color",
        label: "Text colour (ARGB int)",
        kind: "int",
      },
      {
        key: "subtitle_background_color",
        label: "Background colour (ARGB int)",
        kind: "int",
      },
      { key: "subtitle_outline_enabled", label: "Outline", kind: "boolean" },
      {
        key: "subtitle_outline_color",
        label: "Outline colour (ARGB int)",
        kind: "int",
      },
      {
        key: "subtitle_outline_width",
        label: "Outline width",
        kind: "int",
        min: 0,
        max: 10,
      },
    ],
  },
  {
    title: "Overlays",
    fields: [
      { key: "loading_overlay_enabled", label: "Loading overlay", kind: "boolean" },
      { key: "show_player_loading_status", label: "Loading status text", kind: "boolean" },
      { key: "pause_overlay_enabled", label: "Pause overlay", kind: "boolean" },
      { key: "osd_clock_enabled", label: "OSD clock", kind: "boolean" },
      { key: "skip_intro_enabled", label: "Skip intro button", kind: "boolean" },
    ],
  },
  {
    title: "Auto-play & Next episode",
    fields: [
      {
        key: "stream_auto_play_mode",
        label: "Auto-play mode",
        kind: "select",
        options: ["MANUAL", "FIRST_STREAM", "REGEX_MATCH"],
      },
      {
        key: "stream_auto_play_source",
        label: "Source filter",
        kind: "select",
        options: ["ALL_SOURCES", "INSTALLED_ADDONS_ONLY", "ENABLED_PLUGINS_ONLY"],
      },
      {
        key: "stream_auto_play_selected_addons",
        label: "Selected addons (one per line)",
        kind: "stringSet",
      },
      {
        key: "stream_auto_play_selected_plugins",
        label: "Selected plugins (one per line)",
        kind: "stringSet",
      },
      {
        key: "stream_auto_play_regex",
        label: "Auto-play regex",
        kind: "string",
        hint: "Java regex matched against stream title",
      },
      {
        key: "stream_auto_play_next_episode_enabled",
        label: "Auto-play next episode",
        kind: "boolean",
      },
      {
        key: "stream_auto_play_prefer_bingegroup_next_episode",
        label: "Prefer addon binge group for next episode",
        kind: "boolean",
      },
      {
        key: "stream_auto_play_timeout_seconds",
        label: "Auto-play timeout (s)",
        kind: "int",
        min: 0,
        max: 60,
      },
      {
        key: "next_episode_threshold_mode",
        label: "Next-episode trigger mode",
        kind: "select",
        options: ["PERCENTAGE", "MINUTES_BEFORE_END"],
      },
      {
        key: "next_episode_threshold_percent_v2",
        label: "Trigger at % watched (v2)",
        kind: "float",
        hint: "Float (0–100). Used when mode is PERCENT.",
        min: 0,
        max: 100,
        step: 0.5,
      },
      {
        key: "next_episode_threshold_minutes_before_end_v2",
        label: "Or N minutes before end (v2)",
        kind: "float",
        min: 0,
        max: 30,
        step: 0.5,
      },
      {
        key: "next_episode_threshold_percent",
        label: "Legacy: % threshold (int)",
        kind: "int",
        hint: "Read by older builds; encoded as int.",
        min: 0,
        max: 100,
      },
      {
        key: "next_episode_threshold_minutes_before_end",
        label: "Legacy: minutes-before-end (int)",
        kind: "int",
        min: 0,
        max: 30,
      },
      {
        key: "stream_reuse_last_link_enabled",
        label: "Reuse last successful stream link",
        kind: "boolean",
      },
      {
        key: "stream_reuse_last_link_cache_hours",
        label: "Reuse cache (hours)",
        kind: "int",
        min: 0,
        max: 168,
      },
    ],
  },
  {
    title: "Buffer (advanced)",
    fields: [
      { key: "min_buffer_ms", label: "Min buffer (ms)", kind: "int", min: 0 },
      { key: "max_buffer_ms", label: "Max buffer (ms)", kind: "int", min: 0 },
      { key: "buffer_for_playback_ms", label: "For playback (ms)", kind: "int", min: 0 },
      {
        key: "buffer_for_playback_after_rebuffer_ms",
        label: "After rebuffer (ms)",
        kind: "int",
        min: 0,
      },
      {
        key: "target_buffer_size_mb",
        label: "Target size (MB)",
        kind: "int",
        min: 0,
      },
      {
        key: "back_buffer_duration_ms",
        label: "Back buffer (ms)",
        kind: "int",
        min: 0,
      },
      {
        key: "retain_back_buffer_from_keyframe",
        label: "Retain back buffer from keyframe",
        kind: "boolean",
      },
    ],
  },
];

export default function PlayerSettingsForm({
  profileId,
  initial,
  expectedUpdatedAt,
}: Props) {
  const router = useRouter();
  const [values, setValues] = useState<PlayerSettings>(initial);
  const [state, setState] = useState<SaveState>({ kind: "idle" });
  const [isPending, startTransition] = useTransition();

  const initialSig = useMemo(() => JSON.stringify(initial), [initial]);
  const currentSig = useMemo(() => JSON.stringify(values), [values]);
  const dirty = initialSig !== currentSig;
  useUnsavedWarning(dirty);

  // Preserve the migration flag — it's set by the TV and dropping it could
  // re-trigger the load-control alignment migration.
  const valuesToSave = useMemo<PlayerSettings>(
    () => ({
      ...values,
      migration_load_control_defaults_aligned_done:
        initial.migration_load_control_defaults_aligned_done,
    }),
    [values, initial.migration_load_control_defaults_aligned_done]
  );

  const validation = playerSettingsSchema.safeParse(valuesToSave);

  const update = <K extends keyof PlayerSettings>(key: K, value: PlayerSettings[K]) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = () => {
    setState({ kind: "saving" });
    startTransition(async () => {
      const result = await saveBlobFeature({
        profileId,
        featureKey: "player_settings",
        decodedValues: valuesToSave,
        expectedUpdatedAt,
        revalidatePath: `/p/${profileId}/settings/player`,
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
                key={String(spec.key)}
                spec={spec}
                value={values[spec.key]}
                onChange={(v) => update(spec.key, v as PlayerSettings[typeof spec.key])}
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

