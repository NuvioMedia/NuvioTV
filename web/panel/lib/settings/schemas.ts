import { z } from "zod";
import {
  encodeBoolean,
  encodeFloat,
  encodeInt,
  encodeString,
  encodeStringSet,
  type EncodedFeature,
  type EncodedValue,
} from "./envelope";

// Each schema mirrors a *DataStore.kt under app/src/main/java/com/omnio/tv/data/local/.
// The schema validates the DECODED values that UI forms produce; a parallel `*Encoders`
// map picks the right envelope type (int vs long vs float vs double) for each key.
//
// CRITICAL: the envelope type must match what the TV writes with intPreferencesKey /
// longPreferencesKey / floatPreferencesKey / booleanPreferencesKey / etc. Encoding
// an int as "long" makes the TV-side read silently fall back to the default.
// When adding a key on the Android side, add it here in the same PR (see CONTRIBUTING.md).

// ---------------------------------------------------------------------------
// theme_settings
// ---------------------------------------------------------------------------
export const themeSettingsSchema = z
  .object({
    selected_theme: z.string().optional(),
    selected_font: z.string().optional(),
  })
  .strict();

export type ThemeSettings = z.infer<typeof themeSettingsSchema>;

export const themeSettingsEncoders: Record<keyof ThemeSettings, (v: never) => EncodedValue> = {
  selected_theme: (v: string) => encodeString(v),
  selected_font: (v: string) => encodeString(v),
} as never;

// ---------------------------------------------------------------------------
// layout_settings
// ---------------------------------------------------------------------------
export const layoutSettingsSchema = z
  .object({
    selected_layout: z.string().optional(),
    has_chosen_layout: z.boolean().optional(),
    hero_catalog_key: z.string().optional(),
    hero_catalog_keys: z.string().optional(),
    home_catalog_order_keys: z.string().optional(),
    disabled_home_catalog_keys: z.string().optional(),
    sidebar_collapsed_by_default: z.boolean().optional(),
    modern_sidebar_enabled: z.boolean().optional(),
    glass_sidepanel_enabled: z.boolean().optional(),
    modern_sidebar_blur_enabled: z.boolean().optional(),
    modern_landscape_posters_enabled: z.boolean().optional(),
    hero_section_enabled: z.boolean().optional(),
    search_discover_enabled: z.boolean().optional(),
    poster_labels_enabled: z.boolean().optional(),
    catalog_addon_name_enabled: z.boolean().optional(),
    catalog_type_suffix_enabled: z.boolean().optional(),
    focused_poster_backdrop_expand_enabled: z.boolean().optional(),
    focused_poster_backdrop_expand_delay_seconds: z.number().int().optional(),
    focused_poster_backdrop_trailer_enabled: z.boolean().optional(),
    focused_poster_backdrop_trailer_muted: z.boolean().optional(),
    focused_poster_backdrop_trailer_playback_target: z.string().optional(),
    poster_card_width_dp: z.number().int().optional(),
    poster_card_height_dp: z.number().int().optional(),
    poster_card_corner_radius_dp: z.number().int().optional(),
    blur_unwatched_episodes: z.boolean().optional(),
    blur_continue_watching_next_up: z.boolean().optional(),
    detail_page_trailer_button_enabled: z.boolean().optional(),
    prefer_external_meta_addon_detail: z.boolean().optional(),
    modern_hero_full_screen_backdrop: z.boolean().optional(),
    hide_unreleased_content: z.boolean().optional(),
    show_full_release_date: z.boolean().optional(),
  })
  .strict();

export type LayoutSettings = z.infer<typeof layoutSettingsSchema>;

