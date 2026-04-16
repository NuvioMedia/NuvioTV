package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class PlayerRuntimeController(
    internal val context: Context,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    internal val streamRepository: StreamRepository,
    internal val addonRepository: AddonRepository,
    internal val pluginManager: PluginManager,
    internal val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    internal val parentalGuideRepository: ParentalGuideRepository,
    internal val traktScrobbleService: TraktScrobbleService,
    internal val traktEpisodeMappingService: TraktEpisodeMappingService,
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    internal val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    internal val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    internal val torrentService: TorrentService,
    internal val torrentSettings: com.nuvio.tv.core.torrent.TorrentSettings,
    internal val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    internal val tmdbMetadataService: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    internal val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val SWITCH_TRACE_TAG = "SwitchTrace"
        internal const val SWITCH_TRACE_ENABLED = false
        internal const val TRACK_FRAME_RATE_GRACE_MS = 1500L
        internal const val ADDON_SUBTITLE_TRACK_ID_PREFIX = "nuvio-addon-sub:"
    }

    internal data class PendingAudioSelection(
        val language: String?,
        val name: String?,
        val streamUrl: String
    )

    internal data class RememberedTrackSelection(
        val language: String?,
        val name: String?,
        val trackId: String? = null,
        val indexHint: Int? = null,
        val languageIndexHint: Int? = null,
        val isForcedHint: Boolean? = null
    )

    internal sealed class RememberedSubtitleSelection {
        data object Disabled : RememberedSubtitleSelection()
        data class Internal(
            val track: RememberedTrackSelection
        ) : RememberedSubtitleSelection()
        data class Addon(
            val id: String,
            val url: String,
            val language: String,
            val addonName: String
        ) : RememberedSubtitleSelection()
    }

    internal data class TrackPreference(
        val audio: RememberedTrackSelection? = null,
        val subtitle: RememberedSubtitleSelection? = null
    )

    internal data class PendingEngineSwitchTrackPreference(
        val streamUrl: String,
        val preference: TrackPreference,
        val sourceEngine: InternalPlayerEngine
    )

    internal data class ExplicitSubtitleSelectionForEngineSwitch(
        val streamUrl: String,
        val selection: RememberedSubtitleSelection
    )

    internal val navigationArgs = PlayerNavigationArgs.from(savedStateHandle)
    internal val initialStreamUrl: String = navigationArgs.streamUrl
    internal val title: String = navigationArgs.title
    internal val streamName: String? = navigationArgs.streamName
    internal val year: String? = navigationArgs.year
    internal val headersJson: String? = navigationArgs.headersJson
    internal val contentId: String? = navigationArgs.contentId
    internal val contentType: String? = navigationArgs.contentType
    internal val contentName: String? = navigationArgs.contentName
    internal val poster: String? = navigationArgs.poster
    internal val backdrop: String? = navigationArgs.backdrop
    internal val logo: String? = navigationArgs.logo
    internal val videoId: String? = navigationArgs.videoId
    internal val initialSeason: Int? = navigationArgs.initialSeason
    internal val initialEpisode: Int? = navigationArgs.initialEpisode
    internal val initialEpisodeTitle: String? = navigationArgs.initialEpisodeTitle
    internal val mediaSourceFactory = PlayerMediaSourceFactory()

    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    internal var currentAddonName: String? = navigationArgs.addonName
    internal var currentAddonLogo: String? = navigationArgs.addonLogo
    internal var currentStreamDescription: String? = navigationArgs.streamDescription
    internal val contentLanguage: String? = navigationArgs.contentLanguage
    internal var currentVideoCodec: String? = null
    internal var currentVideoWidth: Int? = null
    internal var currentVideoHeight: Int? = null
    internal var currentVideoBitrate: Int? = null
    internal var currentStreamUrl: String
    internal var currentStreamResponseHeaders: Map<String, String> = emptyMap()
    internal var currentStreamMimeType: String?
    internal var currentHeaders: Map<String, String>

    init {
        val (cleanInitialUrl, mergedInitialHeaders) = PlayerMediaSourceFactory.extractUserInfoAuth(
            initialStreamUrl,
            PlayerMediaSourceFactory.sanitizeHeaders(PlayerMediaSourceFactory.parseHeaders(headersJson))
        )
        currentStreamUrl = cleanInitialUrl
        currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
            url = cleanInitialUrl,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders
        )
        currentHeaders = mergedInitialHeaders
    }

    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentHeaders(): Map<String, String> = currentHeaders

    fun stopAndRelease() {
        releasePlayer()
    }

    internal var currentVideoId: String? = videoId
    internal var currentSeason: Int? = initialSeason
    internal var currentEpisode: Int? = initialEpisode
    internal var currentEpisodeTitle: String? = initialEpisodeTitle

    internal val _uiState = MutableStateFlow(
        PlayerUiState(
            title = title,
            contentName = contentName,
            currentStreamName = streamName,
            currentStreamUrl = currentStreamUrl,
            releaseYear = year,
            contentType = contentType,
            backdrop = backdrop,
            logo = logo,
            showLoadingOverlay = true,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    internal val _playbackTimeline = MutableStateFlow(PlaybackTimelineState())
    val playbackTimeline: StateFlow<PlaybackTimelineState> = _playbackTimeline.asStateFlow()

    internal fun updatePlaybackTimeline(
        currentPosition: Long = _playbackTimeline.value.currentPosition,
        duration: Long = _playbackTimeline.value.duration
    ) {
        _playbackTimeline.update {
            it.copy(
                currentPosition = currentPosition.coerceAtLeast(0L),
                duration = duration.coerceAtLeast(0L)
            )
        }
    }

    internal fun resetPlaybackTimeline() {
        _playbackTimeline.value = PlaybackTimelineState()
    }

    internal var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer?
        get() = _exoPlayer
    internal var playbackSpeedAwareAudioOutputProvider: PlaybackSpeedAwareAudioOutputProvider? = null

    internal var progressJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var frameRateProbeJob: Job? = null
    internal var frameRateProbeToken: Long = 0L
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hidePlayerEngineSwitchInfoJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
    internal var subtitleAutoSyncLoadJob: Job? = null
    internal var nextEpisodeAutoPlayJob: Job? = null
    internal var sourceStreamsJob: Job? = null
    internal var sourceChipErrorDismissJob: Job? = null
    internal var sourceStreamsCacheRequestKey: String? = null
    internal var hostActivityRef: WeakReference<Activity>? = null
    internal var initialPlaybackStarted: Boolean = false
    
    
    internal var lastSavedPosition: Long = 0L
    internal val saveThresholdMs = 5000L 
    internal var lastKnownDuration: Long = 0L

    
    internal var playbackStartedForParentalGuide = false
    internal var hasRenderedFirstFrame = false
    internal var shouldEnforceAutoplayOnFirstReady = true
    internal var metaVideos: List<Video> = emptyList()
    internal var nextEpisodeVideo: Video? = null
    internal var userPausedManually = false

    
    internal var skipIntervals: List<SkipInterval> = emptyList()
    internal var skipIntroEnabled: Boolean = true
    internal var skipIntroFetchedKey: String? = null
    internal var lastActiveSkipType: String? = null
    internal var autoSubtitleSelected: Boolean = false
    internal var lastSubtitlePreferredLanguage: String? = null
    internal var lastSubtitleSecondaryLanguage: String? = null
    internal var pendingAddonSubtitleLanguage: String? = null
    internal var pendingAddonSubtitleTrackId: String? = null
    internal var pendingAudioSelectionAfterSubtitleRefresh: PendingAudioSelection? = null
    internal var rememberedTrackPreference: TrackPreference? = null
    internal var persistedTrackPreference: TrackPreference? = null
    internal var pendingEngineSwitchTrackPreference: PendingEngineSwitchTrackPreference? = null
    internal var explicitSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null
    internal var effectiveSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null
    internal var switchTraceSessionId: Long = 0L
    internal var switchTraceSequence: Long = 0L
    internal var subtitleDisabledByPersistedPreference: Boolean = false
    internal var subtitleAddonRestoredByPersistedPreference: Boolean = false
    internal var pendingRestoredAddonSubtitle: com.nuvio.tv.domain.model.Subtitle? = null
    internal var attachedAddonSubtitleKeys: Set<String> = emptySet()
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var streamReuseLastLinkEnabled: Boolean = false
    internal var autoSwitchInternalPlayerOnErrorEnabled: Boolean = false
    internal var startupEngineFailoverTriggered: Boolean = false
    internal var runtimeInternalPlayerEngineOverride: InternalPlayerEngine? = null
    internal var currentInternalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER
    internal var streamAutoPlayModeSetting: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var streamAutoPlayPreferBingeGroupForNextEpisodeSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = 98f
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var mpvHardwareDecodeModeSetting: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    internal var mpvPreferredAudioLanguages: List<String> = emptyList()
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasInitializedAudioAmplificationForSession: Boolean = false

    internal var lastBufferLogTimeMs: Long = 0L
    
    internal val gainAudioProcessor = GainAudioProcessor()
    internal var trackSelector: DefaultTrackSelector? = null
    internal var currentMediaSession: MediaSession? = null
    internal var mpvView: NuvioMpvSurfaceView? = null
    internal var mpvInitializationInProgress: Boolean = false
    internal var mpvTrackRefreshInProgress: Boolean = false
    internal var pendingMpvHardRestartOnNextAttach: Boolean = false
    internal var delayMpvResumeSeekUntilVideoTrack: Boolean = false
    internal var mpvDelayStartAfterAfrSwitch: Boolean = false
    internal var pauseOverlayJob: Job? = null
    internal val pauseOverlayDelayMs = 5000L
    internal val seekProgressSyncDebounceMs = 700L
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    internal var isReleasingPlayer: Boolean = false
    internal var cachedDecoderPriority: Int = 1
    internal var hasTriedAudioPcmFallback: Boolean = false
    internal var hasTriedDv7HevcFallback: Boolean = false
    internal var forceDv7ToHevc: Boolean = false
    internal var startupRetryCount: Int = 0
    internal var errorRetryCount: Int = 0
    internal var errorRetryJob: Job? = null
    internal var currentScrobbleItem: TraktScrobbleItem? = null
    internal var currentTraktEpisodeMapping: EpisodeMappingEntry? = null
    internal var currentTraktEpisodeMappingKey: String? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasRequestedScrobbleStartForCurrentItem: Boolean = false
    internal var scrobbleStartRequestGeneration: Long = 0L
    internal var playbackPreparationJob: Job? = null
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false
    internal var requestedUseLibassByUser: Boolean = false
    internal var libassPipelineOverrideForCurrentStream: Boolean? = null
    internal var activePlayerUsesLibass: Boolean = false
    internal var libassPipelineSwitchInFlight: Boolean = false
    internal var hasDetectedAssSsaTrackForCurrentStream: Boolean = false
    internal var libassPipelineDecisionStreamUrl: String? = null
    internal var torrentStreamJob: Job? = null
    internal var torrentStateObserverJob: Job? = null
    internal var isTorrentStream: Boolean = navigationArgs.infoHash != null
    internal var currentInfoHash: String? = navigationArgs.infoHash
    internal var currentFileIdx: Int? = navigationArgs.fileIdx
    internal var currentTorrentSources: List<String>? =
        navigationArgs.sourcesJson?.let { raw ->
            runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { s -> s.isNotEmpty() }
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String?
        get() {
            val type = contentType?.lowercase()
            val vid = currentVideoId
            return if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
        }

    init {
        if (!navigationArgs.startFromBeginning) {
            loadSavedProgressFor(currentSeason, currentEpisode)
        }
        fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
        observeSubtitleSettings()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
        observeTorrentSettings()
    }

    private fun observeTorrentSettings() {
        scope.launch {
            torrentSettings.settings.collect { settings ->
                _uiState.update { it.copy(hideTorrentStats = settings.hideTorrentStats) }
            }
        }
    }
    

    fun onCleared() {
        releasePlayer()
        stopTorrentStream()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }
}

internal fun PlayerRuntimeController.beginSwitchTraceSession(
    reason: String,
    targetEngine: InternalPlayerEngine?
) {
    switchTraceSessionId = System.currentTimeMillis()
    switchTraceSequence = 0L
    logSwitchTrace(
        stage = "session-begin",
        message = "reason=$reason sourceEngine=$currentInternalPlayerEngine targetEngine=$targetEngine"
    )
}

internal fun PlayerRuntimeController.logSwitchTrace(
    stage: String,
    message: String
) {
    if (!PlayerRuntimeController.SWITCH_TRACE_ENABLED) return
    if (switchTraceSessionId == 0L) {
        switchTraceSessionId = System.currentTimeMillis()
        switchTraceSequence = 0L
    }
    val sequence = ++switchTraceSequence
    val streamToken = currentStreamUrl.hashCode().toUInt().toString(16)
    Log.w(
        PlayerRuntimeController.SWITCH_TRACE_TAG,
        "sid=$switchTraceSessionId seq=$sequence stage=$stage engine=$currentInternalPlayerEngine streamToken=$streamToken $message"
    )
}
