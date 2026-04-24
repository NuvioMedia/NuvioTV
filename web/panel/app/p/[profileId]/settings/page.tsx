import { getSettingsSnapshot } from "@/lib/data/settings";

interface Props {
  params: Promise<{ profileId: string }>;
}

function fmt(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "boolean") return value ? "on" : "off";
  if (Array.isArray(value)) return value.length === 0 ? "(none)" : value.join(", ");
  if (typeof value === "string") return value.length === 0 ? "(empty)" : value;
  return String(value);
}

interface FieldRow {
  key: string;
  label: string;
}

function FieldGrid({
  values,
  fields,
}: {
  values: Record<string, unknown> | undefined;
  fields: FieldRow[];
}) {
  if (!values) {
    return <div className="text-sm text-slate-500">Not set on this profile.</div>;
  }
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
      {fields.map((f) => (
        <div key={f.key} className="flex items-baseline justify-between gap-4 border-b border-slate-700/30 pb-1">
          <dt className="text-slate-400">{f.label}</dt>
          <dd className="text-right font-mono text-xs text-slate-200">{fmt(values[f.key])}</dd>
        </div>
      ))}
    </dl>
  );
}

export default async function SettingsPage({ params }: Props) {
  const { profileId } = await params;
  const snap = await getSettingsSnapshot(Number.parseInt(profileId, 10));

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Settings</h1>
        <p className="text-sm text-slate-400">
          Read-only view of theme, layout, playback, and trailer preferences for this profile.
        </p>
      </header>

      {snap.unknownFeatureKeys.length > 0 && (
        <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-100">
          <strong className="font-semibold">Unknown feature keys present:</strong>{" "}
          {snap.unknownFeatureKeys.map((k) => (
            <code key={k} className="mr-2 font-mono">
              {k}
            </code>
          ))}
          <p className="mt-1 text-xs text-amber-200/70">
            These were synced by the TV but are not modeled in the panel. Update
            <code className="mx-1 font-mono">lib/settings/schemas.ts</code>
            and re-deploy to surface them.
          </p>
        </div>
      )}

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Theme</h2>
        <FieldGrid
          values={snap.features.theme_settings}
          fields={[
            { key: "selected_theme", label: "Theme" },
            { key: "selected_font", label: "Font" },
          ]}
        />
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Layout</h2>
        <FieldGrid
          values={snap.features.layout_settings}
          fields={[
            { key: "selected_layout", label: "Layout" },
            { key: "hero_section_enabled", label: "Hero section" },
            { key: "search_discover_enabled", label: "Search/Discover" },
            { key: "modern_sidebar_enabled", label: "Modern sidebar" },
            { key: "modern_landscape_posters_enabled", label: "Landscape posters" },
            { key: "poster_labels_enabled", label: "Poster labels" },
            { key: "catalog_addon_name_enabled", label: "Show addon name" },
            { key: "catalog_type_suffix_enabled", label: "Show type suffix" },
            { key: "hide_unreleased_content", label: "Hide unreleased" },
            { key: "show_full_release_date", label: "Full release date" },
            { key: "poster_card_width_dp", label: "Poster width (dp)" },
            { key: "poster_card_height_dp", label: "Poster height (dp)" },
            { key: "poster_card_corner_radius_dp", label: "Poster corner (dp)" },
            { key: "blur_unwatched_episodes", label: "Blur unwatched eps" },
            { key: "blur_continue_watching_next_up", label: "Blur next up" },
            { key: "focused_poster_backdrop_expand_enabled", label: "Focus expand" },
            { key: "focused_poster_backdrop_trailer_enabled", label: "Focus trailer" },
            { key: "focused_poster_backdrop_trailer_muted", label: "Focus trailer muted" },
          ]}
        />
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Trailers</h2>
        <FieldGrid
          values={snap.features.trailer_settings}
          fields={[
            { key: "trailer_enabled", label: "Trailers" },
            { key: "trailer_delay_seconds", label: "Delay (s)" },
          ]}
        />
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Playback</h2>
        <FieldGrid
          values={snap.features.player_settings}
          fields={[
            { key: "player_preference", label: "Player" },
            { key: "internal_player_engine", label: "Engine" },
            { key: "auto_switch_internal_player_on_error", label: "Auto-switch on error" },
            { key: "preferred_audio_language", label: "Audio language" },
            { key: "secondary_preferred_audio_language", label: "Secondary audio" },
            { key: "subtitle_preferred_language", label: "Subtitle language" },
            { key: "subtitle_secondary_language", label: "Secondary subtitle" },
            { key: "subtitle_size", label: "Subtitle size %" },
            { key: "subtitle_vertical_offset", label: "Subtitle offset %" },
            { key: "subtitle_bold", label: "Subtitle bold" },
            { key: "subtitle_outline_enabled", label: "Subtitle outline" },
            { key: "use_libass", label: "libass" },
            { key: "decoder_priority", label: "Decoder priority" },
            { key: "tunneling_enabled", label: "Tunneling" },
            { key: "skip_silence", label: "Skip silence" },
            { key: "loading_overlay_enabled", label: "Loading overlay" },
            { key: "pause_overlay_enabled", label: "Pause overlay" },
            { key: "osd_clock_enabled", label: "OSD clock" },
            { key: "skip_intro_enabled", label: "Skip intro" },
            { key: "frame_rate_matching_mode", label: "Frame rate match" },
            { key: "resolution_matching_enabled", label: "Resolution match" },
            { key: "stream_auto_play_mode", label: "Auto-play" },
            { key: "stream_auto_play_next_episode_enabled", label: "Auto-play next ep" },
            { key: "stream_auto_play_timeout_seconds", label: "Auto-play timeout (s)" },
            { key: "min_buffer_ms", label: "Min buffer (ms)" },
            { key: "max_buffer_ms", label: "Max buffer (ms)" },
          ]}
        />
      </section>

      <section className="rounded-2xl border border-slate-700/50 bg-slate-800/40 p-5">
        <h2 className="mb-3 text-lg font-medium">Trakt preferences</h2>
        <FieldGrid
          values={snap.features.trakt_settings}
          fields={[
            { key: "watch_progress_source", label: "Progress source" },
            { key: "continue_watching_days_cap", label: "Continue watching cap (days)" },
            { key: "show_unaired_next_up", label: "Show unaired next up" },
            { key: "show_meta_comments", label: "Show meta comments" },
          ]}
        />
      </section>
    </div>
  );
}
