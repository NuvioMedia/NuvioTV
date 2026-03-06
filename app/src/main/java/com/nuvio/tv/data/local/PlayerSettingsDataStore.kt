package com.nuvio.tv.data.local

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Available subtitle languages
 */
data class SubtitleLanguage(
    val code: String,
    val name: String
)

const val SUBTITLE_LANGUAGE_FORCED = "forced"

val AVAILABLE_SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("en", "English"),
    SubtitleLanguage("es", "Spanish"),
    SubtitleLanguage("fr", "French"),
    SubtitleLanguage("de", "German"),
    SubtitleLanguage("it", "Italian"),
    SubtitleLanguage("pt", "Portuguese (Portugal)"),
    SubtitleLanguage("pt-br", "Portuguese (Brazil)"),
    SubtitleLanguage("ru", "Russian"),
    SubtitleLanguage("ja", "Japanese"),
    SubtitleLanguage("ko", "Korean"),
    SubtitleLanguage("zh", "Chinese"),
    SubtitleLanguage("ar", "Arabic"),
    SubtitleLanguage("hi", "Hindi"),
    SubtitleLanguage("tr", "Turkish"),
    SubtitleLanguage("pl", "Polish"),
    SubtitleLanguage("nl", "Dutch"),
    SubtitleLanguage("sv", "Swedish"),
    SubtitleLanguage("da", "Danish"),
    SubtitleLanguage("no", "Norwegian"),
    SubtitleLanguage("fi", "Finnish"),
    SubtitleLanguage("th", "Thai"),
    SubtitleLanguage("vi", "Vietnamese"),
    SubtitleLanguage("id", "Indonesian"),
    SubtitleLanguage("ms", "Malay"),
    SubtitleLanguage("he", "Hebrew"),
    SubtitleLanguage("el", "Greek"),
    SubtitleLanguage("cs", "Czech"),
    SubtitleLanguage("hu", "Hungarian"),
    SubtitleLanguage("ro", "Romanian"),
    SubtitleLanguage("uk", "Ukrainian"),
    SubtitleLanguage("bg", "Bulgarian"),
    SubtitleLanguage("hr", "Croatian"),
    SubtitleLanguage("sk", "Slovak"),
    SubtitleLanguage("sl", "Slovenian"),
    SubtitleLanguage("sr", "Serbian"),
    SubtitleLanguage("ta", "Tamil"),
    SubtitleLanguage("te", "Telugu"),
    SubtitleLanguage("ml", "Malayalam"),
    SubtitleLanguage("bn", "Bengali"),
    SubtitleLanguage("mr", "Marathi"),
    SubtitleLanguage("gu", "Gujarati"),
    SubtitleLanguage("kn", "Kannada"),
    SubtitleLanguage("pa", "Punjabi")
)

/**
 * Data class representing subtitle style settings
 */
data class SubtitleStyleSettings(
    val preferredLanguage: String = "en",
    val secondaryPreferredLanguage: String? = null,
    val size: Int = 120, // Percentage (50-200)
    val verticalOffset: Int = 5, // Percentage from bottom (-20 to 50)
    val bold: Boolean = false,
    val textColor: Int = Color.White.toArgb(),
    val backgroundColor: Int = Color.Transparent.toArgb(),
    val outlineEnabled: Boolean = true,
    val outlineColor: Int = Color.Black.toArgb(),
    val outlineWidth: Int = 2 // 1-5
)

/**
 * Data class representing buffer settings
 */
data class BufferSettings(
    val minBufferMs: Int = DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = DEFAULT_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    val bufferForPlaybackAfterRebufferMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val targetBufferSizeMb: Int = DEFAULT_TARGET_BUFFER_SIZE_MB,
    val backBufferDurationMs: Int = DEFAULT_BACK_BUFFER_DURATION_MS,
    val retainBackBufferFromKeyframe: Boolean = false
) {
    companion object {
        const val DEFAULT_MIN_BUFFER_MS = 20_000
        const val DEFAULT_MAX_BUFFER_MS = 50_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 3_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        const val DEFAULT_TARGET_BUFFER_SIZE_MB: Int = 100
        const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
    }
}

/**
 * Available audio language options
 */
object AudioLanguageOption {
    const val DEFAULT = "default"  // Use media file default
    const val DEVICE = "device"    // Use device locale
}

/**
 * Data class representing player settings
 */
