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
import com.nuvio.tv.ui.util.languageCodeToName

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
    val minBufferMs: Int = 50_000,
    val maxBufferMs: Int = 50_000,
    val bufferForPlaybackMs: Int = 2_500,
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
    val targetBufferSizeMb: Int = 0, // 0 = ExoPlayer default
    val backBufferDurationMs: Int = 0,
    val retainBackBufferFromKeyframe: Boolean = false
)

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
    val audioAmplificationDb: Int = 0,
    val persistAudioAmplification: Boolean = false,
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val loadingOverlayEnabled: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    // Dolby Vision Profile 7 → HEVC fallback (requires forked ExoPlayer)
    val mapDV7ToHevc: Boolean = false,
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
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val subtitleOrganizationMode: SubtitleOrganizationMode = SubtitleOrganizationMode.NONE,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    val resizeMode: Int = 0 
)

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
        private const val AUDIO_AMPLIFICATION_DB_MIN = 0
        private const val AUDIO_AMPLIFICATION_DB_MAX = 10
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
    private val audioAmplificationDbKey = intPreferencesKey("audio_amplification_db")
    private val persistAudioAmplificationKey = booleanPreferencesKey("persist_audio_amplification")
    private val preferredAudioLanguageKey = stringPreferencesKey("preferred_audio_language")
    private val secondaryPreferredAudioLanguageKey = stringPreferencesKey("secondary_preferred_audio_language")
    private val loadingOverlayEnabledKey = booleanPreferencesKey("loading_overlay_enabled")
    private val pauseOverlayEnabledKey = booleanPreferencesKey("pause_overlay_enabled")
    private val osdClockEnabledKey = booleanPreferencesKey("osd_clock_enabled")
    private val skipIntroEnabledKey = booleanPreferencesKey("skip_intro_enabled")
    private val mapDV7ToHevcKey = booleanPreferencesKey("map_dv7_to_hevc")
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
    private val streamAutoPlayTimeoutSecondsKey = intPreferencesKey("stream_auto_play_timeout_seconds")
    private val nextEpisodeThresholdModeKey = stringPreferencesKey("next_episode_threshold_mode")
    private val nextEpisodeThresholdPercentLegacyKey = intPreferencesKey("next_episode_threshold_percent")
    private val nextEpisodeThresholdMinutesBeforeEndLegacyKey = intPreferencesKey("next_episode_threshold_minutes_before_end")
    private val nextEpisodeThresholdPercentKey = floatPreferencesKey("next_episode_threshold_percent_v2")
    private val nextEpisodeThresholdMinutesBeforeEndKey = floatPreferencesKey("next_episode_threshold_minutes_before_end_v2")
    private val streamReuseLastLinkEnabledKey = booleanPreferencesKey("stream_reuse_last_link_enabled")
    private val streamReuseLastLinkCacheHoursKey = intPreferencesKey("stream_reuse_last_link_cache_hours")
    private val subtitleOrganizationModeKey = stringPreferencesKey("subtitle_organization_mode")
    private val addonSubtitleStartupModeKey = stringPreferencesKey("addon_subtitle_startup_mode")
    private val resizeModeKey = intPreferencesKey("resize_mode")

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
                        prefs[minBufferMsKey] = 50_000
                        prefs[maxBufferMsKey] = 50_000
                    }

                    prefs[migrationLoadControlDefaultsAlignedDoneKey] = true
                }

                val min = prefs[minBufferMsKey]
                val max = prefs[maxBufferMsKey]
                if (min != null && max != null && max < min) {
                    prefs[maxBufferMsKey] = min
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
                audioAmplificationDb = (prefs[audioAmplificationDbKey] ?: 0).coerceIn(
                    AUDIO_AMPLIFICATION_DB_MIN,
                    AUDIO_AMPLIFICATION_DB_MAX
                ),
                persistAudioAmplification = prefs[persistAudioAmplificationKey] ?: false,
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
                streamAutoPlayTimeoutSeconds = (prefs[streamAutoPlayTimeoutSecondsKey] ?: 3).coerceIn(0, 11),
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
                addonSubtitleStartupMode = parseAddonSubtitleStartupMode(prefs[addonSubtitleStartupModeKey]),
                resizeMode = (prefs[resizeModeKey] ?: 0).coerceIn(0, 4),
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
                    minBufferMs = prefs[minBufferMsKey] ?: 50_000,
                    maxBufferMs = prefs[maxBufferMsKey] ?: 50_000,
                    bufferForPlaybackMs = prefs[bufferForPlaybackMsKey] ?: 2_500,
                    bufferForPlaybackAfterRebufferMs = prefs[bufferForPlaybackAfterRebufferMsKey] ?: 5_000,
                    targetBufferSizeMb = prefs[targetBufferSizeMbKey] ?: 0,
                    backBufferDurationMs = prefs[backBufferDurationMsKey] ?: 0,
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

    suspend fun setAudioAmplificationDb(db: Int) {
        store().edit { prefs ->
            prefs[audioAmplificationDbKey] = db.coerceIn(
                AUDIO_AMPLIFICATION_DB_MIN,
                AUDIO_AMPLIFICATION_DB_MAX
            )
        }
    }

    suspend fun setPersistAudioAmplification(enabled: Boolean, dbToPersist: Int? = null) {
        store().edit { prefs ->
            prefs[persistAudioAmplificationKey] = enabled
            if (enabled && dbToPersist != null) {
                prefs[audioAmplificationDbKey] = dbToPersist.coerceIn(
                    AUDIO_AMPLIFICATION_DB_MIN,
                    AUDIO_AMPLIFICATION_DB_MAX
                )
            }
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

    suspend fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[streamAutoPlayTimeoutSecondsKey] = seconds.coerceIn(0, 11)
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

    suspend fun setResizeMode(mode: Int) {
        store().edit { prefs ->
            prefs[resizeModeKey] = mode.coerceIn(0, 4)
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
            val currentMax = prefs[maxBufferMsKey] ?: 50_000
            if (currentMax < newMin) {
                prefs[maxBufferMsKey] = newMin
            }
        }
    }

    suspend fun setBufferMaxBufferMs(ms: Int) {
        store().edit { prefs ->
            val currentMin = prefs[minBufferMsKey] ?: 50_000
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
}