export const layoutSettingsEncoders = {
  selected_layout: (v: string) => encodeString(v),
  has_chosen_layout: (v: boolean) => encodeBoolean(v),
  hero_catalog_key: (v: string) => encodeString(v),
  hero_catalog_keys: (v: string) => encodeString(v),
  home_catalog_order_keys: (v: string) => encodeString(v),
  disabled_home_catalog_keys: (v: string) => encodeString(v),
  sidebar_collapsed_by_default: (v: boolean) => encodeBoolean(v),
  modern_sidebar_enabled: (v: boolean) => encodeBoolean(v),
  glass_sidepanel_enabled: (v: boolean) => encodeBoolean(v),
  modern_sidebar_blur_enabled: (v: boolean) => encodeBoolean(v),
  modern_landscape_posters_enabled: (v: boolean) => encodeBoolean(v),
  hero_section_enabled: (v: boolean) => encodeBoolean(v),
  search_discover_enabled: (v: boolean) => encodeBoolean(v),
  poster_labels_enabled: (v: boolean) => encodeBoolean(v),
  catalog_addon_name_enabled: (v: boolean) => encodeBoolean(v),
  catalog_type_suffix_enabled: (v: boolean) => encodeBoolean(v),
  focused_poster_backdrop_expand_enabled: (v: boolean) => encodeBoolean(v),
  focused_poster_backdrop_expand_delay_seconds: (v: number) => encodeInt(v),
  focused_poster_backdrop_trailer_enabled: (v: boolean) => encodeBoolean(v),
  focused_poster_backdrop_trailer_muted: (v: boolean) => encodeBoolean(v),
  focused_poster_backdrop_trailer_playback_target: (v: string) => encodeString(v),
  poster_card_width_dp: (v: number) => encodeInt(v),
  poster_card_height_dp: (v: number) => encodeInt(v),
  poster_card_corner_radius_dp: (v: number) => encodeInt(v),
  blur_unwatched_episodes: (v: boolean) => encodeBoolean(v),
  blur_continue_watching_next_up: (v: boolean) => encodeBoolean(v),
  detail_page_trailer_button_enabled: (v: boolean) => encodeBoolean(v),
  prefer_external_meta_addon_detail: (v: boolean) => encodeBoolean(v),
  modern_hero_full_screen_backdrop: (v: boolean) => encodeBoolean(v),
  hide_unreleased_content: (v: boolean) => encodeBoolean(v),
  show_full_release_date: (v: boolean) => encodeBoolean(v),
} as const;

// ---------------------------------------------------------------------------
// player_settings
// ---------------------------------------------------------------------------
export const playerSettingsSchema = z
  .object({
    player_preference: z.string().optional(),
    internal_player_engine: z.string().optional(),
    auto_switch_internal_player_on_error: z.boolean().optional(),
    use_libass: z.boolean().optional(),
    libass_render_type: z.string().optional(),
    decoder_priority: z.number().int().optional(),
    tunneling_enabled: z.boolean().optional(),
    skip_silence: z.boolean().optional(),
    audio_amplification_db: z.number().int().optional(),
    persist_audio_amplification: z.boolean().optional(),
    preferred_audio_language: z.string().optional(),
    secondary_preferred_audio_language: z.string().optional(),
    loading_overlay_enabled: z.boolean().optional(),
    show_player_loading_status: z.boolean().optional(),
    pause_overlay_enabled: z.boolean().optional(),
    osd_clock_enabled: z.boolean().optional(),
    skip_intro_enabled: z.boolean().optional(),
    map_dv7_to_hevc: z.boolean().optional(),
    mpv_hardware_decode_mode: z.string().optional(),
    frame_rate_matching: z.boolean().optional(),
    frame_rate_matching_mode: z.string().optional(),
    resolution_matching_enabled: z.boolean().optional(),
    stream_auto_play_mode: z.string().optional(),
    stream_auto_play_source: z.string().optional(),
    stream_auto_play_selected_addons: z.array(z.string()).optional(),
    stream_auto_play_selected_plugins: z.array(z.string()).optional(),
    stream_auto_play_regex: z.string().optional(),
    stream_auto_play_next_episode_enabled: z.boolean().optional(),
    stream_auto_play_prefer_bingegroup_next_episode: z.boolean().optional(),
    stream_auto_play_timeout_seconds: z.number().int().optional(),
    next_episode_threshold_mode: z.string().optional(),
    next_episode_threshold_percent: z.number().int().optional(),
    next_episode_threshold_minutes_before_end: z.number().int().optional(),
    next_episode_threshold_percent_v2: z.number().optional(),
    next_episode_threshold_minutes_before_end_v2: z.number().optional(),
    stream_reuse_last_link_enabled: z.boolean().optional(),
    stream_reuse_last_link_cache_hours: z.number().int().optional(),
    subtitle_organization_mode: z.string().optional(),
    addon_subtitle_startup_mode: z.string().optional(),
    resize_mode: z.number().int().optional(),
    subtitle_preferred_language: z.string().optional(),
    subtitle_secondary_language: z.string().optional(),
    subtitle_size: z.number().int().optional(),
    subtitle_vertical_offset: z.number().int().optional(),
    subtitle_bold: z.boolean().optional(),
    subtitle_text_color: z.number().int().optional(),
    subtitle_background_color: z.number().int().optional(),
    subtitle_outline_enabled: z.boolean().optional(),
    subtitle_outline_color: z.number().int().optional(),
    subtitle_outline_width: z.number().int().optional(),
    min_buffer_ms: z.number().int().optional(),
    max_buffer_ms: z.number().int().optional(),
    buffer_for_playback_ms: z.number().int().optional(),
    buffer_for_playback_after_rebuffer_ms: z.number().int().optional(),
    target_buffer_size_mb: z.number().int().optional(),
    back_buffer_duration_ms: z.number().int().optional(),
    retain_back_buffer_from_keyframe: z.boolean().optional(),
    migration_load_control_defaults_aligned_done: z.boolean().optional(),
  })
  .strict();