data class PlayerSettings(
    val playerPreference: PlayerPreference = PlayerPreference.INTERNAL,
    val useLibass: Boolean = false,
    val libassRenderType: LibassRenderType = LibassRenderType.OVERLAY_OPEN_GL,
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    val bufferSettings: BufferSettings = BufferSettings(),
    // Audio settings
    val decoderPriority: Int = 1, // EXTENSION_RENDERER_MODE_ON (0=off, 1=on, 2=prefer)
    val tunnelingEnabled: Boolean = false,
    val skipSilence: Boolean = false,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val loadingOverlayEnabled: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    // Force Dolby Vision streams to HEVC/HDR10 fallback (uses Media3 codec remap path)
    val mapDV7ToHevc: Boolean = false,
    // Experimental: try native DV7 -> DV8.1 conversion before HEVC fallback.
    val experimentalDv7ToDv81Enabled: Boolean = false,
    // Experimental: enable Fire OS audio-path quirks (DTS core fallback, capability routing, etc).
    val experimentalDtsIecPassthroughEnabled: Boolean = false,
    // Experimental: allow DV5 streams to use the compatibility DV8.1 remap path.
    val experimentalDv5ToDv81Enabled: Boolean = false,
    // Experimental: preserve mapping metadata when converting DV7 -> DV8.1.
    val experimentalDv7ToDv81PreserveMappingEnabled: Boolean = false,
    // Display settings
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.OFF,
    val resolutionMatchingEnabled: Boolean = false,
    // Stream selection settings
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlaySelectedPlugins: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean = true,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val subtitleOrganizationMode: SubtitleOrganizationMode = SubtitleOrganizationMode.NONE,
    // Networking
    val vodCacheSizeMode: VodCacheSizeMode = DEFAULT_VOD_CACHE_SIZE_MODE,
    val vodCacheSizeMb: Int = DEFAULT_VOD_CACHE_SIZE_MB,
    val useParallelConnections: Boolean = DEFAULT_USE_PARALLEL_CONNECTIONS,
    val parallelConnectionCount: Int = DEFAULT_PARALLEL_CONNECTION_COUNT,
    val parallelChunkSizeMb: Int = DEFAULT_PARALLEL_CHUNK_SIZE_MB,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    val enableBufferLogs: Boolean = false
) {
    companion object {
        const val DEFAULT_VOD_CACHE_SIZE_MB = 500
        const val MIN_VOD_CACHE_SIZE_MB = 100
        const val MAX_VOD_CACHE_SIZE_MB = 65_536
        val DEFAULT_VOD_CACHE_SIZE_MODE: VodCacheSizeMode = VodCacheSizeMode.AUTO
        const val DEFAULT_USE_PARALLEL_CONNECTIONS = false
        const val DEFAULT_PARALLEL_CONNECTION_COUNT = 2
        const val DEFAULT_PARALLEL_CHUNK_SIZE_MB = 16
        const val MIN_PARALLEL_CONNECTION_COUNT = 2
        const val MAX_PARALLEL_CONNECTION_COUNT = 4
        const val MIN_PARALLEL_CHUNK_SIZE_MB = 8
        const val MAX_PARALLEL_CHUNK_SIZE_MB = 128
    }
}

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY
}

enum class VodCacheSizeMode {
    AUTO,
    MANUAL
}

enum class FrameRateMatchingMode {
    OFF,
    START,
    START_STOP
}

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END
}

enum class SubtitleOrganizationMode {
    NONE,
    BY_LANGUAGE,
    BY_ADDON
}

enum class AddonSubtitleStartupMode {
    FAST_STARTUP,
    PREFERRED_ONLY,
    ALL_SUBTITLES
}

enum class PlayerPreference {
    INTERNAL,
    EXTERNAL,
    ASK_EVERY_TIME
}

/**
 * Enum representing the different libass render types
 * Maps to io.github.peerless2012.ass.media.type.AssRenderType
 */
enum class LibassRenderType {
    CUES,              // Standard SubtitleView rendering (no animation support)
    EFFECTS_CANVAS,    // Effect-based Canvas rendering (supports animations)
    EFFECTS_OPEN_GL,   // Effect-based OpenGL rendering (supports animations, faster)
    OVERLAY_CANVAS,    // Overlay Canvas rendering (supports HDR)
    OVERLAY_OPEN_GL    // Overlay OpenGL rendering (supports HDR, recommended)
}

