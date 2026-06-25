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
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.ui.util.languageCodeToName
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/**
 * Available subtitle languages
 */
data class SubtitleLanguage(
    val code: String,
    val name: String
)

val SubtitleLanguage.displayName: String
    get() = languageCodeToName(code)

const val SUBTITLE_LANGUAGE_FORCED = "forced"

val AVAILABLE_SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("af", "Afrikaans"),
    SubtitleLanguage("sq", "Albanian"),
    SubtitleLanguage("am", "Amharic"),
    SubtitleLanguage("ar", "Arabic"),
    SubtitleLanguage("hy", "Armenian"),
    SubtitleLanguage("az", "Azerbaijani"),
    SubtitleLanguage("eu", "Basque"),
    SubtitleLanguage("be", "Belarusian"),
    SubtitleLanguage("bn", "Bengali"),
    SubtitleLanguage("bs", "Bosnian"),
    SubtitleLanguage("bg", "Bulgarian"),
    SubtitleLanguage("my", "Burmese"),
    SubtitleLanguage("ca", "Catalan"),
    SubtitleLanguage("zh", "Chinese"),
    SubtitleLanguage("zh-CN", "Chinese (Simplified)"),
    SubtitleLanguage("zh-TW", "Chinese (Traditional)"),
    SubtitleLanguage("hr", "Croatian"),
    SubtitleLanguage("cs", "Czech"),
    SubtitleLanguage("da", "Danish"),
    SubtitleLanguage("nl", "Dutch"),
    SubtitleLanguage("en", "English"),
    SubtitleLanguage("et", "Estonian"),
    SubtitleLanguage("tl", "Filipino"),
    SubtitleLanguage("fi", "Finnish"),
    SubtitleLanguage("fr", "French"),
    SubtitleLanguage("gl", "Galician"),
    SubtitleLanguage("ka", "Georgian"),
    SubtitleLanguage("de", "German"),
    SubtitleLanguage("el", "Greek"),
    SubtitleLanguage("gu", "Gujarati"),
    SubtitleLanguage("he", "Hebrew"),
    SubtitleLanguage("hi", "Hindi"),
    SubtitleLanguage("hu", "Hungarian"),
    SubtitleLanguage("is", "Icelandic"),
    SubtitleLanguage("id", "Indonesian"),
    SubtitleLanguage("ga", "Irish"),
    SubtitleLanguage("it", "Italian"),
    SubtitleLanguage("ja", "Japanese"),
    SubtitleLanguage("kn", "Kannada"),
    SubtitleLanguage("kk", "Kazakh"),
    SubtitleLanguage("km", "Khmer"),
    SubtitleLanguage("ko", "Korean"),
    SubtitleLanguage("lo", "Lao"),
    SubtitleLanguage("lv", "Latvian"),
    SubtitleLanguage("lt", "Lithuanian"),
    SubtitleLanguage("mk", "Macedonian"),
    SubtitleLanguage("ms", "Malay"),
    SubtitleLanguage("ml", "Malayalam"),
    SubtitleLanguage("mt", "Maltese"),
    SubtitleLanguage("mr", "Marathi"),
    SubtitleLanguage("mn", "Mongolian"),
    SubtitleLanguage("ne", "Nepali"),
    SubtitleLanguage("no", "Norwegian"),
    SubtitleLanguage("pa", "Punjabi"),
    SubtitleLanguage("fa", "Persian"),
    SubtitleLanguage("pl", "Polish"),
    SubtitleLanguage("pt", "Portuguese (Portugal)"),
    SubtitleLanguage("pt-br", "Portuguese (Brazil)"),
    SubtitleLanguage("ro", "Romanian"),
    SubtitleLanguage("ru", "Russian"),
    SubtitleLanguage("sr", "Serbian"),
    SubtitleLanguage("si", "Sinhala"),
    SubtitleLanguage("sk", "Slovak"),
    SubtitleLanguage("sl", "Slovenian"),
    SubtitleLanguage("es", "Spanish"),
    SubtitleLanguage("es-419", "Spanish (Latin America)"),
    SubtitleLanguage("sw", "Swahili"),
    SubtitleLanguage("sv", "Swedish"),
    SubtitleLanguage("ta", "Tamil"),
    SubtitleLanguage("te", "Telugu"),
    SubtitleLanguage("th", "Thai"),
    SubtitleLanguage("tr", "Turkish"),
    SubtitleLanguage("uk", "Ukrainian"),
    SubtitleLanguage("ur", "Urdu"),
    SubtitleLanguage("uz", "Uzbek"),
    SubtitleLanguage("vi", "Vietnamese"),
    SubtitleLanguage("cy", "Welsh"),
    SubtitleLanguage("zu", "Zulu")
)

val AVAILABLE_TMDB_LANGUAGES = AVAILABLE_SUBTITLE_LANGUAGES + listOf(
    SubtitleLanguage("en-AU", "English (Australia)"),
    SubtitleLanguage("en-CA", "English (Canada)"),
    SubtitleLanguage("en-GB", "English (United Kingdom)"),
)

/**
 * Data class representing subtitle style settings
 */
data class SubtitleStyleSettings(
    val preferredLanguage: String = "en",
    val secondaryPreferredLanguage: String? = null,
    val useForcedSubtitles: Boolean = false,
    val showOnlyPreferredLanguages: Boolean = false,
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
        const val DEFAULT_MIN_BUFFER_MS = 15_000
        const val DEFAULT_MAX_BUFFER_MS = 45_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 5_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val DEFAULT_TARGET_BUFFER_SIZE_MB: Int = 150
        // Media3 reserves additional bytes for back buffer as a fraction of
        // targetBufferBytes. 15s default keeps peak heap within Fire TV class
        // limits while still covering 3 default 5s seek-back presses.
        const val DEFAULT_BACK_BUFFER_DURATION_MS = 15_000
    }
}

/**
 * Available audio language options
 */
object AudioLanguageOption {
    const val DEFAULT = "default"  // Use media file default
    const val DEVICE = "device"    // Use device locale
    const val ORIGINAL = "original"  // Use content's original language (from TMDB)
}

enum class AudioOutputChannels(
    val settingValue: String,
    val displayLabel: String,
    val channelCount: Int,
    val ffmpegLayoutName: String
) {
    CHANNELS_2_0("2.0", "2.0", 2, "stereo"),
    CHANNELS_2_1("2.1", "2.1", 3, "2.1"),
    CHANNELS_3_0("3.0", "3.0", 3, "3.0"),
    CHANNELS_3_1("3.1", "3.1", 4, "3.1"),
    CHANNELS_4_0("4.0", "4.0", 4, "4.0"),
    CHANNELS_4_1("4.1", "4.1", 5, "4.1"),
    CHANNELS_5_0("5.0", "5.0", 5, "5.0"),
    CHANNELS_5_1("5.1", "5.1", 6, "5.1"),
    CHANNELS_7_0("7.0", "7.0", 7, "7.0"),
    CHANNELS_7_1("7.1", "7.1", 8, "7.1");

    companion object {
        val default = CHANNELS_7_1

        fun fromSettingValue(value: String?): AudioOutputChannels {
            return entries.firstOrNull { it.settingValue == value } ?: default
        }
    }
}

/**
 * Data class representing player settings
 */