export type PlayerSettings = z.infer<typeof playerSettingsSchema>;

export const playerSettingsEncoders = {
  player_preference: (v: string) => encodeString(v),
  internal_player_engine: (v: string) => encodeString(v),
  auto_switch_internal_player_on_error: (v: boolean) => encodeBoolean(v),
  use_libass: (v: boolean) => encodeBoolean(v),
  libass_render_type: (v: string) => encodeString(v),
  decoder_priority: (v: number) => encodeInt(v),
  tunneling_enabled: (v: boolean) => encodeBoolean(v),
  skip_silence: (v: boolean) => encodeBoolean(v),
  audio_amplification_db: (v: number) => encodeInt(v),
  persist_audio_amplification: (v: boolean) => encodeBoolean(v),
  preferred_audio_language: (v: string) => encodeString(v),
  secondary_preferred_audio_language: (v: string) => encodeString(v),
  loading_overlay_enabled: (v: boolean) => encodeBoolean(v),
  show_player_loading_status: (v: boolean) => encodeBoolean(v),
  pause_overlay_enabled: (v: boolean) => encodeBoolean(v),
  osd_clock_enabled: (v: boolean) => encodeBoolean(v),
  skip_intro_enabled: (v: boolean) => encodeBoolean(v),
  map_dv7_to_hevc: (v: boolean) => encodeBoolean(v),
  mpv_hardware_decode_mode: (v: string) => encodeString(v),
  frame_rate_matching: (v: boolean) => encodeBoolean(v),
  frame_rate_matching_mode: (v: string) => encodeString(v),
  resolution_matching_enabled: (v: boolean) => encodeBoolean(v),
  stream_auto_play_mode: (v: string) => encodeString(v),
  stream_auto_play_source: (v: string) => encodeString(v),
  stream_auto_play_selected_addons: (v: string[]) => encodeStringSet(v),
  stream_auto_play_selected_plugins: (v: string[]) => encodeStringSet(v),
  stream_auto_play_regex: (v: string) => encodeString(v),
  stream_auto_play_next_episode_enabled: (v: boolean) => encodeBoolean(v),
  stream_auto_play_prefer_bingegroup_next_episode: (v: boolean) => encodeBoolean(v),
  stream_auto_play_timeout_seconds: (v: number) => encodeInt(v),
  next_episode_threshold_mode: (v: string) => encodeString(v),
  // legacy keys — TV still reads these as fallback
  next_episode_threshold_percent: (v: number) => encodeInt(v),
  next_episode_threshold_minutes_before_end: (v: number) => encodeInt(v),
  // v2 keys — TV prefers these
  next_episode_threshold_percent_v2: (v: number) => encodeFloat(v),
  next_episode_threshold_minutes_before_end_v2: (v: number) => encodeFloat(v),
  stream_reuse_last_link_enabled: (v: boolean) => encodeBoolean(v),
  stream_reuse_last_link_cache_hours: (v: number) => encodeInt(v),
  subtitle_organization_mode: (v: string) => encodeString(v),
  addon_subtitle_startup_mode: (v: string) => encodeString(v),
  resize_mode: (v: number) => encodeInt(v),
  subtitle_preferred_language: (v: string) => encodeString(v),
  subtitle_secondary_language: (v: string) => encodeString(v),
  subtitle_size: (v: number) => encodeInt(v),
  subtitle_vertical_offset: (v: number) => encodeInt(v),
  subtitle_bold: (v: boolean) => encodeBoolean(v),
  subtitle_text_color: (v: number) => encodeInt(v),
  subtitle_background_color: (v: number) => encodeInt(v),
  subtitle_outline_enabled: (v: boolean) => encodeBoolean(v),
  subtitle_outline_color: (v: number) => encodeInt(v),
  subtitle_outline_width: (v: number) => encodeInt(v),
  min_buffer_ms: (v: number) => encodeInt(v),
  max_buffer_ms: (v: number) => encodeInt(v),
  buffer_for_playback_ms: (v: number) => encodeInt(v),
  buffer_for_playback_after_rebuffer_ms: (v: number) => encodeInt(v),
  target_buffer_size_mb: (v: number) => encodeInt(v),
  back_buffer_duration_ms: (v: number) => encodeInt(v),
  retain_back_buffer_from_keyframe: (v: boolean) => encodeBoolean(v),
  migration_load_control_defaults_aligned_done: (v: boolean) => encodeBoolean(v),
} as const;