@Singleton
class PlayerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "player_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Player preference key
    private val playerPreferenceKey = stringPreferencesKey("player_preference")

    // Libass settings keys
    private val useLibassKey = booleanPreferencesKey("use_libass")
    private val libassRenderTypeKey = stringPreferencesKey("libass_render_type")

    // Audio settings keys
    private val decoderPriorityKey = intPreferencesKey("decoder_priority")
    private val tunnelingEnabledKey = booleanPreferencesKey("tunneling_enabled")
    private val skipSilenceKey = booleanPreferencesKey("skip_silence")
    private val preferredAudioLanguageKey = stringPreferencesKey("preferred_audio_language")
    private val secondaryPreferredAudioLanguageKey = stringPreferencesKey("secondary_preferred_audio_language")
    private val loadingOverlayEnabledKey = booleanPreferencesKey("loading_overlay_enabled")
    private val pauseOverlayEnabledKey = booleanPreferencesKey("pause_overlay_enabled")
    private val osdClockEnabledKey = booleanPreferencesKey("osd_clock_enabled")
    private val skipIntroEnabledKey = booleanPreferencesKey("skip_intro_enabled")
    private val mapDV7ToHevcKey = booleanPreferencesKey("map_dv7_to_hevc")
    private val experimentalDv7ToDv81EnabledKey =
        booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
    private val experimentalDtsIecPassthroughEnabledKey =
        booleanPreferencesKey("experimental_dts_iec_passthrough_enabled")
    private val experimentalDv5ToDv81EnabledKey =
        booleanPreferencesKey("experimental_dv5_to_dv81_enabled")
    private val experimentalDv7ToDv81PreserveMappingEnabledKey =
        booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
    private val frameRateMatchingKey = booleanPreferencesKey("frame_rate_matching")
    private val frameRateMatchingModeKey = stringPreferencesKey("frame_rate_matching_mode")
    private val resolutionMatchingEnabledKey = booleanPreferencesKey("resolution_matching_enabled")
    private val streamAutoPlayModeKey = stringPreferencesKey("stream_auto_play_mode")
    private val streamAutoPlaySourceKey = stringPreferencesKey("stream_auto_play_source")
    private val streamAutoPlaySelectedAddonsKey = stringSetPreferencesKey("stream_auto_play_selected_addons")
    private val streamAutoPlaySelectedPluginsKey = stringSetPreferencesKey("stream_auto_play_selected_plugins")
    private val streamAutoPlayRegexKey = stringPreferencesKey("stream_auto_play_regex")
    private val streamAutoPlayNextEpisodeEnabledKey = booleanPreferencesKey("stream_auto_play_next_episode_enabled")
    private val streamAutoPlayPreferBingeGroupForNextEpisodeKey =
        booleanPreferencesKey("stream_auto_play_prefer_binge_group_for_next_episode")
    private val nextEpisodeThresholdModeKey = stringPreferencesKey("next_episode_threshold_mode")
    private val nextEpisodeThresholdPercentLegacyKey = intPreferencesKey("next_episode_threshold_percent")
    private val nextEpisodeThresholdMinutesBeforeEndLegacyKey = intPreferencesKey("next_episode_threshold_minutes_before_end")
    private val nextEpisodeThresholdPercentKey = floatPreferencesKey("next_episode_threshold_percent_v2")
    private val nextEpisodeThresholdMinutesBeforeEndKey = floatPreferencesKey("next_episode_threshold_minutes_before_end_v2")
    private val streamReuseLastLinkEnabledKey = booleanPreferencesKey("stream_reuse_last_link_enabled")
    private val streamReuseLastLinkCacheHoursKey = intPreferencesKey("stream_reuse_last_link_cache_hours")
    private val subtitleOrganizationModeKey = stringPreferencesKey("subtitle_organization_mode")
    private val vodCacheSizeModeKey = stringPreferencesKey("vod_cache_size_mode")
    private val vodCacheSizeMbKey = intPreferencesKey("vod_cache_size_mb")
    private val useParallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
    private val parallelConnectionCountKey = intPreferencesKey("parallel_connection_count")
    private val parallelChunkSizeMbKey = intPreferencesKey("parallel_chunk_size_mb")
    private val addonSubtitleStartupModeKey = stringPreferencesKey("addon_subtitle_startup_mode")
    private val enableBufferLogsKey = booleanPreferencesKey("enable_buffer_logs")

    // Subtitle style settings keys
    private val subtitlePreferredLanguageKey = stringPreferencesKey("subtitle_preferred_language")
    private val subtitleSecondaryLanguageKey = stringPreferencesKey("subtitle_secondary_language")
    private val subtitleSizeKey = intPreferencesKey("subtitle_size")
    private val subtitleVerticalOffsetKey = intPreferencesKey("subtitle_vertical_offset")
    private val subtitleBoldKey = booleanPreferencesKey("subtitle_bold")
    private val subtitleTextColorKey = intPreferencesKey("subtitle_text_color")
    private val subtitleBackgroundColorKey = intPreferencesKey("subtitle_background_color")
    private val subtitleOutlineEnabledKey = booleanPreferencesKey("subtitle_outline_enabled")
    private val subtitleOutlineColorKey = intPreferencesKey("subtitle_outline_color")
    private val subtitleOutlineWidthKey = intPreferencesKey("subtitle_outline_width")

    // Buffer settings keys
    private val minBufferMsKey = intPreferencesKey("min_buffer_ms")
    private val maxBufferMsKey = intPreferencesKey("max_buffer_ms")
    private val bufferForPlaybackMsKey = intPreferencesKey("buffer_for_playback_ms")
    private val bufferForPlaybackAfterRebufferMsKey = intPreferencesKey("buffer_for_playback_after_rebuffer_ms")
    private val targetBufferSizeMbKey = intPreferencesKey("target_buffer_size_mb")
    private val backBufferDurationMsKey = intPreferencesKey("back_buffer_duration_ms")
    private val retainBackBufferFromKeyframeKey = booleanPreferencesKey("retain_back_buffer_from_keyframe")

    private val migrationLoadControlDefaultsAlignedDoneKey = booleanPreferencesKey("migration_load_control_defaults_aligned_done")
    private val migrationLoadControlDefaultsRetunedDoneKey =
        booleanPreferencesKey("migration_load_control_defaults_retuned_done")
    private val migrationLoadControlMinBufferRetunedDoneKey =
        booleanPreferencesKey("migration_load_control_min_buffer_retuned_done")

    init {
        ioScope.launch {
            store().edit { prefs ->
                val loadControlMigrated = prefs[migrationLoadControlDefaultsAlignedDoneKey] ?: false
                if (!loadControlMigrated) {
                    val currentMin = prefs[minBufferMsKey]
                    val currentMax = prefs[maxBufferMsKey]

                    val legacyDefaultsDetected = (currentMin == null && currentMax == null) ||
                        (currentMin == 15_000 && currentMax == 25_000)

                    if (legacyDefaultsDetected) {
                        prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                        prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                    }

                    prefs[migrationLoadControlDefaultsAlignedDoneKey] = true
                }

                val loadControlRetuned = prefs[migrationLoadControlDefaultsRetunedDoneKey] ?: false
                if (!loadControlRetuned) {
                    val currentMin = prefs[minBufferMsKey]
                    val currentMax = prefs[maxBufferMsKey]
                    val currentPlayback = prefs[bufferForPlaybackMsKey]
                    val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMsKey]
                    val currentTargetBuffer = prefs[targetBufferSizeMbKey]

                    val previousDefaultsDetected =
                        currentMin == 50_000 &&
                            currentMax == 50_000 &&
                            currentPlayback == 2_500 &&
                            currentPlaybackAfterRebuffer == 5_000 &&
                            currentTargetBuffer == 0

                    val olderDefaultsDetected =
                        currentMin == 15_000 &&
                            currentMax == 25_000

                    if (previousDefaultsDetected || olderDefaultsDetected) {
                        prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                        prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                        prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                        prefs[bufferForPlaybackAfterRebufferMsKey] =
                            BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                        prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
                    }

                    prefs[migrationLoadControlDefaultsRetunedDoneKey] = true
                }

                val minBufferRetuned = prefs[migrationLoadControlMinBufferRetunedDoneKey] ?: false
                if (!minBufferRetuned) {
                    val currentMin = prefs[minBufferMsKey]
                    val currentMax = prefs[maxBufferMsKey]
                    val currentPlayback = prefs[bufferForPlaybackMsKey]
                    val currentPlaybackAfterRebuffer = prefs[bufferForPlaybackAfterRebufferMsKey]
                    val currentTargetBuffer = prefs[targetBufferSizeMbKey]
                    val currentBackBuffer = prefs[backBufferDurationMsKey]
                    val currentRetainBackBuffer = prefs[retainBackBufferFromKeyframeKey]

                    val previousRetunedDefaultsDetected =
                        currentMin == 50_000 &&
                            currentMax == 50_000 &&
                            currentPlayback == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS &&
                            currentPlaybackAfterRebuffer ==
                            BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS &&
                            currentTargetBuffer == BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB &&
                            (currentBackBuffer == null ||
                                currentBackBuffer == BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS) &&
                            (currentRetainBackBuffer == null || !currentRetainBackBuffer)

                    if (previousRetunedDefaultsDetected) {
                        prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                    }

                    prefs[migrationLoadControlMinBufferRetunedDoneKey] = true
                }

                val min = prefs[minBufferMsKey]
                val max = prefs[maxBufferMsKey]
                if (min != null && max != null && max < min) {
                    prefs[maxBufferMsKey] = min
                }

                prefs[vodCacheSizeMbKey]?.let { current ->
                    val normalized = current.coerceIn(
                        PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                        PlayerSettings.MAX_VOD_CACHE_SIZE_MB
                    )
                    if (normalized != current) {
                        prefs[vodCacheSizeMbKey] = normalized
                    }
                }
                prefs[vodCacheSizeModeKey]?.let { raw ->
                    val normalized = runCatching { VodCacheSizeMode.valueOf(raw) }
                        .getOrDefault(PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE)
                        .name
                    if (normalized != raw) {
                        prefs[vodCacheSizeModeKey] = normalized
                    }
                }

                val preferredAudioLanguage = prefs[preferredAudioLanguageKey]
                if (preferredAudioLanguage != null) {
                    val normalizedPreferredAudioLanguage =
                        normalizeSelectableLanguageCode(preferredAudioLanguage)
                    if (normalizedPreferredAudioLanguage != preferredAudioLanguage) {
                        prefs[preferredAudioLanguageKey] = normalizedPreferredAudioLanguage
                    }
                }

                val secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
                if (secondaryPreferredAudioLanguage != null) {
                    val normalizedSecondaryPreferredAudioLanguage =
                        normalizeSecondaryAudioLanguageCode(secondaryPreferredAudioLanguage)
                    if (normalizedSecondaryPreferredAudioLanguage != secondaryPreferredAudioLanguage) {
                        if (normalizedSecondaryPreferredAudioLanguage != null) {
                            prefs[secondaryPreferredAudioLanguageKey] = normalizedSecondaryPreferredAudioLanguage
                        } else {
                            prefs.remove(secondaryPreferredAudioLanguageKey)
                        }
                    }
                }

                val preferredSubtitleLanguage = prefs[subtitlePreferredLanguageKey]
                if (preferredSubtitleLanguage != null) {
                    val normalizedPreferredSubtitleLanguage =
                        normalizeSelectableLanguageCode(preferredSubtitleLanguage)
                    if (normalizedPreferredSubtitleLanguage != preferredSubtitleLanguage) {
                        prefs[subtitlePreferredLanguageKey] = normalizedPreferredSubtitleLanguage
                    }
                }

                val secondarySubtitleLanguage = prefs[subtitleSecondaryLanguageKey]
                if (secondarySubtitleLanguage != null) {
                    val normalizedSecondarySubtitleLanguage =
                        normalizeSelectableLanguageCode(secondarySubtitleLanguage)
                    if (normalizedSecondarySubtitleLanguage != secondarySubtitleLanguage) {
                        prefs[subtitleSecondaryLanguageKey] = normalizedSecondarySubtitleLanguage
                    }
                }
            }
        }
    }

    /**
     * Flow of current player settings
     */
    val playerSettings: Flow<PlayerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            PlayerSettings(
                playerPreference = prefs[playerPreferenceKey]?.let {
                    runCatching { PlayerPreference.valueOf(it) }.getOrDefault(PlayerPreference.INTERNAL)
                } ?: PlayerPreference.INTERNAL,
                useLibass = prefs[useLibassKey] ?: false,
                libassRenderType = prefs[libassRenderTypeKey]?.let {
                    try { LibassRenderType.valueOf(it) } catch (e: Exception) { LibassRenderType.OVERLAY_OPEN_GL }
                } ?: LibassRenderType.OVERLAY_OPEN_GL,
                decoderPriority = prefs[decoderPriorityKey] ?: 1,
                tunnelingEnabled = prefs[tunnelingEnabledKey] ?: false,
                skipSilence = prefs[skipSilenceKey] ?: false,
                preferredAudioLanguage = normalizeSelectableLanguageCode(
                    prefs[preferredAudioLanguageKey] ?: AudioLanguageOption.DEVICE
                ),
                secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
                    ?.let(::normalizeSecondaryAudioLanguageCode),
                loadingOverlayEnabled = prefs[loadingOverlayEnabledKey] ?: true,
                pauseOverlayEnabled = prefs[pauseOverlayEnabledKey] ?: true,
                osdClockEnabled = prefs[osdClockEnabledKey] ?: true,
                skipIntroEnabled = prefs[skipIntroEnabledKey] ?: true,
                mapDV7ToHevc = prefs[mapDV7ToHevcKey] ?: false,
                experimentalDv7ToDv81Enabled = prefs[experimentalDv7ToDv81EnabledKey] ?: false,
                experimentalDtsIecPassthroughEnabled =
                    prefs[experimentalDtsIecPassthroughEnabledKey] ?: false,
                experimentalDv5ToDv81Enabled = prefs[experimentalDv5ToDv81EnabledKey] ?: false,
                experimentalDv7ToDv81PreserveMappingEnabled =
                    prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] ?: false,
                frameRateMatchingMode = prefs[frameRateMatchingModeKey]?.let {
                    runCatching { FrameRateMatchingMode.valueOf(it) }.getOrNull()
                } ?: if (prefs[frameRateMatchingKey] == true) {
                    FrameRateMatchingMode.START_STOP
                } else {
                    FrameRateMatchingMode.OFF
                },
                resolutionMatchingEnabled = prefs[resolutionMatchingEnabledKey] ?: false,
                streamAutoPlayMode = prefs[streamAutoPlayModeKey]?.let {
                    runCatching { StreamAutoPlayMode.valueOf(it) }.getOrDefault(StreamAutoPlayMode.MANUAL)
                } ?: StreamAutoPlayMode.MANUAL,
                streamAutoPlaySource = prefs[streamAutoPlaySourceKey]?.let {
                    runCatching { StreamAutoPlaySource.valueOf(it) }.getOrDefault(StreamAutoPlaySource.ALL_SOURCES)
                } ?: StreamAutoPlaySource.ALL_SOURCES,
                streamAutoPlaySelectedAddons = prefs[streamAutoPlaySelectedAddonsKey] ?: emptySet(),
                streamAutoPlaySelectedPlugins = prefs[streamAutoPlaySelectedPluginsKey] ?: emptySet(),
                streamAutoPlayRegex = prefs[streamAutoPlayRegexKey] ?: "",
                streamAutoPlayNextEpisodeEnabled = prefs[streamAutoPlayNextEpisodeEnabledKey] ?: false,
                streamAutoPlayPreferBingeGroupForNextEpisode =
                    prefs[streamAutoPlayPreferBingeGroupForNextEpisodeKey] ?: true,
                nextEpisodeThresholdMode = prefs[nextEpisodeThresholdModeKey]?.let {
                    runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrDefault(NextEpisodeThresholdMode.PERCENTAGE)
                } ?: NextEpisodeThresholdMode.PERCENTAGE,
                nextEpisodeThresholdPercent = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdPercentKey]
                        ?: prefs[nextEpisodeThresholdPercentLegacyKey]?.toFloat()
                        ?: 99f,
                    min = 97f,
                    max = 99.5f
                ),
                nextEpisodeThresholdMinutesBeforeEnd = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdMinutesBeforeEndKey]
                        ?: prefs[nextEpisodeThresholdMinutesBeforeEndLegacyKey]?.toFloat()
                        ?: 2f,
                    min = 1f,
                    max = 3.5f
                ),
                streamReuseLastLinkEnabled = prefs[streamReuseLastLinkEnabledKey] ?: false,
                streamReuseLastLinkCacheHours = (prefs[streamReuseLastLinkCacheHoursKey] ?: 24).coerceIn(1, 168),
                subtitleOrganizationMode = parseSubtitleOrganizationMode(prefs[subtitleOrganizationModeKey]),
                vodCacheSizeMode = prefs[vodCacheSizeModeKey]?.let {
                    runCatching { VodCacheSizeMode.valueOf(it) }
                        .getOrDefault(PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE)
                } ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE,
                vodCacheSizeMb = (prefs[vodCacheSizeMbKey] ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB)
                    .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB),
                useParallelConnections = prefs[useParallelConnectionsKey] ?: PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS,
                parallelConnectionCount = (prefs[parallelConnectionCountKey]
                    ?: PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT).coerceIn(
                    PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                ),
                parallelChunkSizeMb = (prefs[parallelChunkSizeMbKey]
                    ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB).coerceIn(
                    PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                    PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                ),
                addonSubtitleStartupMode = parseAddonSubtitleStartupMode(prefs[addonSubtitleStartupModeKey]),
                enableBufferLogs = prefs[enableBufferLogsKey] ?: false,
                subtitleStyle = SubtitleStyleSettings(
                    preferredLanguage = normalizeSelectableLanguageCode(
                        prefs[subtitlePreferredLanguageKey] ?: "en"
                    ),
                    secondaryPreferredLanguage = prefs[subtitleSecondaryLanguageKey]
                        ?.let(::normalizeSelectableLanguageCode),
                    size = prefs[subtitleSizeKey] ?: 100,
                    verticalOffset = prefs[subtitleVerticalOffsetKey] ?: 5,
                    bold = prefs[subtitleBoldKey] ?: false,
                    textColor = prefs[subtitleTextColorKey] ?: Color.White.toArgb(),
                    backgroundColor = prefs[subtitleBackgroundColorKey] ?: Color.Transparent.toArgb(),
                    outlineEnabled = prefs[subtitleOutlineEnabledKey] ?: true,
                    outlineColor = prefs[subtitleOutlineColorKey] ?: Color.Black.toArgb(),
                    outlineWidth = prefs[subtitleOutlineWidthKey] ?: 2
                ),
                bufferSettings = BufferSettings(
                    minBufferMs = prefs[minBufferMsKey] ?: BufferSettings.DEFAULT_MIN_BUFFER_MS,
                    maxBufferMs = prefs[maxBufferMsKey] ?: BufferSettings.DEFAULT_MAX_BUFFER_MS,
                    bufferForPlaybackMs = prefs[bufferForPlaybackMsKey]
                        ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    bufferForPlaybackAfterRebufferMs = prefs[bufferForPlaybackAfterRebufferMsKey]
                        ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    targetBufferSizeMb = prefs[targetBufferSizeMbKey]?.coerceAtLeast(0)
                        ?: BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB,
                    backBufferDurationMs = prefs[backBufferDurationMsKey]
                        ?: BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS,
                    retainBackBufferFromKeyframe = prefs[retainBackBufferFromKeyframeKey] ?: false
                )
            )
        }
    }

    /**
     * Flow for just the libass toggle
     */
    val useLibass: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[useLibassKey] ?: false
        }
    }

    /**
     * Flow for the libass render type
     */
    val libassRenderType: Flow<LibassRenderType> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[libassRenderTypeKey]?.let {
                try { LibassRenderType.valueOf(it) } catch (e: Exception) { LibassRenderType.OVERLAY_OPEN_GL }
            } ?: LibassRenderType.OVERLAY_OPEN_GL
        }
    }

    // Player preference setter

    suspend fun setPlayerPreference(preference: PlayerPreference) {
        store().edit { prefs ->
            prefs[playerPreferenceKey] = preference.name
        }
    }

    // Audio settings setters

    suspend fun setDecoderPriority(priority: Int) {
        store().edit { prefs ->
            prefs[decoderPriorityKey] = priority.coerceIn(0, 2)
        }
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[tunnelingEnabledKey] = enabled
        }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        store().edit { prefs ->
            prefs[skipSilenceKey] = enabled
        }
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        store().edit { prefs ->
            prefs[preferredAudioLanguageKey] = normalizeSelectableLanguageCode(
                language.ifBlank { AudioLanguageOption.DEVICE }
            )
        }
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeSecondaryAudioLanguageCode)
            if (normalizedLanguage != null) {
                prefs[secondaryPreferredAudioLanguageKey] = normalizedLanguage
            } else {
                prefs.remove(secondaryPreferredAudioLanguageKey)
            }
        }
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[pauseOverlayEnabledKey] = enabled
        }
    }

    suspend fun setOsdClockEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[osdClockEnabledKey] = enabled
        }
    }

    suspend fun setSkipIntroEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[skipIntroEnabledKey] = enabled
        }
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[loadingOverlayEnabledKey] = enabled
        }
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        store().edit { prefs ->
            prefs[frameRateMatchingModeKey] = mode.name
            prefs[frameRateMatchingKey] = mode != FrameRateMatchingMode.OFF
        }
    }

    suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[resolutionMatchingEnabledKey] = enabled
        }
    }

    suspend fun setFrameRateMatching(enabled: Boolean) {
        setFrameRateMatchingMode(
            if (enabled) FrameRateMatchingMode.START_STOP else FrameRateMatchingMode.OFF
        )
    }

    suspend fun setEnableBufferLogs(enabled: Boolean) {
        store().edit { prefs ->
            prefs[enableBufferLogsKey] = enabled
        }
    }

    suspend fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        store().edit { prefs ->
            prefs[streamAutoPlayModeKey] = mode.name
        }
    }

    suspend fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        store().edit { prefs ->
            prefs[streamAutoPlaySourceKey] = source.name
        }
    }

    suspend fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        store().edit { prefs ->
            prefs[streamAutoPlaySelectedAddonsKey] = addons
        }
    }

    suspend fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        store().edit { prefs ->
            prefs[streamAutoPlaySelectedPluginsKey] = plugins
        }
    }

    suspend fun setStreamAutoPlayRegex(regex: String) {
        store().edit { prefs ->
            prefs[streamAutoPlayRegexKey] = regex.trim()
        }
    }

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamAutoPlayNextEpisodeEnabledKey] = enabled
        }
    }

    suspend fun setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamAutoPlayPreferBingeGroupForNextEpisodeKey] = enabled
        }
    }

    suspend fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdModeKey] = mode.name
        }
    }

    suspend fun setNextEpisodeThresholdPercent(percent: Float) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdPercentKey] = normalizeHalfStep(
                value = percent,
                min = 97f,
                max = 99.5f
            )
        }
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdMinutesBeforeEndKey] = normalizeHalfStep(
                value = minutes,
                min = 1f,
                max = 3.5f
            )
        }
    }

    private fun normalizeHalfStep(value: Float, min: Float, max: Float): Float {
        val clamped = value.coerceIn(min, max)
        return (clamped * 2f).roundToInt() / 2f
    }

    suspend fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamReuseLastLinkEnabledKey] = enabled
        }
    }

    suspend fun setStreamReuseLastLinkCacheHours(hours: Int) {
        store().edit { prefs ->
            prefs[streamReuseLastLinkCacheHoursKey] = hours.coerceIn(1, 168)
        }
    }

    suspend fun setSubtitleOrganizationMode(mode: SubtitleOrganizationMode) {
        store().edit { prefs ->
            prefs[subtitleOrganizationModeKey] = mode.name
        }
    }

    suspend fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        store().edit { prefs ->
            prefs[addonSubtitleStartupModeKey] = mode.name
        }
    }

    private fun parseSubtitleOrganizationMode(value: String?): SubtitleOrganizationMode {
        return when (value) {
            null, "NONE" -> SubtitleOrganizationMode.NONE
            "BY_LANGUAGE" -> SubtitleOrganizationMode.BY_LANGUAGE
            "BY_ADDON" -> SubtitleOrganizationMode.BY_ADDON
            else -> SubtitleOrganizationMode.NONE
        }
    }

    private fun parseAddonSubtitleStartupMode(value: String?): AddonSubtitleStartupMode {
        return when (value) {
            null, "ALL_SUBTITLES" -> AddonSubtitleStartupMode.ALL_SUBTITLES
            "PREFERRED_ONLY" -> AddonSubtitleStartupMode.PREFERRED_ONLY
            "FAST_STARTUP" -> AddonSubtitleStartupMode.FAST_STARTUP
            else -> AddonSubtitleStartupMode.ALL_SUBTITLES
        }
    }

    private fun normalizeSelectableLanguageCode(language: String): String {
        val code = language.trim().lowercase()
        return when (code) {
            "pt-br", "pt_br", "br", "pob" -> "pt-br"
            "pt-pt", "pt_pt", "por" -> "pt"
            "forced", "force", "forc" -> SUBTITLE_LANGUAGE_FORCED
            else -> code
        }
    }

    private fun normalizeSecondaryAudioLanguageCode(language: String): String? {
        val normalized = normalizeSelectableLanguageCode(language)
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            else -> normalized
        }
    }

    suspend fun setMapDV7ToHevc(enabled: Boolean) {
        store().edit { prefs ->
            prefs[mapDV7ToHevcKey] = enabled
        }
    }

    suspend fun setExperimentalDv7ToDv81Enabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv7ToDv81EnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDtsIecPassthroughEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDtsIecPassthroughEnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv5ToDv81Enabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv5ToDv81EnabledKey] = enabled
        }
    }

    suspend fun setExperimentalDv7ToDv81PreserveMappingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[experimentalDv7ToDv81PreserveMappingEnabledKey] = enabled
        }
    }

    /**
     * Set whether to use libass for ASS/SSA subtitle rendering
     */
    suspend fun setUseLibass(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useLibassKey] = enabled
        }
    }

    /**
     * Set the libass render type
     */
    suspend fun setLibassRenderType(renderType: LibassRenderType) {
        store().edit { prefs ->
            prefs[libassRenderTypeKey] = renderType.name
        }
    }

    // Subtitle style settings functions

    suspend fun setSubtitlePreferredLanguage(language: String) {
        store().edit { prefs ->
            prefs[subtitlePreferredLanguageKey] = normalizeSelectableLanguageCode(
                language.ifBlank { "en" }
            )
        }
    }

    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeSelectableLanguageCode)
            if (normalizedLanguage != null) {
                prefs[subtitleSecondaryLanguageKey] = normalizedLanguage
            } else {
                prefs.remove(subtitleSecondaryLanguageKey)
            }
        }
    }

    suspend fun setSubtitleSize(size: Int) {
        store().edit { prefs ->
            prefs[subtitleSizeKey] = size.coerceIn(50, 200)
        }
    }

    suspend fun setSubtitleVerticalOffset(offset: Int) {
        store().edit { prefs ->
            prefs[subtitleVerticalOffsetKey] = offset.coerceIn(-20, 50)
        }
    }

    suspend fun setSubtitleBold(bold: Boolean) {
        store().edit { prefs ->
            prefs[subtitleBoldKey] = bold
        }
    }

    suspend fun setSubtitleTextColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleTextColorKey] = color
        }
    }

    suspend fun setSubtitleBackgroundColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleBackgroundColorKey] = color
        }
    }

    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[subtitleOutlineEnabledKey] = enabled
        }
    }

    suspend fun setSubtitleOutlineColor(color: Int) {
        store().edit { prefs ->
            prefs[subtitleOutlineColorKey] = color
        }
    }

    suspend fun setSubtitleOutlineWidth(width: Int) {
        store().edit { prefs ->
            prefs[subtitleOutlineWidthKey] = width.coerceIn(1, 5)
        }
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        store().edit { prefs ->
            val newMin = ms.coerceIn(5_000, 120_000)
            prefs[minBufferMsKey] = newMin
            val currentMax = prefs[maxBufferMsKey] ?: BufferSettings.DEFAULT_MAX_BUFFER_MS
            if (currentMax < newMin) {
                prefs[maxBufferMsKey] = newMin
            }
        }
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        store().edit { prefs ->
            val currentMin = prefs[minBufferMsKey] ?: BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = ms.coerceIn(currentMin, 120_000)
        }
    }

    suspend fun setBufferForPlaybackMs(ms: Int) {
        store().edit { prefs ->
            prefs[bufferForPlaybackMsKey] = ms.coerceIn(1_000, 30_000)
        }
    }

    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) {
        store().edit { prefs ->
            prefs[bufferForPlaybackAfterRebufferMsKey] = ms.coerceIn(1_000, 60_000)
        }
    }

    suspend fun setBufferTargetSizeMb(mb: Int) {
        store().edit { prefs ->
            prefs[targetBufferSizeMbKey] = mb.coerceAtLeast(0)
        }
    }

    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        store().edit { prefs ->
            prefs[backBufferDurationMsKey] = ms.coerceIn(0, 120_000)
        }
    }

    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) {
        store().edit { prefs ->
            prefs[retainBackBufferFromKeyframeKey] = retain
        }
    }

    suspend fun resetBufferSettingsToDefaults() {
        store().edit { prefs ->
            prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
            prefs[bufferForPlaybackAfterRebufferMsKey] =
                BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            prefs[retainBackBufferFromKeyframeKey] = false
        }
    }

    suspend fun resetNetworkSettingsToDefaults() {
        store().edit { prefs ->
            prefs[vodCacheSizeModeKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE.name
            prefs[vodCacheSizeMbKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB
            prefs[useParallelConnectionsKey] = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
            prefs[parallelConnectionCountKey] = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
            prefs[parallelChunkSizeMbKey] = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
        }
    }

    suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) {
        store().edit { prefs ->
            prefs[vodCacheSizeModeKey] = mode.name
        }
    }

    suspend fun setVodCacheSizeMb(mb: Int) {
        store().edit { prefs ->
            prefs[vodCacheSizeMbKey] = mb.coerceIn(
                PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                PlayerSettings.MAX_VOD_CACHE_SIZE_MB
            )
        }
    }

    suspend fun setUseParallelConnections(enabled: Boolean) {
        store().edit { prefs ->
            prefs[useParallelConnectionsKey] = enabled
        }
    }

    suspend fun setParallelConnectionCount(count: Int) {
        store().edit { prefs ->
            prefs[parallelConnectionCountKey] = count.coerceIn(
                PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
            )
        }
    }

    suspend fun setParallelChunkSizeMb(mb: Int) {
        store().edit { prefs ->
            prefs[parallelChunkSizeMbKey] = mb.coerceIn(
                PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
            )
        }
    }

    suspend fun updateMemorySettings(
        targetBufferSizeMb: Int? = null,
        useParallelConnections: Boolean? = null,
        parallelConnectionCount: Int? = null,
        parallelChunkSizeMb: Int? = null
    ) {
        store().edit { prefs ->
            targetBufferSizeMb?.let { prefs[targetBufferSizeMbKey] = it.coerceAtLeast(0) }
            useParallelConnections?.let { prefs[useParallelConnectionsKey] = it }
            parallelConnectionCount?.let {
                prefs[parallelConnectionCountKey] = it.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                )
            }
            parallelChunkSizeMb?.let {
                prefs[parallelChunkSizeMbKey] = it.coerceIn(
                    PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB,
                    PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB
                )
            }
        }
    }
}