data class PlayerSettings(
    val playerPreference: PlayerPreference = PlayerPreference.INTERNAL,
    val internalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
    val autoSwitchInternalPlayerOnError: Boolean = false,
    val useLibass: Boolean = false,
    val libassRenderType: LibassRenderType = LibassRenderType.OVERLAY_OPEN_GL,
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    val bufferSettings: BufferSettings = BufferSettings(),
    // Audio settings
    val decoderPriority: Int = 1, // EXTENSION_RENDERER_MODE_ON (0=off, 1=on, 2=prefer)
    val downmixEnabled: Boolean = false,
    val audioOutputChannels: AudioOutputChannels = AudioOutputChannels.default,
    val maintainOriginalAudioOnDownmix: Boolean = true,
    val tunnelingEnabled: Boolean = false,
    val forceOpticalPassthrough: Boolean = false,
    val skipSilence: Boolean = false,
    val audioAmplificationDb: Int = 0,
    val centerMixLevelDb: Int = 0,
    val persistAudioAmplification: Boolean = false,
    val rememberAudioDelayPerDevice: Boolean = true,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val loadingOverlayEnabled: Boolean = true,
    val showPlayerLoadingStatus: Boolean = true,
    val playbackIssueReportsEnabled: Boolean = false,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    val parentalGuideEnabled: Boolean = true,
    val autoSkipSegmentTypes: Set<AutoSkipSegmentType> = emptySet(),
    // Dolby Vision settings (libdovi conversion). dv7HandlingMode == HDR10_BASE_LAYER
    // replaces the legacy mapDV7ToHevc boolean (strip DV7, play HEVC base layer).
    val dv5ToDv81Enabled: Boolean = false,
    val dv7ToDv81PreserveMappingEnabled: Boolean = false,
    val dv7HandlingMode: Dv7HandlingMode = Dv7HandlingMode.AUTO,
    // Experimental libdovi conversion-mode override. -1 = auto (use the
    // profile-driven auto-pick); 0..4 = force that exact libdovi mode
    // (0 copy, 1 MEL, 2 8.1 no-op, 3 8.4 static, 4 8.1 preserve-mapping).
    // Only honored when dv7HandlingMode is OFF or DV81_LIBDOVI.
    val dv7LibdoviModeOverride: Int = -1,
    val stripHdr10PlusSei: Boolean = false,
    val mpvHardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE,
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
    val streamAutoPlayReuseBingeGroup: Boolean = true,
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val stillWatchingEnabled: Boolean = false,
    val stillWatchingEpisodeThreshold: Int = DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val externalPlayerForwardSubtitles: Boolean = false,
    val subtitleOrganizationMode: SubtitleOrganizationMode = SubtitleOrganizationMode.NONE,

    // Networking
    val bufferEngineEnabled: Boolean = false,
    val parallelNetworkEnabled: Boolean = false,
    /** When true the device memory budget caps the buffer; when false Target Buffer Size drives it. */
    val bufferBudgetManaged: Boolean = DEFAULT_BUFFER_BUDGET_MANAGED,
    /** When true, target buffer slider max is raised to 2GB regardless of device memory. */
    val allowLargeTargetBuffer: Boolean = DEFAULT_ALLOW_LARGE_TARGET_BUFFER,
    val vodCacheEnabled: Boolean = DEFAULT_VOD_CACHE_ENABLED,
    val vodCacheSizeMode: VodCacheSizeMode = DEFAULT_VOD_CACHE_SIZE_MODE,
    val vodCacheSizeMb: Int = DEFAULT_VOD_CACHE_SIZE_MB,
    val useParallelConnections: Boolean = DEFAULT_USE_PARALLEL_CONNECTIONS,
    val parallelConnectionCount: Int = DEFAULT_PARALLEL_CONNECTION_COUNT,
    val parallelChunkSizeMb: Int = DEFAULT_PARALLEL_CHUNK_SIZE_MB,
    val enableHttp2: Boolean = DEFAULT_ENABLE_HTTP2,

    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    val enableBufferLogs: Boolean = false,
    val resizeMode: Int = 0,
    // Nuvio ExoPlayer Performance Mode
    val nuvioPerformanceModeEnabled: Boolean = DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED
) {
    companion object {
        const val DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD = 3
        const val MIN_STILL_WATCHING_EPISODE_THRESHOLD = 2
        const val MAX_STILL_WATCHING_EPISODE_THRESHOLD = 6

        const val STREAM_AUTOPLAY_TIMEOUT_UNLIMITED = Int.MAX_VALUE

        val STREAM_AUTOPLAY_TIMEOUT_VALUES: List<Int> =
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, STREAM_AUTOPLAY_TIMEOUT_UNLIMITED)

        fun applyLegacyTimeoutSentinelMigration(stored: Int?): Int {
            val raw = stored ?: 3
            if (raw == 11) return STREAM_AUTOPLAY_TIMEOUT_UNLIMITED
            if (raw in STREAM_AUTOPLAY_TIMEOUT_VALUES) return raw
            return STREAM_AUTOPLAY_TIMEOUT_VALUES
                .filter { it != STREAM_AUTOPLAY_TIMEOUT_UNLIMITED }
                .minBy { kotlin.math.abs(it.toLong() - raw.toLong()) }
        }

        fun isBoundedTimeout(timeoutSeconds: Int): Boolean =
            timeoutSeconds > 0 && timeoutSeconds != STREAM_AUTOPLAY_TIMEOUT_UNLIMITED

        const val DEFAULT_BUFFER_BUDGET_MANAGED = true
        const val DEFAULT_ALLOW_LARGE_TARGET_BUFFER = false
        const val LARGE_TARGET_BUFFER_MAX_MB = 2048
        const val DEFAULT_VOD_CACHE_ENABLED = false
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
        const val DEFAULT_ENABLE_HTTP2 = false
        const val DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED = false
    }
}

enum class StreamAutoPlayMode {
    MANUAL, FIRST_STREAM, REGEX_MATCH
}

enum class StreamAutoPlaySource {
    ALL_SOURCES, INSTALLED_ADDONS_ONLY, ENABLED_PLUGINS_ONLY
}

enum class VodCacheSizeMode {
    AUTO, MANUAL
}

enum class FrameRateMatchingMode {
    OFF, START, START_STOP
}

enum class NextEpisodeThresholdMode {
    PERCENTAGE, MINUTES_BEFORE_END
}

enum class SubtitleOrganizationMode {
    NONE, BY_LANGUAGE, BY_ADDON
}

enum class AddonSubtitleStartupMode {
    FAST_STARTUP, PREFERRED_ONLY, ALL_SUBTITLES
}

enum class MpvHardwareDecodeMode {
    LEGACY_DIRECT_COPY, AUTO_SAFE, HARDWARE_COPY, HARDWARE_DIRECT, DISABLED
}

enum class AutoSkipSegmentType(val storedValue: String) {
    INTRO("intro"),
    RECAP("recap"),
    OUTRO("outro");

    companion object {
        fun fromStoredValue(value: String): AutoSkipSegmentType? =
            values().firstOrNull { it.storedValue == value }

        fun fromSkipIntervalType(type: String): AutoSkipSegmentType? = when (type.trim().lowercase()) {
            "op", "opening", "mixed-op", "intro" -> INTRO
            "recap" -> RECAP
            "ed", "ending", "mixed-ed", "outro", "credits" -> OUTRO
            else -> null
        }
    }
}

enum class PlayerPreference {
    INTERNAL, EXTERNAL, ASK_EVERY_TIME
}

enum class InternalPlayerEngine {
    EXOPLAYER,
    MVP_PLAYER,
    AUTO
}

enum class LibassRenderType {
    CUES, EFFECTS_CANVAS, EFFECTS_OPEN_GL, OVERLAY_CANVAS, OVERLAY_OPEN_GL
}
/**
 * How DV7 streams should be handled at playback time.
 *
 * AUTO is the recommended default; it queries display capabilities and
 * routes via [com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy].
 *
 * The other three values bypass the policy and apply unconditionally:
 * - HDR10_BASE_LAYER: ignore DV metadata, play HEVC base layer
 * - DV81_LIBDOVI: libdovi DV7 to DV8.1 conversion
 *   STRIP_DV: libdovi DV stripping
 * - OFF: pass DV7 through untouched (may glitch on hardware lacking DV7 support)
 */
enum class Dv7HandlingMode {
    AUTO,
    HDR10_BASE_LAYER,
    DV81_LIBDOVI,
    STRIP_DV,
    OFF;