// ---------------------------------------------------------------------------
// trailer_settings
// ---------------------------------------------------------------------------
export const trailerSettingsSchema = z
  .object({
    trailer_enabled: z.boolean().optional(),
    trailer_delay_seconds: z.number().int().optional(),
  })
  .strict();

export type TrailerSettings = z.infer<typeof trailerSettingsSchema>;

export const trailerSettingsEncoders = {
  trailer_enabled: (v: boolean) => encodeBoolean(v),
  trailer_delay_seconds: (v: number) => encodeInt(v),
} as const;

// ---------------------------------------------------------------------------
// tmdb_settings
// ---------------------------------------------------------------------------
export const tmdbSettingsSchema = z
  .object({
    tmdb_enabled: z.boolean().optional(),
    tmdb_modern_home_enabled: z.boolean().optional(),
    tmdb_enrich_continue_watching: z.boolean().optional(),
    tmdb_language: z.string().optional(),
    tmdb_use_artwork: z.boolean().optional(),
    tmdb_use_basic_info: z.boolean().optional(),
    tmdb_use_details: z.boolean().optional(),
    tmdb_use_release_dates: z.boolean().optional(),
    tmdb_use_credits: z.boolean().optional(),
    tmdb_use_productions: z.boolean().optional(),
    tmdb_use_networks: z.boolean().optional(),
    tmdb_use_episodes: z.boolean().optional(),
    tmdb_use_more_like_this: z.boolean().optional(),
    tmdb_use_collections: z.boolean().optional(),
  })
  .strict();

export type TmdbSettings = z.infer<typeof tmdbSettingsSchema>;

