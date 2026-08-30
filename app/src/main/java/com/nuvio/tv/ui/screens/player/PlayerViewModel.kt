package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.debrid.DirectDebridStreamPreparer
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tracking.TrackingScrobbleCoordinator
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.data.local.AudioDelayRouteDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.repository.ParentalGuideRepository
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.SkipIntroRepository
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val subtitleRepository: com.nuvio.tv.domain.repository.SubtitleRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val trackingScrobbleCoordinator: TrackingScrobbleCoordinator,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: com.nuvio.tv.data.local.BingeGroupCacheDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchedItemsPreferences: com.nuvio.tv.data.local.WatchedItemsPreferences,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trackPreferenceDataStore: com.nuvio.tv.data.local.TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackProgressStore: CloudLibraryPlaybackProgressStore,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation,
    private val playbackIssueReportRepository: com.nuvio.tv.data.repository.PlaybackIssueReportRepository,
    private val externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    private val subtitleFileCache: com.nuvio.tv.core.player.SubtitleFileCache,
    private val tvRecommendationManager: com.nuvio.tv.core.recommendations.TvRecommendationManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        // Release trailer player codec resources so the full-screen player can
        // claim hardware decoders without contention (prevents black screen).
        trailerPlayerPool.yield()
    }

    internal val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        pluginManager = pluginManager,
        subtitleRepository = subtitleRepository,
        parentalGuideRepository = parentalGuideRepository,
        trackingScrobbleCoordinator = trackingScrobbleCoordinator,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        directDebridResolver = directDebridResolver,
        directDebridStreamPreparer = directDebridStreamPreparer,
        cloudLibraryRepository = cloudLibraryRepository,
        cloudPlaybackProgressStore = cloudPlaybackProgressStore,
        cloudPlaybackSessionStore = cloudPlaybackSessionStore,
        streamBadgePresentation = streamBadgePresentation,
        playbackIssueReportRepository = playbackIssueReportRepository,
        tvRecommendationManager = tvRecommendationManager,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    private val postPlayRecommendationController = PostPlayRecommendationController(
        playbackController = controller,
        playerSettingsDataStore = playerSettingsDataStore,
        metaRepository = metaRepository,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        mdbListRepository = mdbListRepository,
        mdbListSettingsDataStore = mdbListSettingsDataStore,
        traktRelatedService = traktRelatedService,
        traktAuthDataStore = traktAuthDataStore,
        traktSettingsDataStore = traktSettingsDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchProgressRepository = watchProgressRepository,
        watchedSeriesStateHolder = watchedSeriesStateHolder,
        trailerService = trailerService,
        trailerSettingsDataStore = trailerSettingsDataStore,
        trailerPlayerPool = trailerPlayerPool,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val postPlayRecommendationUiState: StateFlow<PostPlayRecommendationUiState>
        get() = postPlayRecommendationController.uiState

    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun getCurrentFileSizeBytes(): Long? = controller.currentVideoSize

    fun stopAndRelease() {
        postPlayRecommendationController.stop()
        controller.stopAndRelease()
    }

    fun playPostPlayTrailer() {
        postPlayRecommendationController.playTrailer()
    }

    fun onPostPlayTrailerEnded() {
        postPlayRecommendationController.onTrailerEnded()
    }

    fun showPreviousPostPlayRecommendation() {
        postPlayRecommendationController.showPreviousRecommendation()
    }

    fun showNextPostPlayRecommendation() {
        postPlayRecommendationController.showNextRecommendation()
    }

    fun returnToPlayerFromPostPlay() {
        postPlayRecommendationController.returnToPlayer()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: NuvioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    fun bindExoSubtitleView(subtitleView: androidx.media3.ui.SubtitleView?) {
        controller.bindExoSubtitleView(subtitleView)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    override fun onCleared() {
        postPlayRecommendationController.stop()
        controller.onCleared()
        // Allow the trailer player to be re-created when returning to home screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Save watch progress returned by an external player after "Open in External Player".
     * Uses the controller's current content metadata (contentId, season, episode, etc.)
     * which are still available since the controller hasn't been cleared yet.
     */
    fun saveExternalPlayerProgress(positionMs: Long, durationMs: Long?) {
        val effectiveDuration = durationMs ?: controller.playbackTimeline.value.duration
        controller.saveWatchProgressInternal(
            position = positionMs,
            duration = effectiveDuration
        )
    }

    /**
     * Launch the current stream in an external player via the centralized tracker.
     *
     * Keep the ViewModel alive until the external intent has been handed to the launcher.
     * This lets the caller navigate away only after a successful handoff, while failures
     * remain visible on the current player screen (#2560).
     */
    fun launchInExternalPlayer(
        activityContext: Context,
        resumePositionMs: Long,
        onResult: (Boolean) -> Unit
    ) {
        val url = controller.getCurrentStreamUrl()
        if (url.isBlank()) {
            onResult(false)
            return
        }
        val contentId = controller.contentId
            ?: controller.cloudPlaybackContext?.item?.stableKey
            ?: run {
            onResult(false)
            return
        }
        val videoId = controller.currentVideoId ?: contentId
        val metadata = com.nuvio.tv.core.player.ExternalPlaybackMetadata(
            contentId = contentId,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = videoId,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year
        )
        val headers = controller.getCurrentHeaders()
        val nextEpisodeSnapshot = controller.metaVideos
            .takeIf { it.isNotEmpty() }
            ?.let { videos ->
                com.nuvio.tv.core.player.resolveExternalNextEpisodeSnapshot(
                    videos = videos,
                    currentSeason = metadata.season,
                    currentEpisode = metadata.episode
                )
            }

        // Capture already-loaded addon subtitles before handing off. Preparation stays in the
        // ViewModel scope because the player screen remains alive until the intent is sent.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    com.nuvio.tv.core.player.SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val cachedSubtitles = subtitleInputs?.let { inputs ->
                try {
                    withTimeoutOrNull(10_000L) {
                        subtitleFileCache.cacheSubtitles(inputs)
                    }
                } catch (_: Exception) {
                    // Subtitle forwarding is best-effort; the external launch must still proceed.
                    null
                }
            }

            // Stop the internal player only after preparation has completed and immediately
            // before sending the external intent.
            controller.stopAndRelease()
            val launched = try {
                externalPlaybackTracker.launchPlayer(
                    metadata = metadata,
                    url = url,
                    title = metadata.buildPlayerTitle(),
                    headers = headers,
                    resumePositionMs = resumePositionMs,
                    subtitles = cachedSubtitles,
                    nextEpisodeSnapshot = nextEpisodeSnapshot,
                    cloudSessionToken = controller.cloudSessionToken,
                    context = activityContext
                )
            } catch (_: Exception) {
                false
            }
            onResult(launched)
        }
    }
}