    companion object {
        /** Tolerant string parser used by the DataStore. */
        fun fromStoredString(value: String?): Dv7HandlingMode = when (value) {
            AUTO.name -> AUTO
            HDR10_BASE_LAYER.name -> HDR10_BASE_LAYER
            DV81_LIBDOVI.name -> DV81_LIBDOVI
            STRIP_DV.name -> STRIP_DV
            OFF.name -> OFF
            else -> AUTO
        }
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FEATURE = "player_settings"
        private const val AUDIO_AMPLIFICATION_DB_MIN = 0
        private const val AUDIO_AMPLIFICATION_DB_MAX = 10
        private const val CENTER_MIX_LEVEL_DB_MIN = -10
        private const val CENTER_MIX_LEVEL_DB_MAX = 30
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Keys
    private val playerPreferenceKey = stringPreferencesKey("player_preference")
    private val internalPlayerEngineKey = stringPreferencesKey("internal_player_engine")
    private val autoSwitchInternalPlayerOnErrorKey = booleanPreferencesKey("auto_switch_internal_player_on_error")
    private val useLibassKey = booleanPreferencesKey("use_libass")
    private val libassRenderTypeKey = stringPreferencesKey("libass_render_type")
    private val decoderPriorityKey = intPreferencesKey("decoder_priority")
    private val downmixEnabledKey = booleanPreferencesKey("downmix_enabled")
    private val audioOutputChannelsKey = stringPreferencesKey("audio_output_channels")
    private val maintainOriginalAudioOnDownmixKey =
        booleanPreferencesKey("maintain_original_audio_on_downmix")
    private val downmixNormalizationEnabledLegacyKey =
        booleanPreferencesKey("downmix_normalization_enabled")
    private val tunnelingEnabledKey = booleanPreferencesKey("tunneling_enabled")
    private val forceOpticalPassthroughKey = booleanPreferencesKey("force_optical_passthrough")
    private val skipSilenceKey = booleanPreferencesKey("skip_silence")
    private val audioAmplificationDbKey = intPreferencesKey("audio_amplification_db")
    private val centerMixLevelDbKey = intPreferencesKey("center_mix_level_db")
    private val persistAudioAmplificationKey = booleanPreferencesKey("persist_audio_amplification")
    private val rememberAudioDelayPerDeviceKey = booleanPreferencesKey("remember_audio_delay_per_device")
    private val preferredAudioLanguageKey = stringPreferencesKey("preferred_audio_language")
    private val secondaryPreferredAudioLanguageKey = stringPreferencesKey("secondary_preferred_audio_language")
    private val loadingOverlayEnabledKey = booleanPreferencesKey("loading_overlay_enabled")
    private val showPlayerLoadingStatusKey = booleanPreferencesKey("show_player_loading_status")
    private val playbackIssueReportsEnabledKey = booleanPreferencesKey("playback_issue_reports_enabled")
    private val pauseOverlayEnabledKey = booleanPreferencesKey("pause_overlay_enabled")
    private val osdClockEnabledKey = booleanPreferencesKey("osd_clock_enabled")
    private val skipIntroEnabledKey = booleanPreferencesKey("skip_intro_enabled")
    private val parentalGuideEnabledKey = booleanPreferencesKey("parental_guide_enabled")
    private val autoSkipSegmentTypesKey = stringSetPreferencesKey("auto_skip_segment_types")

    // DV Keys
    // NOTE: pref-key STRINGS retain the legacy `experimental_*` names so users upgrading
    // from older versions don't lose their saved DV5/preserve-mapping toggle state. Only
    // the Kotlin var names here and the PlayerSettings field names were de-experimentalized.
    private val dv5ToDv81EnabledKey = booleanPreferencesKey("experimental_dv5_to_dv81_enabled")
    private val dv7ToDv81PreserveMappingEnabledKey = booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
    private val dv7HandlingModeKey = stringPreferencesKey("dv7_handling_mode")
    // Legacy "DV7 - HEVC" boolean, read only to migrate existing users to HDR10_BASE_LAYER.
    private val legacyMapDv7ToHevcKey = booleanPreferencesKey("map_dv7_to_hevc")
    private val dv7LibdoviModeOverrideKey = intPreferencesKey("dv7_libdovi_mode_override")
    private val stripHdr10PlusSeiKey = booleanPreferencesKey("strip_hdr10plus_sei")
    private val mpvHardwareDecodeModeKey = stringPreferencesKey("mpv_hardware_decode_mode")
    private val frameRateMatchingKey = booleanPreferencesKey("frame_rate_matching")
    private val frameRateMatchingModeKey = stringPreferencesKey("frame_rate_matching_mode")
    private val resolutionMatchingEnabledKey = booleanPreferencesKey("resolution_matching_enabled")
    private val streamAutoPlayModeKey = stringPreferencesKey("stream_auto_play_mode")
    private val streamAutoPlaySourceKey = stringPreferencesKey("stream_auto_play_source")
    private val streamAutoPlaySelectedAddonsKey = stringSetPreferencesKey("stream_auto_play_selected_addons")
    private val streamAutoPlaySelectedPluginsKey = stringSetPreferencesKey("stream_auto_play_selected_plugins")
    private val streamAutoPlayRegexKey = stringPreferencesKey("stream_auto_play_regex")
    private val streamAutoPlayNextEpisodeEnabledKey = booleanPreferencesKey("stream_auto_play_next_episode_enabled")
    private val streamAutoPlayPreferBingeGroupForNextEpisodeKey = booleanPreferencesKey("stream_auto_play_prefer_bingegroup_next_episode")
    private val streamAutoPlayReuseBingeGroupKey = booleanPreferencesKey("stream_auto_play_reuse_binge_group")
    private val streamAutoPlayTimeoutSecondsKey = intPreferencesKey("stream_auto_play_timeout_seconds")
    private val stillWatchingEnabledKey = booleanPreferencesKey("still_watching_enabled")
    private val stillWatchingEpisodeThresholdKey = intPreferencesKey("still_watching_episode_threshold")
    private val nextEpisodeThresholdModeKey = stringPreferencesKey("next_episode_threshold_mode")
    private val nextEpisodeThresholdPercentLegacyKey = intPreferencesKey("next_episode_threshold_percent")
    private val nextEpisodeThresholdMinutesBeforeEndLegacyKey = intPreferencesKey("next_episode_threshold_minutes_before_end")
    private val nextEpisodeThresholdPercentKey = floatPreferencesKey("next_episode_threshold_percent_v2")
    private val nextEpisodeThresholdMinutesBeforeEndKey = floatPreferencesKey("next_episode_threshold_minutes_before_end_v2")
    private val streamReuseLastLinkEnabledKey = booleanPreferencesKey("stream_reuse_last_link_enabled")
    private val streamReuseLastLinkCacheHoursKey = intPreferencesKey("stream_reuse_last_link_cache_hours")
    private val externalPlayerForwardSubtitlesKey = booleanPreferencesKey("external_player_forward_subtitles")
    private val subtitleOrganizationModeKey = stringPreferencesKey("subtitle_organization_mode")

    // Network Keys
    private val vodCacheEnabledKey = booleanPreferencesKey("vod_cache_enabled")
    private val vodCacheSizeModeKey = stringPreferencesKey("vod_cache_size_mode")
    private val vodCacheSizeMbKey = intPreferencesKey("vod_cache_size_mb")
    private val useParallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
    private val bufferEngineEnabledKey = booleanPreferencesKey("buffer_engine_enabled")
    private val parallelNetworkEnabledKey = booleanPreferencesKey("parallel_network_enabled")
    private val allowLargeTargetBufferKey = booleanPreferencesKey("allow_large_target_buffer")
    private val bufferBudgetManagedKey = booleanPreferencesKey("buffer_budget_managed")
    private val parallelConnectionCountKey = intPreferencesKey("parallel_connection_count")
    private val parallelChunkSizeMbKey = intPreferencesKey("parallel_chunk_size_mb")
    private val enableHttp2Key = booleanPreferencesKey("enable_http2")
    private val lastPlaybackDiagnosticsKey = stringPreferencesKey("last_playback_diagnostics_json")

    private val addonSubtitleStartupModeKey = stringPreferencesKey("addon_subtitle_startup_mode")
    private val addonSubtitleStartupModeAutoPreferredKey =
        booleanPreferencesKey("addon_subtitle_startup_mode_auto_preferred")
    private val enableBufferLogsKey = booleanPreferencesKey("enable_buffer_logs")
    private val resizeModeKey = intPreferencesKey("resize_mode")

    // Subtitle style keys
    private val subtitlePreferredLanguageKey = stringPreferencesKey("subtitle_preferred_language")
    private val subtitleSecondaryLanguageKey = stringPreferencesKey("subtitle_secondary_language")
    private val subtitleUseForcedSubtitlesKey = booleanPreferencesKey("subtitle_use_forced_subtitles")
    private val subtitleShowOnlyPreferredLanguagesKey = booleanPreferencesKey("subtitle_show_only_preferred_languages")
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
    private val nuvioPerformanceModeEnabledKey = booleanPreferencesKey("nuvio_performance_mode_enabled")

    private val migrationLoadControlDefaultsAlignedDoneKey = booleanPreferencesKey("migration_load_control_defaults_aligned_done")
    private val migrationLoadControlDefaultsRetunedDoneKey = booleanPreferencesKey("migration_load_control_defaults_retuned_done")
    private val migrationLoadControlMinBufferRetunedDoneKey = booleanPreferencesKey("migration_load_control_min_buffer_retuned_done")
    private val migrationVodCacheSplitDoneKey = booleanPreferencesKey("migration_vod_cache_split_done")
    private val migrationBackBufferDurationBumpedDoneKey = booleanPreferencesKey("migration_back_buffer_duration_bumped_done")
    private val migrationMaxBufferBumpedDoneKey = booleanPreferencesKey("migration_max_buffer_bumped_done")
    private val migrationTargetBufferSizeBumpedDoneKey = booleanPreferencesKey("migration_target_buffer_size_bumped_done")
    private val migrationAfterRebufferLoweredDoneKey = booleanPreferencesKey("migration_after_rebuffer_lowered_done")
    private val migrationBackBufferDurationReducedDoneKey = booleanPreferencesKey("migration_back_buffer_duration_reduced_done")
    private val migrationTargetBufferSizeReducedDoneKey = booleanPreferencesKey("migration_target_buffer_size_reduced_done")
    init {
        ioScope.launch {
            profileManager.activeProfileId.collect { pid ->
                migrateProfile(pid)
            }
        }
    }

    private suspend fun migrateProfile(profileId: Int) {
        factory.get(profileId, FEATURE).edit { prefs ->
                val loadControlMigrated = prefs[migrationLoadControlDefaultsAlignedDoneKey] ?: false
                if (!loadControlMigrated) {
                    val currentMin = prefs[minBufferMsKey]
                    val currentMax = prefs[maxBufferMsKey]
                    val legacyDefaultsDetected = (currentMin == null && currentMax == null) || (currentMin == 15_000 && currentMax == 25_000)
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

                    val previousDefaultsDetected = currentMin == 50_000 && currentMax == 50_000 && currentPlayback == 2_500 && currentPlaybackAfterRebuffer == 5_000 && currentTargetBuffer == 0
                    val olderDefaultsDetected = currentMin == 15_000 && currentMax == 25_000

                    if (previousDefaultsDetected || olderDefaultsDetected) {
                        prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                        prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                        prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                        prefs[bufferForPlaybackAfterRebufferMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
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

                    val previousRetunedDefaultsDetected = currentMin == 50_000 && currentMax == 50_000 && currentPlayback == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS && currentPlaybackAfterRebuffer == BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS && currentTargetBuffer == BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB && (currentBackBuffer == null || currentBackBuffer == BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS) && (currentRetainBackBuffer == null || !currentRetainBackBuffer)

                    if (previousRetunedDefaultsDetected) prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                    prefs[migrationLoadControlMinBufferRetunedDoneKey] = true
                }

                // VOD cache split: previously the disk cache was implicitly on with the
                // parallel-network section. Now it has its own toggle, defaulting to on.
                val vodCacheSplitMigrated = prefs[migrationVodCacheSplitDoneKey] ?: false
                if (!vodCacheSplitMigrated) {
                    if (prefs[vodCacheEnabledKey] == null) {
                        prefs[vodCacheEnabledKey] = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
                    }
                    prefs[migrationVodCacheSplitDoneKey] = true
                }

                // Back buffer bump from prior 10s default.
                val backBufferBumped = prefs[migrationBackBufferDurationBumpedDoneKey] ?: false
                if (!backBufferBumped) {
                    val currentBackBuffer = prefs[backBufferDurationMsKey]
                    if (currentBackBuffer == null || currentBackBuffer == 10_000) {
                        prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
                    }
                    prefs[migrationBackBufferDurationBumpedDoneKey] = true
                }

                // Max buffer bump from prior 30s default.
                val maxBufferBumped = prefs[migrationMaxBufferBumpedDoneKey] ?: false
                if (!maxBufferBumped) {
                    val currentMax = prefs[maxBufferMsKey]
                    if (currentMax == null || currentMax == 30_000) {
                        prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                    }
                    prefs[migrationMaxBufferBumpedDoneKey] = true
                }

                // Target buffer bump from prior 100MB default.
                val targetBufferBumped = prefs[migrationTargetBufferSizeBumpedDoneKey] ?: false
                if (!targetBufferBumped) {
                    val currentTarget = prefs[targetBufferSizeMbKey]
                    if (currentTarget == null || currentTarget == 100) {
                        prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
                    }
                    prefs[migrationTargetBufferSizeBumpedDoneKey] = true
                }

                // After-rebuffer threshold lowered from prior 5s default for faster resume.
                val afterRebufferLowered = prefs[migrationAfterRebufferLoweredDoneKey] ?: false
                if (!afterRebufferLowered) {
                    val currentAfter = prefs[bufferForPlaybackAfterRebufferMsKey]
                    if (currentAfter == null || currentAfter == 5_000) {
                        prefs[bufferForPlaybackAfterRebufferMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                    }
                    prefs[migrationAfterRebufferLoweredDoneKey] = true
                }

                // Back buffer reduced from interim 30s due to heap pressure on high-bitrate content.
                val backBufferReduced = prefs[migrationBackBufferDurationReducedDoneKey] ?: false
                if (!backBufferReduced) {
                    val currentBack = prefs[backBufferDurationMsKey]
                    if (currentBack == null || currentBack == 30_000) {
                        prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
                    }
                    prefs[migrationBackBufferDurationReducedDoneKey] = true
                }

                // Corrects users from a 125 interim build back to the 150 default.
                val targetBufferCorrected = prefs[migrationTargetBufferSizeReducedDoneKey] ?: false
                if (!targetBufferCorrected) {
                    val currentTarget = prefs[targetBufferSizeMbKey]
                    if (currentTarget == 125) {
                        prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
                    }
                    prefs[migrationTargetBufferSizeReducedDoneKey] = true
                }

                val min = prefs[minBufferMsKey]
                val max = prefs[maxBufferMsKey]
                if (min != null && max != null && max < min) prefs[maxBufferMsKey] = min

                prefs[vodCacheSizeMbKey]?.let { current ->
                    val normalized = current.coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
                    if (normalized != current) prefs[vodCacheSizeMbKey] = normalized
                }
                prefs[vodCacheSizeModeKey]?.let { raw ->
                    val normalized = runCatching { VodCacheSizeMode.valueOf(raw) }.getOrDefault(PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE).name
                    if (normalized != raw) prefs[vodCacheSizeModeKey] = normalized
                }

                val preferredAudioLanguage = prefs[preferredAudioLanguageKey]
                if (preferredAudioLanguage != null) {
                    val normalizedPreferredAudioLanguage = normalizeSelectableLanguageCode(preferredAudioLanguage)
                    if (normalizedPreferredAudioLanguage != preferredAudioLanguage) {
                        prefs[preferredAudioLanguageKey] = normalizedPreferredAudioLanguage
                    }
                }

                val secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
                if (secondaryPreferredAudioLanguage != null) {
                    val normalizedSecondaryPreferredAudioLanguage = normalizeSecondaryAudioLanguageCode(secondaryPreferredAudioLanguage)
                    if (normalizedSecondaryPreferredAudioLanguage != secondaryPreferredAudioLanguage) {
                        if (normalizedSecondaryPreferredAudioLanguage != null) prefs[secondaryPreferredAudioLanguageKey] = normalizedSecondaryPreferredAudioLanguage
                        else prefs.remove(secondaryPreferredAudioLanguageKey)
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

            val normalizedPreferredSubtitleLanguage =
                preferredSubtitleLanguage?.let(::normalizeSelectableLanguageCode)
            val normalizedSecondarySubtitleLanguage =
                secondarySubtitleLanguage?.let(::normalizeSelectableLanguageCode)
            when {
                normalizedPreferredSubtitleLanguage == SUBTITLE_LANGUAGE_FORCED -> {
                    prefs[subtitleUseForcedSubtitlesKey] = true
                    val migratedPreferred = normalizedSecondarySubtitleLanguage
                        ?.takeUnless { it == SUBTITLE_LANGUAGE_FORCED || it == "none" }
                        ?: "en"
                    prefs[subtitlePreferredLanguageKey] = migratedPreferred
                    prefs.remove(subtitleSecondaryLanguageKey)
                }
                normalizedSecondarySubtitleLanguage == SUBTITLE_LANGUAGE_FORCED -> {
                    prefs[subtitleUseForcedSubtitlesKey] = true
                    prefs.remove(subtitleSecondaryLanguageKey)
                }
            }
        }
    }

    /**
     * Flow of current player settings
     */
    val playerSettings: Flow<PlayerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.onStart { migrateProfile(pid) }
    }.map { prefs ->
            PlayerSettings(
                playerPreference = prefs[playerPreferenceKey]?.let {
                    runCatching { PlayerPreference.valueOf(it) }.getOrDefault(PlayerPreference.INTERNAL)
                } ?: PlayerPreference.INTERNAL,
                internalPlayerEngine = prefs[internalPlayerEngineKey]?.let {
                    runCatching { InternalPlayerEngine.valueOf(it) }.getOrDefault(InternalPlayerEngine.EXOPLAYER)
                } ?: InternalPlayerEngine.EXOPLAYER,
                autoSwitchInternalPlayerOnError = prefs[autoSwitchInternalPlayerOnErrorKey] ?: false,
                useLibass = prefs[useLibassKey] ?: false,
                libassRenderType = prefs[libassRenderTypeKey]?.let {
                    try { LibassRenderType.valueOf(it) } catch (e: Exception) { LibassRenderType.OVERLAY_OPEN_GL }
                } ?: LibassRenderType.OVERLAY_OPEN_GL,
                decoderPriority = prefs[decoderPriorityKey] ?: 1,
                downmixEnabled =
                    prefs[downmixEnabledKey]
                        ?: (
                            prefs[audioOutputChannelsKey] != null ||
                                prefs[maintainOriginalAudioOnDownmixKey] != null ||
                                prefs[downmixNormalizationEnabledLegacyKey] != null
                            ),
                audioOutputChannels = AudioOutputChannels.fromSettingValue(
                    prefs[audioOutputChannelsKey]
                ),
                maintainOriginalAudioOnDownmix =
                    prefs[maintainOriginalAudioOnDownmixKey]
                        ?: !(prefs[downmixNormalizationEnabledLegacyKey] ?: false),
                tunnelingEnabled = prefs[tunnelingEnabledKey] ?: false,
                forceOpticalPassthrough = prefs[forceOpticalPassthroughKey] ?: false,
                skipSilence = prefs[skipSilenceKey] ?: false,
                audioAmplificationDb = (prefs[audioAmplificationDbKey] ?: 0).coerceIn(
                    AUDIO_AMPLIFICATION_DB_MIN,
                    AUDIO_AMPLIFICATION_DB_MAX
                ),
                centerMixLevelDb = (prefs[centerMixLevelDbKey] ?: 0).coerceIn(
                    CENTER_MIX_LEVEL_DB_MIN,
                    CENTER_MIX_LEVEL_DB_MAX
                ),
                persistAudioAmplification = prefs[persistAudioAmplificationKey] ?: false,
                rememberAudioDelayPerDevice = prefs[rememberAudioDelayPerDeviceKey] ?: true,
                preferredAudioLanguage = normalizeSelectableLanguageCode(
                    prefs[preferredAudioLanguageKey] ?: AudioLanguageOption.DEVICE
                ),
                secondaryPreferredAudioLanguage = prefs[secondaryPreferredAudioLanguageKey]
                    ?.let(::normalizeSecondaryAudioLanguageCode),
                loadingOverlayEnabled = prefs[loadingOverlayEnabledKey] ?: true,
                showPlayerLoadingStatus = prefs[showPlayerLoadingStatusKey] ?: true,
                playbackIssueReportsEnabled = prefs[playbackIssueReportsEnabledKey] ?: false,
                pauseOverlayEnabled = prefs[pauseOverlayEnabledKey] ?: true,
                osdClockEnabled = prefs[osdClockEnabledKey] ?: true,
                skipIntroEnabled = prefs[skipIntroEnabledKey] ?: true,
                parentalGuideEnabled = prefs[parentalGuideEnabledKey] ?: true,
                autoSkipSegmentTypes = prefs[autoSkipSegmentTypesKey]
                    ?.mapNotNull(AutoSkipSegmentType::fromStoredValue)
                    ?.toSet()
                    ?: emptySet(),
                dv5ToDv81Enabled = prefs[dv5ToDv81EnabledKey] ?: false,
                dv7ToDv81PreserveMappingEnabled = prefs[dv7ToDv81PreserveMappingEnabledKey] ?: false,
                dv7HandlingMode = when {
                    prefs[dv7HandlingModeKey] != null ->
                        Dv7HandlingMode.fromStoredString(prefs[dv7HandlingModeKey])
                    prefs[legacyMapDv7ToHevcKey] == true -> Dv7HandlingMode.HDR10_BASE_LAYER
                    else -> Dv7HandlingMode.AUTO
                },
                dv7LibdoviModeOverride = (prefs[dv7LibdoviModeOverrideKey] ?: -1).coerceIn(-1, 4),
                stripHdr10PlusSei = prefs[stripHdr10PlusSeiKey] ?: false,
                mpvHardwareDecodeMode = parseMpvHardwareDecodeMode(prefs[mpvHardwareDecodeModeKey]),
                frameRateMatchingMode = prefs[frameRateMatchingModeKey]?.let {
                    runCatching { FrameRateMatchingMode.valueOf(it) }.getOrNull()
                } ?: if (prefs[frameRateMatchingKey] == true) FrameRateMatchingMode.START_STOP else FrameRateMatchingMode.OFF,
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
                streamAutoPlayReuseBingeGroup =
                    prefs[streamAutoPlayReuseBingeGroupKey] ?: true,
                streamAutoPlayTimeoutSeconds = PlayerSettings.applyLegacyTimeoutSentinelMigration(
                    prefs[streamAutoPlayTimeoutSecondsKey]
                ),
                stillWatchingEnabled = prefs[stillWatchingEnabledKey] ?: false,
                stillWatchingEpisodeThreshold = prefs[stillWatchingEpisodeThresholdKey]
                    ?.coerceIn(
                        PlayerSettings.MIN_STILL_WATCHING_EPISODE_THRESHOLD,
                        PlayerSettings.MAX_STILL_WATCHING_EPISODE_THRESHOLD
                    )
                    ?: PlayerSettings.DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD,
                nextEpisodeThresholdMode = prefs[nextEpisodeThresholdModeKey]?.let {
                    runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrDefault(NextEpisodeThresholdMode.PERCENTAGE)
                } ?: NextEpisodeThresholdMode.PERCENTAGE,
                nextEpisodeThresholdPercent = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdPercentKey]
                        ?: prefs[nextEpisodeThresholdPercentLegacyKey]?.toFloat()
                        ?: 99f,
                    min = 97f,
                    max = 100f
                ),
                nextEpisodeThresholdMinutesBeforeEnd = normalizeHalfStep(
                    value = prefs[nextEpisodeThresholdMinutesBeforeEndKey]
                        ?: prefs[nextEpisodeThresholdMinutesBeforeEndLegacyKey]?.toFloat()
                        ?: 2f,
                    min = 0f,
                    max = 3.5f
                ),
                streamReuseLastLinkEnabled = prefs[streamReuseLastLinkEnabledKey] ?: false,
                streamReuseLastLinkCacheHours = (prefs[streamReuseLastLinkCacheHoursKey] ?: 24).coerceIn(1, 168),
                externalPlayerForwardSubtitles = prefs[externalPlayerForwardSubtitlesKey] ?: false,
                subtitleOrganizationMode = parseSubtitleOrganizationMode(prefs[subtitleOrganizationModeKey]),
                vodCacheEnabled = prefs[vodCacheEnabledKey] ?: PlayerSettings.DEFAULT_VOD_CACHE_ENABLED,
                vodCacheSizeMode = prefs[vodCacheSizeModeKey]?.let {
                    runCatching { VodCacheSizeMode.valueOf(it) }.getOrDefault(PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE)
                } ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE,
                vodCacheSizeMb = (prefs[vodCacheSizeMbKey] ?: PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB).coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB),
                useParallelConnections = prefs[useParallelConnectionsKey] ?: PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS,
                bufferEngineEnabled = prefs[bufferEngineEnabledKey] ?: false,
                parallelNetworkEnabled = prefs[parallelNetworkEnabledKey] ?: false,
                allowLargeTargetBuffer = prefs[allowLargeTargetBufferKey] ?: PlayerSettings.DEFAULT_ALLOW_LARGE_TARGET_BUFFER,
                bufferBudgetManaged = prefs[bufferBudgetManagedKey] ?: PlayerSettings.DEFAULT_BUFFER_BUDGET_MANAGED,
                parallelConnectionCount = run {
                    val isNativeMemory = isNativeMemoryActive(prefs)
                    val defaultConnectionCount = if (isNativeMemory) 4 else PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
                    val maxConnectionCount = if (isNativeMemory) 16 else PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                    (prefs[parallelConnectionCountKey] ?: defaultConnectionCount).coerceIn(PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT, maxConnectionCount)
                },
                parallelChunkSizeMb = (prefs[parallelChunkSizeMbKey] ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB).coerceIn(PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB, PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB),
                addonSubtitleStartupMode = parseAddonSubtitleStartupMode(prefs[addonSubtitleStartupModeKey]),
                enableBufferLogs = prefs[enableBufferLogsKey] ?: false,
                resizeMode = (prefs[resizeModeKey] ?: 0).coerceIn(0, 4),
                enableHttp2 = prefs[enableHttp2Key] ?: PlayerSettings.DEFAULT_ENABLE_HTTP2,
                nuvioPerformanceModeEnabled = (prefs[nuvioPerformanceModeEnabledKey] ?: PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED) &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O,
                subtitleStyle = SubtitleStyleSettings(
                    preferredLanguage = normalizeSubtitlePreferredLanguageForRead(
                        prefs[subtitlePreferredLanguageKey],
                        prefs[subtitleSecondaryLanguageKey]
                    ),
                    secondaryPreferredLanguage = prefs[subtitleSecondaryLanguageKey]
                        ?.let(::normalizeSelectableLanguageCode)
                        ?.takeUnless { it == SUBTITLE_LANGUAGE_FORCED },
                    useForcedSubtitles = (prefs[subtitleUseForcedSubtitlesKey] ?: false) ||
                        prefs[subtitlePreferredLanguageKey]?.let(::normalizeSelectableLanguageCode) == SUBTITLE_LANGUAGE_FORCED ||
                        prefs[subtitleSecondaryLanguageKey]?.let(::normalizeSelectableLanguageCode) == SUBTITLE_LANGUAGE_FORCED,
                    showOnlyPreferredLanguages = prefs[subtitleShowOnlyPreferredLanguagesKey] ?: false,
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
                    bufferForPlaybackMs = prefs[bufferForPlaybackMsKey] ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    bufferForPlaybackAfterRebufferMs = prefs[bufferForPlaybackAfterRebufferMsKey] ?: BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    targetBufferSizeMb = prefs[targetBufferSizeMbKey]?.coerceAtLeast(0) ?: BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB,
                    backBufferDurationMs = prefs[backBufferDurationMsKey] ?: BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS,
                    retainBackBufferFromKeyframe = prefs[retainBackBufferFromKeyframeKey] ?: false
                )
            )
        }

    val useLibass: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.onStart { migrateProfile(pid) }
    }.map { prefs ->
        prefs[useLibassKey] ?: false
    }

    val libassRenderType: Flow<LibassRenderType> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.onStart { migrateProfile(pid) }
    }.map { prefs ->
        prefs[libassRenderTypeKey]?.let {
            try { LibassRenderType.valueOf(it) } catch (e: Exception) { LibassRenderType.OVERLAY_OPEN_GL }
        } ?: LibassRenderType.OVERLAY_OPEN_GL
    }
    val lastPlaybackDiagnostics: Flow<LastPlaybackDiagnostics> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val json = prefs[lastPlaybackDiagnosticsKey]
            if (json.isNullOrBlank()) LastPlaybackDiagnostics.EMPTY
            else LastPlaybackDiagnostics.fromJson(json)
        }
    }

    // Player preference setter

    suspend fun setPlayerPreference(preference: PlayerPreference) {
        store().edit { prefs ->
            prefs[playerPreferenceKey] = preference.name
        }
    }

    suspend fun setInternalPlayerEngine(engine: InternalPlayerEngine) {
        store().edit { prefs ->
            prefs[internalPlayerEngineKey] = engine.name
        }
    }

    suspend fun setAutoSwitchInternalPlayerOnError(enabled: Boolean) {
        store().edit { prefs ->
            prefs[autoSwitchInternalPlayerOnErrorKey] = enabled
        }
    }

    // Audio settings setters

    suspend fun setDecoderPriority(priority: Int) {
        store().edit { prefs ->
            prefs[decoderPriorityKey] = priority.coerceIn(0, 2)
        }
    }

    suspend fun setDownmixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[downmixEnabledKey] = enabled
        }
    }

    suspend fun setAudioOutputChannels(channels: AudioOutputChannels) {
        store().edit { prefs ->
            prefs[downmixEnabledKey] = true
            prefs[audioOutputChannelsKey] = channels.settingValue
        }
    }

    suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean) {
        store().edit { prefs ->
            prefs[downmixEnabledKey] = true
            prefs[maintainOriginalAudioOnDownmixKey] = enabled
        }
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[tunnelingEnabledKey] = enabled
        }
    }

    suspend fun setForceOpticalPassthrough(enabled: Boolean) {
        store().edit { prefs ->
            prefs[forceOpticalPassthroughKey] = enabled
        }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        store().edit { prefs ->
            prefs[skipSilenceKey] = enabled
        }
    }

    suspend fun setAudioAmplificationDb(db: Int) {
        store().edit { prefs ->
            prefs[audioAmplificationDbKey] = db.coerceIn(
                AUDIO_AMPLIFICATION_DB_MIN,
                AUDIO_AMPLIFICATION_DB_MAX
            )
        }
    }

    suspend fun setCenterMixLevelDb(db: Int) {
        store().edit { prefs ->
            prefs[centerMixLevelDbKey] = db.coerceIn(
                CENTER_MIX_LEVEL_DB_MIN,
                CENTER_MIX_LEVEL_DB_MAX
            )
        }
    }

    suspend fun setPersistAudioAmplification(
        enabled: Boolean,
        dbToPersist: Int? = null,
        centerMixDbToPersist: Int? = null
    ) {
        store().edit { prefs ->
            prefs[persistAudioAmplificationKey] = enabled
            if (enabled && dbToPersist != null) {
                prefs[audioAmplificationDbKey] = dbToPersist.coerceIn(
                    AUDIO_AMPLIFICATION_DB_MIN,
                    AUDIO_AMPLIFICATION_DB_MAX
                )
            }
            if (enabled && centerMixDbToPersist != null) {
                prefs[centerMixLevelDbKey] = centerMixDbToPersist.coerceIn(
                    CENTER_MIX_LEVEL_DB_MIN,
                    CENTER_MIX_LEVEL_DB_MAX
                )
            }
        }
    }

    suspend fun setRememberAudioDelayPerDevice(enabled: Boolean) {
        store().edit { prefs ->
            prefs[rememberAudioDelayPerDeviceKey] = enabled
        }
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        store().edit { it[preferredAudioLanguageKey] = normalizeSelectableLanguageCode(language.ifBlank { AudioLanguageOption.DEVICE }) }
    }

    suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language?.takeIf { it.isNotBlank() }?.let(::normalizeSecondaryAudioLanguageCode)
            if (normalizedLanguage != null) prefs[secondaryPreferredAudioLanguageKey] = normalizedLanguage
            else prefs.remove(secondaryPreferredAudioLanguageKey)
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

    suspend fun setParentalGuideEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[parentalGuideEnabledKey] = enabled
        }
    }

    suspend fun setAutoSkipSegmentTypeEnabled(segmentType: AutoSkipSegmentType, enabled: Boolean) {
        store().edit { prefs ->
            val current = prefs[autoSkipSegmentTypesKey]
                ?.mapNotNull(AutoSkipSegmentType::fromStoredValue)
                ?.toSet()
                ?: emptySet()
            val updated = if (enabled) current + segmentType else current - segmentType
            prefs[autoSkipSegmentTypesKey] = updated.map { it.storedValue }.toSet()
        }
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[loadingOverlayEnabledKey] = enabled
        }
    }

    suspend fun setShowPlayerLoadingStatus(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showPlayerLoadingStatusKey] = enabled
        }
    }

    suspend fun setPlaybackIssueReportsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[playbackIssueReportsEnabledKey] = enabled
        }
    }

    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        store().edit { prefs ->
            prefs[frameRateMatchingModeKey] = mode.name
            prefs[frameRateMatchingKey] = mode != FrameRateMatchingMode.OFF
        }
    }

    suspend fun setEnableBufferLogs(enabled: Boolean) {
        store().edit { prefs ->
            prefs[enableBufferLogsKey] = enabled
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

    suspend fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        store().edit { prefs ->
            prefs[streamAutoPlayReuseBingeGroupKey] = enabled
        }
    }

    suspend fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[streamAutoPlayTimeoutSecondsKey] = PlayerSettings.applyLegacyTimeoutSentinelMigration(seconds)
        }
    }

    suspend fun setStillWatchingEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[stillWatchingEnabledKey] = enabled
        }
    }

    suspend fun setStillWatchingEpisodeThreshold(threshold: Int) {
        store().edit { prefs ->
            prefs[stillWatchingEpisodeThresholdKey] = threshold.coerceIn(
                PlayerSettings.MIN_STILL_WATCHING_EPISODE_THRESHOLD,
                PlayerSettings.MAX_STILL_WATCHING_EPISODE_THRESHOLD
            )
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
                max = 100f
            )
        }
    }

    suspend fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        store().edit { prefs ->
            prefs[nextEpisodeThresholdMinutesBeforeEndKey] = normalizeHalfStep(
                value = minutes,
                min = 0f,
                max = 3.5f
            )
        }
    }

    private fun normalizeHalfStep(value: Float, min: Float, max: Float): Float {
        return (value.coerceIn(min, max) * 2f).roundToInt() / 2f
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

    suspend fun setExternalPlayerForwardSubtitles(enabled: Boolean) {
        store().edit { prefs ->
            prefs[externalPlayerForwardSubtitlesKey] = enabled
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
            prefs[addonSubtitleStartupModeAutoPreferredKey] = false
        }
    }

    suspend fun setResizeMode(mode: Int) {
        store().edit { prefs ->
            prefs[resizeModeKey] = mode.coerceIn(0, 4)
        }
    }



    private fun parseSubtitleOrganizationMode(value: String?): SubtitleOrganizationMode {
        return when (value) { "BY_LANGUAGE" -> SubtitleOrganizationMode.BY_LANGUAGE; "BY_ADDON" -> SubtitleOrganizationMode.BY_ADDON; else -> SubtitleOrganizationMode.NONE }
    }

    private fun parseAddonSubtitleStartupMode(value: String?): AddonSubtitleStartupMode {
        return when (value) { "PREFERRED_ONLY" -> AddonSubtitleStartupMode.PREFERRED_ONLY; "FAST_STARTUP" -> AddonSubtitleStartupMode.FAST_STARTUP; else -> AddonSubtitleStartupMode.ALL_SUBTITLES }
    }

    private fun parseMpvHardwareDecodeMode(value: String?): MpvHardwareDecodeMode {
        return when (value) { "HARDWARE_COPY" -> MpvHardwareDecodeMode.HARDWARE_COPY; "HARDWARE_DIRECT" -> MpvHardwareDecodeMode.HARDWARE_DIRECT; "DISABLED" -> MpvHardwareDecodeMode.DISABLED; "LEGACY_DIRECT_COPY" -> MpvHardwareDecodeMode.LEGACY_DIRECT_COPY; else -> MpvHardwareDecodeMode.AUTO_SAFE }
    }

    private fun normalizeSelectableLanguageCode(language: String): String {
        val code = language.trim().lowercase()
        return when (code) { "pt-br", "pt_br", "br", "pob" -> "pt-br"; "pt-pt", "pt_pt", "por" -> "pt"; "forced", "force", "forc" -> SUBTITLE_LANGUAGE_FORCED; else -> code }
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

    private fun normalizeSubtitlePreferredLanguageForRead(
        preferredLanguage: String?,
        secondaryLanguage: String?
    ): String {
        val preferred = preferredLanguage
            ?.let(::normalizeSelectableLanguageCode)
            ?: return "en"
        if (preferred != SUBTITLE_LANGUAGE_FORCED) return preferred

        return secondaryLanguage
            ?.let(::normalizeSelectableLanguageCode)
            ?.takeUnless { it == SUBTITLE_LANGUAGE_FORCED || it == "none" }
            ?: "en"
    }

    suspend fun setMpvHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        store().edit { prefs ->
            prefs[mpvHardwareDecodeModeKey] = mode.name
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

    // Dolby Vision setters (libdovi conversion)
    suspend fun setDv5ToDv81Enabled(enabled: Boolean) { store().edit { it[dv5ToDv81EnabledKey] = enabled } }
    suspend fun setDv7ToDv81PreserveMappingEnabled(enabled: Boolean) { store().edit { it[dv7ToDv81PreserveMappingEnabledKey] = enabled } }
    suspend fun setDv7HandlingMode(mode: Dv7HandlingMode) { store().edit { it[dv7HandlingModeKey] = mode.name } }
    suspend fun setDv7LibdoviModeOverride(mode: Int) { store().edit { it[dv7LibdoviModeOverrideKey] = mode.coerceIn(-1, 4) } }
    suspend fun setStripHdr10PlusSei(enabled: Boolean) { store().edit { it[stripHdr10PlusSeiKey] = enabled } }

    // Subtitle styles
    suspend fun setSubtitlePreferredLanguage(language: String) { store().edit { it[subtitlePreferredLanguageKey] = normalizeSelectableLanguageCode(language.ifBlank { "en" }) } }
    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        store().edit { prefs ->
            val normalizedLanguage = language?.takeIf { it.isNotBlank() }?.let(::normalizeSelectableLanguageCode)
            if (normalizedLanguage != null) prefs[subtitleSecondaryLanguageKey] = normalizedLanguage
            else prefs.remove(subtitleSecondaryLanguageKey)
        }
    }
    suspend fun setSubtitleSize(size: Int) { store().edit { it[subtitleSizeKey] = size.coerceIn(50, 200) } }
    suspend fun setSubtitleVerticalOffset(offset: Int) { store().edit { it[subtitleVerticalOffsetKey] = offset.coerceIn(-20, 50) } }
    suspend fun setSubtitleBold(bold: Boolean) { store().edit { it[subtitleBoldKey] = bold } }
    suspend fun setSubtitleTextColor(color: Int) { store().edit { it[subtitleTextColorKey] = color } }
    suspend fun setSubtitleBackgroundColor(color: Int) { store().edit { it[subtitleBackgroundColorKey] = color } }
    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) { store().edit { it[subtitleOutlineEnabledKey] = enabled } }
    suspend fun setSubtitleOutlineColor(color: Int) { store().edit { it[subtitleOutlineColorKey] = color } }
    suspend fun setSubtitleOutlineWidth(width: Int) { store().edit { it[subtitleOutlineWidthKey] = width.coerceIn(1, 5) } }

    suspend fun setUseForcedSubtitles(enabled: Boolean) {
        store().edit { prefs ->
            prefs[subtitleUseForcedSubtitlesKey] = enabled
        }
    }

    suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        store().edit { prefs ->
            val currentStartupMode = parseAddonSubtitleStartupMode(prefs[addonSubtitleStartupModeKey])
            prefs[subtitleShowOnlyPreferredLanguagesKey] = enabled
            if (enabled) {
                if (currentStartupMode == AddonSubtitleStartupMode.ALL_SUBTITLES) {
                    prefs[addonSubtitleStartupModeKey] = AddonSubtitleStartupMode.PREFERRED_ONLY.name
                    prefs[addonSubtitleStartupModeAutoPreferredKey] = true
                } else {
                    prefs[addonSubtitleStartupModeAutoPreferredKey] = false
                }
            } else {
                val wasAutoPreferred = prefs[addonSubtitleStartupModeAutoPreferredKey] ?: false
                if (wasAutoPreferred && currentStartupMode == AddonSubtitleStartupMode.PREFERRED_ONLY) {
                    prefs[addonSubtitleStartupModeKey] = AddonSubtitleStartupMode.ALL_SUBTITLES.name
                }
                prefs[addonSubtitleStartupModeAutoPreferredKey] = false
            }
        }
    }

    // Buffer settings functions

    suspend fun setBufferMinBufferMs(ms: Int) {
        store().edit { prefs ->
            val isNativeMemory = isNativeMemoryActive(prefs)
            val maxLimit = if (isNativeMemory) 1_200_000 else 120_000
            val newMin = ms.coerceIn(5_000, maxLimit)
            prefs[minBufferMsKey] = newMin
            val currentMax = prefs[maxBufferMsKey] ?: BufferSettings.DEFAULT_MAX_BUFFER_MS
            if (currentMax < newMin) prefs[maxBufferMsKey] = newMin
        }
    }
    suspend fun setBufferMaxBufferMs(ms: Int) {
        store().edit { prefs ->
            val isNativeMemory = isNativeMemoryActive(prefs)
            val maxLimit = if (isNativeMemory) 1_200_000 else 120_000
            val currentMin = prefs[minBufferMsKey] ?: BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = ms.coerceIn(currentMin, maxLimit)
        }
    }
    suspend fun setBufferForPlaybackMs(ms: Int) { store().edit { it[bufferForPlaybackMsKey] = ms.coerceIn(1_000, 30_000) } }
    suspend fun setBufferForPlaybackAfterRebufferMs(ms: Int) { store().edit { it[bufferForPlaybackAfterRebufferMsKey] = ms.coerceIn(1_000, 60_000) } }
    suspend fun setBufferTargetSizeMb(mb: Int) { store().edit { it[targetBufferSizeMbKey] = mb.coerceAtLeast(0) } }
    suspend fun setBufferBackBufferDurationMs(ms: Int) {
        store().edit { prefs ->
            val isNativeMemory = isNativeMemoryActive(prefs)
            val maxLimit = if (isNativeMemory) 240_000 else 120_000
            prefs[backBufferDurationMsKey] = ms.coerceIn(0, maxLimit)
        }
    }
    suspend fun setBufferRetainBackBufferFromKeyframe(retain: Boolean) { store().edit { it[retainBackBufferFromKeyframeKey] = retain } }

    suspend fun resetBufferSettingsToDefaults() {
        store().edit { prefs ->
            prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
            prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
            prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
            prefs[bufferForPlaybackAfterRebufferMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
            prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
            prefs[retainBackBufferFromKeyframeKey] = false
            // VOD cache is grouped with the playback buffer section in the UI
            // (both extend seek-back smoothness), so reset its values here too.
            prefs[vodCacheEnabledKey] = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
            prefs[vodCacheSizeModeKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE.name
            prefs[vodCacheSizeMbKey] = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB
        }
    }

    suspend fun resetNetworkSettingsToDefaults() {
        store().edit { prefs ->
            prefs[useParallelConnectionsKey] = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
            prefs.remove(parallelConnectionCountKey)
            prefs[parallelChunkSizeMbKey] = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
            prefs[enableHttp2Key] = PlayerSettings.DEFAULT_ENABLE_HTTP2
        }
    }

    suspend fun setEnableHttp2(enabled: Boolean) {
        store().edit { it[enableHttp2Key] = enabled }
    }

    suspend fun setVodCacheEnabled(enabled: Boolean) { store().edit { it[vodCacheEnabledKey] = enabled } }
    suspend fun setVodCacheSizeMode(mode: VodCacheSizeMode) { store().edit { it[vodCacheSizeModeKey] = mode.name } }
    suspend fun setVodCacheSizeMb(mb: Int) { store().edit { it[vodCacheSizeMbKey] = mb.coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB) } }
    suspend fun setUseParallelConnections(enabled: Boolean) { store().edit { it[useParallelConnectionsKey] = enabled } }
    suspend fun setBufferEngineEnabled(enabled: Boolean) {
        store().edit { it[bufferEngineEnabledKey] = enabled }
    }

    suspend fun setParallelNetworkEnabled(enabled: Boolean) {
        store().edit { it[parallelNetworkEnabledKey] = enabled }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun setAllowLargeTargetBuffer(enabled: Boolean) {
        store().edit { prefs ->
            prefs[allowLargeTargetBufferKey] = enabled
            if (!enabled) {
                val isNativeMemory = isNativeMemoryActive(prefs)
                val safeLimitMb = if (isNativeMemory) {
                    NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
                } else {
                    val parallelNetworkEnabled = prefs[parallelNetworkEnabledKey] ?: false
                    val useParallelConnections = prefs[useParallelConnectionsKey] ?: false
                    val connectionCount = prefs[parallelConnectionCountKey] ?: 2
                    val chunkSizeMb = prefs[parallelChunkSizeMbKey] ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
                    val parallelOverheadMb = if (parallelNetworkEnabled && useParallelConnections) {
                        MemoryBudget.parallelOverheadMb(connectionCount, chunkSizeMb)
                    } else {
                        0
                    }
                    MemoryBudget.maxBufferMb(parallelOverheadMb)
                }
                val currentSize = prefs[targetBufferSizeMbKey] ?: BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
                if (currentSize > safeLimitMb) {
                    prefs[targetBufferSizeMbKey] = safeLimitMb
                }
            }
        }
    }
    suspend fun setBufferBudgetManaged(enabled: Boolean) {
        store().edit { it[bufferBudgetManagedKey] = enabled }
    }
    suspend fun setLastPlaybackDiagnostics(diagnostics: LastPlaybackDiagnostics) {
        store().edit { it[lastPlaybackDiagnosticsKey] = diagnostics.toJson() }
    }
    suspend fun setParallelConnectionCount(count: Int) {
        store().edit { prefs ->
            val isNativeMemory = isNativeMemoryActive(prefs)
            val maxCount = if (isNativeMemory) 16 else PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
            prefs[parallelConnectionCountKey] = count.coerceIn(PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT, maxCount)
        }
    }
    suspend fun setParallelChunkSizeMb(mb: Int) { store().edit { it[parallelChunkSizeMbKey] = mb.coerceIn(PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB, PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB) } }

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
                val isNativeMemory = isNativeMemoryActive(prefs)
                val maxCount = if (isNativeMemory) 16 else PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                prefs[parallelConnectionCountKey] = it.coerceIn(PlayerSettings.MIN_PARALLEL_CONNECTION_COUNT, maxCount)
            }
            parallelChunkSizeMb?.let { prefs[parallelChunkSizeMbKey] = it.coerceIn(PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_MB, PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_MB) }
        }
    }

    // Nuvio ExoPlayer Performance Mode

    val nuvioPerformanceModeEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            (prefs[nuvioPerformanceModeEnabledKey] ?: PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED) &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        }
    }

    suspend fun setNuvioPerformanceModeEnabled(enabled: Boolean) {
        val actualEnabled = enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        store().edit { prefs ->
            prefs[nuvioPerformanceModeEnabledKey] = actualEnabled
            if (actualEnabled) {
                val safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
                prefs[minBufferMsKey] = 200_000
                prefs[maxBufferMsKey] = 280_000
                prefs[bufferForPlaybackMsKey] = 1_500
                prefs[bufferForPlaybackAfterRebufferMsKey] = 1_500
                prefs[targetBufferSizeMbKey] = safeLimitMb
                prefs[backBufferDurationMsKey] = 12_000
                prefs[allowLargeTargetBufferKey] = true
                prefs[useParallelConnectionsKey] = true
                prefs[parallelConnectionCountKey] = 4
                prefs[parallelChunkSizeMbKey] = 16
            } else {
                prefs[minBufferMsKey] = BufferSettings.DEFAULT_MIN_BUFFER_MS
                prefs[maxBufferMsKey] = BufferSettings.DEFAULT_MAX_BUFFER_MS
                prefs[bufferForPlaybackMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                prefs[bufferForPlaybackAfterRebufferMsKey] = BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                prefs[targetBufferSizeMbKey] = BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
                prefs[backBufferDurationMsKey] = BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS
                prefs[allowLargeTargetBufferKey] = false
                prefs[useParallelConnectionsKey] = false
                prefs[parallelConnectionCountKey] = 2
                prefs[parallelChunkSizeMbKey] = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB
                prefs[bufferBudgetManagedKey] = true
            }
        }
    }

    private fun isNativeMemoryActive(prefs: androidx.datastore.preferences.core.Preferences): Boolean {
        val isEnabled = prefs[nuvioPerformanceModeEnabledKey] ?: PlayerSettings.DEFAULT_NUVIO_PERFORMANCE_MODE_ENABLED
        return isEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
    }
}
