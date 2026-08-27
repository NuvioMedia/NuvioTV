package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PostPlayRecommendationController(
    private val playbackController: PlayerRuntimeController,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val scope: CoroutineScope
) {
    private data class PlaybackIdentity(
        val contentType: String?,
        val contentId: String?,
        val videoId: String?,
        val season: Int?,
        val episode: Int?
    )

    private data class PlaybackSnapshot(
        val identity: PlaybackIdentity,
        val contentType: String?,
        val postPlayRecommendationsEnabled: Boolean,
        val isNextEpisodeMetadataResolved: Boolean,
        val nextEpisodeHasAired: Boolean?,
        val hasError: Boolean,
        val hasBlockingInteraction: Boolean,
        val playbackEnded: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private data class ResolvedCandidate(
        val recommendation: PostPlayRecommendation,
        val meta: Meta?
    )

    private data class RatingPreferences(
        val isMdbListActive: Boolean,
        val showStandardRatings: Boolean
    )

    private val _uiState = MutableStateFlow(PostPlayRecommendationUiState())
    val uiState: StateFlow<PostPlayRecommendationUiState> = _uiState.asStateFlow()

    private var recommendationJob: Job? = null
    private var recommendationSelectionJob: Job? = null
    private var recommendationPrefetchJob: Job? = null
    private var postEndCountdownJob: Job? = null
    private var returnToPlayerAnimationJob: Job? = null
    private var recommendationCandidates = emptyList<MetaPreview>()
    private var ratingPreferences: RatingPreferences? = null
    private val candidateResolutionJobs = mutableMapOf<Int, Deferred<ResolvedCandidate?>>()
    private val recommendationDetailJobs = mutableMapOf<Int, Job>()
    private val recommendationCache = mutableMapOf<Int, PostPlayRecommendation>()
    private var recommendationLoadAttempted = false
    private val postPlayTrailerPlaybackEnabled = AppFeaturePolicy.inAppTrailerPlaybackEnabled
    private var autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
    private var lastSnapshot: PlaybackSnapshot? = null
    private var lastPlaybackIdentity: PlaybackIdentity? = null

    init {
        scope.launch {
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline,
                playerSettingsDataStore.playerSettings
            ) { playerState, timeline, playerSettings ->
                PlaybackSnapshot(
                    identity = PlaybackIdentity(
                        contentType = playerState.contentType?.trim()?.lowercase(),
                        contentId = playbackController.contentId,
                        videoId = playerState.currentVideoId,
                        season = playerState.currentSeason,
                        episode = playerState.currentEpisode
                    ),
                    contentType = playerState.contentType,
                    postPlayRecommendationsEnabled = playerSettings.postPlayRecommendationsEnabled,
                    isNextEpisodeMetadataResolved = playerState.isNextEpisodeMetadataResolved,
                    nextEpisodeHasAired = playerState.nextEpisode?.hasAired,
                    hasError = !playerState.error.isNullOrBlank(),
                    hasBlockingInteraction = playerState.blocksPostPlayRecommendation(),
                    playbackEnded = playerState.playbackEnded,
                    positionMs = timeline.currentPosition,
                    durationMs = timeline.duration
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (lastPlaybackIdentity?.let { it != snapshot.identity } == true) {
                        clearRecommendationState()
                    }
                    lastPlaybackIdentity = snapshot.identity
                    lastSnapshot = snapshot
                    evaluate(snapshot)
                }
        }
    }

    fun playTrailer() {
        startTrailer()
    }

    fun onTrailerEnded() {
        trailerPlayerPool.stop()
        _uiState.update {
            it.copy(
                countdownSeconds = null,
                isTrailerPlaying = false,
                hasAutoPlayedTrailer = true
            )
        }
    }

    fun showPreviousRecommendation() {
        selectRecommendation(-1)
    }

    fun showNextRecommendation() {
        selectRecommendation(1)
    }

    fun returnToPlayer() {
        val state = _uiState.value
        val returnedState = state.returnToPlayer()
        if (returnedState == state) return
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        _uiState.value = returnedState
        returnToPlayerAnimationJob = scope.launch {
            delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong())
            _uiState.value = PostPlayRecommendationUiState(hasReturnedToPlayer = true)
            returnToPlayerAnimationJob = null
        }
    }

    fun stop() {
        clearRecommendationState()
        lastSnapshot = null
        lastPlaybackIdentity = null
    }

    private fun clearRecommendationState() {
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        returnToPlayerAnimationJob = null
        recommendationLoadAttempted = false
        autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
        if (_uiState.value.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        _uiState.value = PostPlayRecommendationUiState()
    }

    private fun clearRecommendationPipeline() {
        recommendationSelectionJob?.cancel()
        recommendationSelectionJob = null
        recommendationPrefetchJob?.cancel()
        recommendationPrefetchJob = null
        candidateResolutionJobs.values.forEach { it.cancel() }
        candidateResolutionJobs.clear()
        recommendationDetailJobs.values.forEach { it.cancel() }
        recommendationDetailJobs.clear()
        recommendationCandidates = emptyList()
        recommendationCache.clear()
        ratingPreferences = null
    }

    private fun evaluate(snapshot: PlaybackSnapshot) {
        val shouldUseRecommendation = shouldUsePostPlayRecommendation(
            contentType = snapshot.contentType,
            isNextEpisodeMetadataResolved = snapshot.isNextEpisodeMetadataResolved,
            nextEpisodeHasAired = snapshot.nextEpisodeHasAired,
            enabled = snapshot.postPlayRecommendationsEnabled
        )
        if (!shouldUseRecommendation || snapshot.hasError) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation ||
                recommendationJob != null
            ) {
                clearRecommendationState()
            }
            return
        }

        if (_uiState.value.hasReturnedToPlayer) return

        val effectiveDuration = snapshot.durationMs
            .takeIf { it > 0L }
            ?: playbackController.lastKnownDuration
        if (isShortPlaceholderDuration(effectiveDuration)) return

        if (!recommendationLoadAttempted &&
            shouldPrefetchPostPlayRecommendation(snapshot.positionMs, effectiveDuration)
        ) {
            loadRecommendation()
        }

        var state = _uiState.value
        val recommendation = state.recommendation ?: return
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = snapshot.positionMs,
            durationMs = effectiveDuration,
            skipIntervals = playbackController.skipIntervals,
            thresholdMode = playbackController.nextEpisodeThresholdModeSetting,
            thresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
        ) || snapshot.playbackEnded

        if (!shouldShow) return
        if (!state.isVisible && snapshot.hasBlockingInteraction) return

        if (!state.isVisible) {
            val needsPostEndCountdown = snapshot.playbackEnded &&
                recommendation.hasTrailer &&
                autoPlayTrailerEnabled
            _uiState.update {
                it.copy(
                    isVisible = true,
                    countdownSeconds = if (needsPostEndCountdown) {
                        POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS
                    } else {
                        postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
                    }
                )
            }
            state = _uiState.value
            if (needsPostEndCountdown) {
                startPostEndCountdown()
                return
            }
        }

        if (state.isTrailerPlaying || state.hasAutoPlayedTrailer || !recommendation.hasTrailer) return
        if (!autoPlayTrailerEnabled) {
            if (state.countdownSeconds != null) {
                _uiState.update { it.copy(countdownSeconds = null) }
            }
            return
        }

        if (snapshot.playbackEnded) {
            if (state.countdownSeconds != null) {
                startTrailer()
            } else {
                startPostEndCountdown()
            }
            return
        }

        val countdown = postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
        if (countdown != state.countdownSeconds) {
            _uiState.update { it.copy(countdownSeconds = countdown) }
        }
    }

    private fun loadRecommendation() {
        recommendationLoadAttempted = true
        recommendationJob = scope.launch {
            _uiState.update { it.copy(isLoadingRecommendation = true) }
            val candidates = try {
                loadCurrentMeta()?.let { loadCandidates(it) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (candidates.isEmpty()) {
                _uiState.update { it.copy(isLoadingRecommendation = false) }
                recommendationJob = null
                return@launch
            }

            recommendationCandidates = candidates
            val preferences = loadRatingPreferences()
            ratingPreferences = preferences
            autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled && runCatching {
                trailerSettingsDataStore.settings.first().enabled
            }.getOrDefault(true)
            candidates.indices.forEach(::startCandidateResolution)
            val resolvedCandidate = awaitCandidateResolution(0)
            if (resolvedCandidate == null) {
                clearRecommendationPipeline()
                _uiState.update { it.copy(isLoadingRecommendation = false) }
                recommendationJob = null
                return@launch
            }
            val recommendation = cacheRecommendation(0, resolvedCandidate, preferences)
            _uiState.update {
                it.copy(
                    recommendation = recommendation,
                    recommendationIndex = 0,
                    recommendationCount = candidates.size,
                    isLoadingRecommendation = false,
                    isLoadingTrailer = postPlayTrailerPlaybackEnabled
                )
            }
            lastSnapshot?.let(::evaluate)
            prefetchRecommendationDetails(preferences)
            recommendationJob = null
        }
    }

    private fun prefetchRecommendationDetails(preferences: RatingPreferences) {
        recommendationPrefetchJob?.cancel()
        recommendationPrefetchJob = scope.launch {
            recommendationCandidates.indices.forEach { index ->
                launch {
                    val resolvedCandidate = awaitCandidateResolution(index) ?: return@launch
                    cacheRecommendation(index, resolvedCandidate, preferences)
                    loadRecommendationDetails(index, resolvedCandidate, preferences)
                }
            }
        }
    }

    private fun startCandidateResolution(index: Int) {
        if (index !in recommendationCandidates.indices || candidateResolutionJobs.containsKey(index)) return
        val candidate = recommendationCandidates[index]
        candidateResolutionJobs[index] = scope.async {
            try {
                resolveCandidate(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun awaitCandidateResolution(index: Int): ResolvedCandidate? {
        startCandidateResolution(index)
        return candidateResolutionJobs[index]?.await()
    }

    private fun cacheRecommendation(
        index: Int,
        resolvedCandidate: ResolvedCandidate,
        preferences: RatingPreferences
    ): PostPlayRecommendation {
        return recommendationCache.getOrPut(index) {
            resolvedCandidate.recommendation.copy(
                showStandardRatings = preferences.showStandardRatings
            )
        }
    }

    private fun selectRecommendation(offset: Int) {
        val state = _uiState.value
        if (!state.isVisible || state.isChangingRecommendation) return
        val targetIndex = state.recommendationIndex + offset
        if (targetIndex !in recommendationCandidates.indices) return

        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        if (state.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        autoPlayTrailerEnabled = false
        _uiState.update {
            it.copy(
                isChangingRecommendation = true,
                countdownSeconds = null,
                isTrailerPlaying = false
            )
        }
        recommendationSelectionJob?.cancel()
        recommendationSelectionJob = scope.launch {
            try {
                val resolvedCandidate = awaitCandidateResolution(targetIndex)
                val preferences = ratingPreferences
                if (resolvedCandidate == null || preferences == null) {
                    _uiState.update { it.copy(isChangingRecommendation = false) }
                    return@launch
                }
                val recommendation = cacheRecommendation(targetIndex, resolvedCandidate, preferences)
                _uiState.update {
                    it.copy(
                        recommendation = recommendation,
                        recommendationIndex = targetIndex,
                        isChangingRecommendation = false,
                        isLoadingTrailer = postPlayTrailerPlaybackEnabled &&
                            recommendationDetailJobs[targetIndex]?.isCompleted != true
                    )
                }
                loadRecommendationDetails(targetIndex, resolvedCandidate, preferences)
            } finally {
                recommendationSelectionJob = null
            }
        }
    }

    private fun loadRecommendationDetails(
        index: Int,
        resolvedCandidate: ResolvedCandidate,
        preferences: RatingPreferences
    ) {
        if (index !in recommendationCandidates.indices || recommendationDetailJobs.containsKey(index)) return
        val candidate = recommendationCandidates[index]
        recommendationDetailJobs[index] = scope.launch {
            _uiState.update { state ->
                if (state.recommendationIndex == index) {
                    state.copy(isLoadingTrailer = postPlayTrailerPlaybackEnabled)
                } else {
                    state
                }
            }
            val ratingsJob = launch {
                val ratings = loadRatings(
                    candidate = candidate,
                    meta = resolvedCandidate.meta,
                    enabled = preferences.isMdbListActive
                )
                updateCachedRecommendation(index) {
                    it.copy(mdbListRatings = ratings)
                }
            }

            val trailerJob = launch {
                if (!postPlayTrailerPlaybackEnabled) return@launch
                val recommendation = recommendationCache[index] ?: return@launch
                val trailerSource = try {
                    withTimeoutOrNull(15_000L) {
                        trailerService.getTrailerPlaybackSource(
                            title = recommendation.title,
                            year = recommendation.releaseInfo,
                            tmdbId = recommendation.tmdbId,
                            type = recommendation.contentType,
                            ignoreUseTrailersGate = true
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                updateCachedRecommendation(index) {
                    it.copy(
                        trailerVideoUrl = trailerSource?.videoUrl,
                        trailerAudioUrl = trailerSource?.audioUrl
                    )
                }
                _uiState.update { state ->
                    if (state.recommendationIndex == index) state.copy(isLoadingTrailer = false) else state
                }
                if (_uiState.value.recommendationIndex == index) {
                    lastSnapshot?.let(::evaluate)
                }
            }

            ratingsJob.join()
            trailerJob.join()
            _uiState.update { state ->
                if (state.recommendationIndex == index) state.copy(isLoadingTrailer = false) else state
            }
        }
    }

    private fun updateCachedRecommendation(
        index: Int,
        transform: (PostPlayRecommendation) -> PostPlayRecommendation
    ) {
        val recommendation = recommendationCache[index]?.let(transform) ?: return
        recommendationCache[index] = recommendation
        _uiState.update { state ->
            if (state.recommendationIndex == index) {
                state.copy(recommendation = recommendation)
            } else {
                state
            }
        }
    }

    private suspend fun loadRatingPreferences(): RatingPreferences {
        val settings = mdbListSettingsDataStore.settings.first()
        val isMdbListActive = settings.enabled && settings.apiKey.isNotBlank()
        val visibility = layoutPreferenceDataStore.homeImdbRatingsVisibility.first()
        return RatingPreferences(
            isMdbListActive = isMdbListActive,
            showStandardRatings = visibility.showStandardDetailRatings(isMdbListActive)
        )
    }

    private suspend fun loadRatings(
        candidate: MetaPreview,
        meta: Meta?,
        enabled: Boolean
    ) = if (!enabled || meta == null) {
        null
    } else {
        try {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = candidate.id,
                fallbackItemType = candidate.apiType
            )?.ratings
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadCurrentMeta(): Meta? {
        val id = playbackController.contentId ?: return null
        val type = playbackController.contentType ?: return null
        metaRepository.getCachedMeta(type, id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                    .first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private suspend fun loadCandidates(meta: Meta): List<MetaPreview> {
        val tmdbContentType = resolvePostPlayContentType(
            apiType = playbackController.contentType,
            fallback = meta.type
        ) ?: return emptyList()
        val candidates = withTimeoutOrNull(10_000L) {
            val sourcePreference = traktSettingsDataStore.moreLikeThisSource.first()
            val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
            if (sourcePreference == MoreLikeThisSourcePreference.TRAKT && traktAuthenticated) {
                runCatching {
                    traktRelatedService.getRelated(
                        meta = meta,
                        fallbackItemId = playbackController.contentId,
                        fallbackItemType = playbackController.contentType
                    )
                }.getOrDefault(emptyList())
            } else {
                val settings = tmdbSettingsDataStore.settings.first()
                if (!settings.enabled || !settings.useMoreLikeThis) return@withTimeoutOrNull emptyList()
                val lookupType = tmdbContentType.toApiString(playbackController.contentType)
                val tmdbId = tmdbService.ensureTmdbId(meta.id, lookupType)
                    ?: playbackController.contentId?.let { tmdbService.ensureTmdbId(it, lookupType) }
                    ?: return@withTimeoutOrNull emptyList()
                runCatching {
                    tmdbMetadataService.fetchMoreLikeThis(
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
                        language = settings.language
                    )
                }.getOrDefault(emptyList())
            }
        }.orEmpty()

        val hideUnreleased = layoutPreferenceDataStore.hideUnreleasedContent.first()
        val watchedIds = combine(
            watchProgressRepository.observeWatchedMovieIds(),
            watchedSeriesStateHolder.fullyWatchedSeriesIds
        ) { movieIds, seriesIds -> movieIds to seriesIds }.first()
        val currentIds = setOfNotNull(meta.id.normalizedId(), playbackController.contentId?.normalizedId())
        val filtered = candidates
            .asSequence()
            .filterNot { it.id.normalizedId() in currentIds }
            .filterNot { candidate ->
                isPostPlayCandidateWatched(
                    candidate = candidate,
                    watchedMovieIds = watchedIds.first,
                    watchedSeriesIds = watchedIds.second
                )
            }
            .filterNot { hideUnreleased && it.isUnreleased(LocalDate.now()) }
            .distinctBy { it.apiType.normalizedId() to it.id.normalizedId() }
            .toList()
        val first = filtered.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: filtered.firstOrNull()
            ?: return emptyList()
        return buildList {
            add(first)
            filtered.asSequence()
                .filterNot { it === first }
                .take(MAX_POST_PLAY_RECOMMENDATIONS - 1)
                .forEach(::add)
        }
    }

    private suspend fun resolveCandidate(candidate: MetaPreview): ResolvedCandidate {
        val settings = tmdbSettingsDataStore.settings.first()
        val meta = try {
            loadCandidateMeta(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val candidateContentType = resolvePostPlayContentType(
            apiType = meta?.apiType ?: candidate.apiType,
            fallback = meta?.type ?: candidate.type
        )
        val tmdbId = try {
            tmdbService.ensureTmdbId(
                videoId = meta?.id ?: candidate.id,
                mediaType = meta?.apiType ?: candidate.apiType
            ) ?: if (meta?.id != candidate.id) {
                tmdbService.ensureTmdbId(candidate.id, candidate.apiType)
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val enrichment = if (settings.enabled && tmdbId != null && candidateContentType != null) {
            try {
                withTimeoutOrNull(12_000L) {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = candidateContentType,
                        language = settings.language
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return ResolvedCandidate(
            recommendation = resolvePostPlayRecommendation(
                candidate = candidate,
                meta = meta,
                enrichment = enrichment,
                settings = settings,
                tmdbId = tmdbId
            ),
            meta = meta
        )
    }

    private suspend fun loadCandidateMeta(candidate: MetaPreview): Meta? {
        metaRepository.getCachedMeta(candidate.apiType, candidate.id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(
                    type = candidate.apiType,
                    id = candidate.id,
                    sourceAddonBaseUrl = candidate.sourceAddonBaseUrl
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private fun startPostEndCountdown() {
        val state = _uiState.value
        if (!postPlayTrailerPlaybackEnabled ||
            postEndCountdownJob?.isActive == true ||
            state.isTrailerPlaying ||
            state.hasAutoPlayedTrailer ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob = scope.launch {
            for (seconds in POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS downTo 1) {
                _uiState.update { it.copy(countdownSeconds = seconds) }
                delay(1_000L)
            }
            startTrailer()
        }
    }

    private fun startTrailer() {
        val state = _uiState.value
        if (!postPlayTrailerPlaybackEnabled ||
            state.isTrailerPlaying ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        playbackController.releasePlayer()
        trailerPlayerPool.reclaim()
        _uiState.update {
            it.copy(
                isVisible = true,
                countdownSeconds = null,
                isTrailerPlaying = true,
                hasAutoPlayedTrailer = true
            )
        }
    }
}

private const val MAX_POST_PLAY_RECOMMENDATIONS = 4

private fun String.normalizedId(): String = trim().lowercase()
