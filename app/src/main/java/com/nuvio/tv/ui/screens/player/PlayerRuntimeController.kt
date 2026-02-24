package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.lifecycle.SavedStateHandle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
import java.util.concurrent.atomic.AtomicLong

class PlayerRuntimeController(
    internal val context: Context,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    internal val streamRepository: StreamRepository,
    internal val addonRepository: AddonRepository,
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
    internal val mediaSourceFactory = PlayerMediaSourceFactory()

    internal var currentStreamUrl: String = initialStreamUrl
    internal var currentHeaders: Map<String, String> = PlayerMediaSourceFactory.parseHeaders(headersJson)

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
    internal var sourceStreamsCacheRequestKey: String? = null
    
    
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
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var streamReuseLastLinkEnabled: Boolean = false
    internal var streamAutoPlayModeSetting: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var streamAutoPlayPreferBingeGroupForNextEpisodeSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = 98f
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasAppliedRememberedAudioSelection: Boolean = false

    internal var lastBufferLogTimeMs: Long = 0L
    
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
    internal var currentScrobbleItem: TraktScrobbleItem? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false
    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String? by lazy {
        val type = contentType?.lowercase()
        val vid = currentVideoId
        if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
    }

    init {
        refreshScrobbleItem()
        initializePlayer(currentStreamUrl, currentHeaders)
        loadSavedProgressFor(currentSeason, currentEpisode)
        fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
        observeSubtitleSettings()
        fetchAddonSubtitles()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
    }
    

    fun onCleared() {
        releasePlayer()
        mediaSourceFactory.shutdown()
    }
}
