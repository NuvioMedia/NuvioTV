package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.SkipInterval
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
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    internal val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val TRACK_FRAME_RATE_GRACE_MS = 1500L
        internal const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        internal const val MAX_TIMEOUT_RECOVERY_ATTEMPTS = 2
        internal const val ADDON_SUBTITLE_TRACK_ID_PREFIX = "nuvio-addon-sub:"
        internal val PORTUGUESE_BRAZILIAN_TAGS = listOf(
            "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil"
        )
        internal val PORTUGUESE_EUROPEAN_TAGS = listOf(
            "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu"
        )
    }

    internal data class PendingAudioSelection(
        val language: String?,
        val name: String?,
        val streamUrl: String
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
    internal val rememberedAudioLanguage: String? = navigationArgs.rememberedAudioLanguage
    internal val rememberedAudioName: String? = navigationArgs.rememberedAudioName
    internal val mediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)

    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    internal var currentStreamUrl: String = initialStreamUrl
    internal var currentHeaders: Map<String, String> =
        PlayerMediaSourceFactory.sanitizeHeaders(PlayerMediaSourceFactory.parseHeaders(headersJson))

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
            currentEpisodeTitle = currentEpisodeTitle
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    internal var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer?
        get() = _exoPlayer

    internal var progressJob: Job? = null
    internal var vodTelemetryJob: Job? = null
    internal var firstFrameWatchdogJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var frameRateProbeJob: Job? = null
    internal var frameRateProbeToken: Long = 0L
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
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
    internal var attachedAddonSubtitleKeys: Set<String> = emptySet()
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var streamReuseLastLinkEnabled: Boolean = false
    internal var streamAutoPlayModeSetting: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = 98f
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasAppliedRememberedAudioSelection: Boolean = false

    internal var lastBufferLogTimeMs: Long = 0L
    internal var lastVodTelemetryRefreshTimeMs: Long = 0L
    internal var cachedVodCacheLogState: String = "vod=warming"
    internal var bufferLogsEnabled: Boolean = false
    internal var lastProgressUiUpdateUptimeMs: Long = 0L
    internal var lastSkipIntervalEvaluationUptimeMs: Long = 0L
    internal var lastNextEpisodeEvaluationUptimeMs: Long = 0L
    internal var bufferLogJob: Job? = null
    
    internal var loudnessEnhancer: LoudnessEnhancer? = null
    internal var trackSelector: DefaultTrackSelector? = null
    internal var currentMediaSession: MediaSession? = null
    internal var pauseOverlayJob: Job? = null
    internal val pauseOverlayDelayMs = 5000L
    internal val seekProgressSyncDebounceMs = 700L
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    internal var hasRetriedCurrentStreamAfterUnexpectedNpe: Boolean = false
    internal var hasRetriedCurrentStreamAfterMediaPeriodHolderCrash: Boolean = false
    internal var timeoutRecoveryAttempts: Int = 0
    internal val dv7ToHevcForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1SoftwarePreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1TrackSelectionBypassStreamUrls: MutableSet<String> = mutableSetOf()
    internal val safeAudioForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val audioDisabledForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal var isMapDv7ToHevcActiveForCurrentPlayback: Boolean = false
    internal var isExperimentalDv7ToDv81ActiveForCurrentPlayback: Boolean = false
    internal var isVc1SoftwareFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1TrackSelectionBypassActiveForCurrentPlayback: Boolean = false
    internal var isSafeAudioModeActiveForCurrentPlayback: Boolean = false
    internal var isAudioDisabledForCurrentPlayback: Boolean = false
    internal var hasAttemptedDv7ToDv81ForCurrentPlayback: Boolean = false
    internal var dv7ToDv81BridgeVersionForCurrentPlayback: String? = null
    internal var dv7ToDv81LastProbeReasonForCurrentPlayback: String? = null
    internal var playerInitializationStartedAtMs: Long = 0L
    internal var pendingSeekTelemetryRequestedAtMs: Long = 0L
    internal var pendingSeekTelemetryTargetMs: Long = -1L
    internal var pendingSeekTelemetryReadyAtMs: Long = 0L
    internal var pendingSeekTelemetryReadyLatencyMs: Long = -1L
    internal var pendingSeekTelemetryAwaitingFirstFrame: Boolean = false
    internal var pendingSeekTelemetryReadyAssumed: Boolean = false
    internal var currentScrobbleItem: TraktScrobbleItem? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasRequestedScrobbleStartForCurrentItem: Boolean = false
    internal var scrobbleStartRequestGeneration: Long = 0L
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false
    internal var currentStreamHasVideoTrack: Boolean = false
    internal var currentVideoTrackIsLikelyVc1: Boolean = false
    internal var currentVideoTrackMimeType: String? = null
    internal var currentVideoTrackCodecs: String? = null
    internal var currentVideoTrackWidth: Int = 0
    internal var currentVideoTrackHeight: Int = 0
    internal var currentVideoTrackSelected: Boolean = false
    internal var currentVideoTrackBestSupport: Int = C.FORMAT_UNSUPPORTED_TYPE
    internal var lastLoggedVideoTrackSignature: String? = null
    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String? by lazy {
        val type = contentType?.lowercase()
        val vid = currentVideoId
        if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
    }

    init {
        refreshScrobbleItem()
        mediaSourceFactory.warmupVodCacheAsync()
        if (!navigationArgs.startFromBeginning) {
            loadSavedProgressFor(currentSeason, currentEpisode)
        }
        fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
        observeSubtitleSettings()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
    }
    

    fun onCleared() {
        releasePlayer()
        vodTelemetryJob?.cancel()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }
}