export const tmdbSettingsEncoders = {
  tmdb_enabled: (v: boolean) => encodeBoolean(v),
  tmdb_modern_home_enabled: (v: boolean) => encodeBoolean(v),
  tmdb_enrich_continue_watching: (v: boolean) => encodeBoolean(v),
  tmdb_language: (v: string) => encodeString(v),
  tmdb_use_artwork: (v: boolean) => encodeBoolean(v),
  tmdb_use_basic_info: (v: boolean) => encodeBoolean(v),
  tmdb_use_details: (v: boolean) => encodeBoolean(v),
  tmdb_use_release_dates: (v: boolean) => encodeBoolean(v),
  tmdb_use_credits: (v: boolean) => encodeBoolean(v),
  tmdb_use_productions: (v: boolean) => encodeBoolean(v),
  tmdb_use_networks: (v: boolean) => encodeBoolean(v),
  tmdb_use_episodes: (v: boolean) => encodeBoolean(v),
  tmdb_use_more_like_this: (v: boolean) => encodeBoolean(v),
  tmdb_use_collections: (v: boolean) => encodeBoolean(v),
} as const;

// ---------------------------------------------------------------------------
// mdblist_settings
// ---------------------------------------------------------------------------
export const mdblistSettingsSchema = z
  .object({
    mdblist_enabled: z.boolean().optional(),
    mdblist_api_key: z.string().optional(),
    mdblist_show_trakt: z.boolean().optional(),
    mdblist_show_imdb: z.boolean().optional(),
    mdblist_show_tmdb: z.boolean().optional(),
    mdblist_show_letterboxd: z.boolean().optional(),
    mdblist_show_tomatoes: z.boolean().optional(),
    mdblist_show_audience: z.boolean().optional(),
    mdblist_show_metacritic: z.boolean().optional(),
  })
  .strict();

export type MdblistSettings = z.infer<typeof mdblistSettingsSchema>;

export const mdblistSettingsEncoders = {
  mdblist_enabled: (v: boolean) => encodeBoolean(v),
  mdblist_api_key: (v: string) => encodeString(v),
  mdblist_show_trakt: (v: boolean) => encodeBoolean(v),
  mdblist_show_imdb: (v: boolean) => encodeBoolean(v),
  mdblist_show_tmdb: (v: boolean) => encodeBoolean(v),
  mdblist_show_letterboxd: (v: boolean) => encodeBoolean(v),
  mdblist_show_tomatoes: (v: boolean) => encodeBoolean(v),
  mdblist_show_audience: (v: boolean) => encodeBoolean(v),
  mdblist_show_metacritic: (v: boolean) => encodeBoolean(v),
} as const;

// ---------------------------------------------------------------------------
// animeskip_settings
// ---------------------------------------------------------------------------
export const animeskipSettingsSchema = z
  .object({
    animeskip_enabled: z.boolean().optional(),
    animeskip_client_id: z.string().optional(),
  })
  .strict();

export type AnimeskipSettings = z.infer<typeof animeskipSettingsSchema>;

export const animeskipSettingsEncoders = {
  animeskip_enabled: (v: boolean) => encodeBoolean(v),
  animeskip_client_id: (v: string) => encodeString(v),
} as const;

// ---------------------------------------------------------------------------
// trakt_settings
// ---------------------------------------------------------------------------
export const traktSettingsSchema = z
  .object({
    continue_watching_days_cap: z.number().int().optional(),
    dismissed_next_up_keys: z.array(z.string()).optional(),
    show_unaired_next_up: z.boolean().optional(),
    show_meta_comments: z.boolean().optional(),
    watch_progress_source: z.string().optional(),
  })
  .strict();

export type TraktSettings = z.infer<typeof traktSettingsSchema>;

export const traktSettingsEncoders = {
  continue_watching_days_cap: (v: number) => encodeInt(v),
  dismissed_next_up_keys: (v: string[]) => encodeStringSet(v),
  show_unaired_next_up: (v: boolean) => encodeBoolean(v),
  show_meta_comments: (v: boolean) => encodeBoolean(v),
  watch_progress_source: (v: string) => encodeString(v),
} as const;

// ---------------------------------------------------------------------------
// emby_credentials
// Note: emby_device_id is intentionally NOT in this schema — the TV excludes it
// from sync (ProfileSettingsSyncService.kt:89) because it's a per-device identifier.
// ---------------------------------------------------------------------------
export const embyCredentialsSchema = z
  .object({
    emby_server_url: z.string().optional(),
    emby_api_key: z.string().optional(),
    emby_user_id: z.string().optional(),
  })
  .strict();

export type EmbyCredentials = z.infer<typeof embyCredentialsSchema>;

export const embyCredentialsEncoders = {
  emby_server_url: (v: string) => encodeString(v),
  emby_api_key: (v: string) => encodeString(v),
  emby_user_id: (v: string) => encodeString(v),
} as const;

// ---------------------------------------------------------------------------
// track_preference
// Dynamic keys like `sub_type|<contentId>`, `audio_lang|<contentId>`. These are
// per-content playback prefs and the panel does NOT edit them — it preserves
// the raw envelope on round-trip and ignores the feature when surfacing UI.
// ---------------------------------------------------------------------------
export const TRACK_PREFERENCE_FEATURE = "track_preference";

// ---------------------------------------------------------------------------
// Feature registry + helpers
// ---------------------------------------------------------------------------
export const SYNCED_FEATURES = [
  "theme_settings",
  "layout_settings",
  "player_settings",
  "trailer_settings",
  "tmdb_settings",
  "mdblist_settings",
  "animeskip_settings",
  "track_preference",
  "trakt_settings",
  "emby_credentials",
] as const;

export type SyncedFeatureKey = (typeof SYNCED_FEATURES)[number];

type EncoderMap = Record<string, (v: never) => EncodedValue>;

export const FEATURE_SCHEMAS: Partial<Record<SyncedFeatureKey, z.ZodTypeAny>> = {
  theme_settings: themeSettingsSchema,
  layout_settings: layoutSettingsSchema,
  player_settings: playerSettingsSchema,
  trailer_settings: trailerSettingsSchema,
  tmdb_settings: tmdbSettingsSchema,
  mdblist_settings: mdblistSettingsSchema,
  animeskip_settings: animeskipSettingsSchema,
  trakt_settings: traktSettingsSchema,
  emby_credentials: embyCredentialsSchema,
};

export const FEATURE_ENCODERS: Partial<Record<SyncedFeatureKey, EncoderMap>> = {
  theme_settings: themeSettingsEncoders as EncoderMap,
  layout_settings: layoutSettingsEncoders as EncoderMap,
  player_settings: playerSettingsEncoders as EncoderMap,
  trailer_settings: trailerSettingsEncoders as EncoderMap,
  tmdb_settings: tmdbSettingsEncoders as EncoderMap,
  mdblist_settings: mdblistSettingsEncoders as EncoderMap,
  animeskip_settings: animeskipSettingsEncoders as EncoderMap,
  trakt_settings: traktSettingsEncoders as EncoderMap,
  emby_credentials: embyCredentialsEncoders as EncoderMap,
};

// Encodes a decoded feature object to the TV's envelope format using the
// registered encoder for each key. Keys absent from the encoder map are dropped
// with a warning — that usually signals a schema drift that needs fixing.
export function encodeFeature(
  featureKey: SyncedFeatureKey,
  decoded: Record<string, unknown>
): EncodedFeature {
  const encoders = FEATURE_ENCODERS[featureKey];
  if (!encoders) {
    throw new Error(`no encoder map registered for feature ${featureKey}`);
  }
  const out: EncodedFeature = {};
  for (const [key, value] of Object.entries(decoded)) {
    if (value === undefined) continue;
    const encoder = encoders[key];
    if (!encoder) {
      // Hard-fail: dropping silently hides schema drift between TV and web.
      throw new Error(`no encoder for ${featureKey}.${key}`);
    }
    out[key] = (encoder as (v: unknown) => EncodedValue)(value);
  }
  return out;
}
