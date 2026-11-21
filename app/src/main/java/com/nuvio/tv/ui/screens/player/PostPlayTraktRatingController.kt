package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.repository.TraktRatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the optional full-screen Trakt rating prompt shown when a movie finishes.
 * While it is visible, the post-play recommendation screen is held back via [isPromptVisible].
 */
internal class PostPlayTraktRatingController(
    private val playbackController: PlayerRuntimeController,
    private val traktRatingService: TraktRatingService,
    playerSettingsDataStore: PlayerSettingsDataStore,
    traktAuthDataStore: TraktAuthDataStore,
    traktSettingsDataStore: TraktSettingsDataStore,
    private val scope: CoroutineScope
) {
    companion object {
        private const val EXISTING_RATING_TIMEOUT_MS = 8_000L
    }

    private data class PromptSnapshot(
        val identity: String?,
        val shouldPrompt: Boolean,
        val hasError: Boolean,
        val hasBlockingInteraction: Boolean,
        val enabled: Boolean,
        val authenticated: Boolean
    )

    private val _uiState = MutableStateFlow(PostPlayTraktRatingUiState())
    val uiState: StateFlow<PostPlayTraktRatingUiState> = _uiState.asStateFlow()

    val isPromptVisible: Flow<Boolean> = _uiState.map { it.isVisible }.distinctUntilChanged()

    private var target: PostPlayTraktRatingTarget? = null
    private var hasPrompted = false
    private var promptGeneration = 0
    private var lastIdentity: String? = null
    private var userAdjusted = false
    private var existingRatingJob: Job? = null
    private var submitJob: Job? = null

    init {
        scope.launch {
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline,
                playerSettingsDataStore.playerSettings,
                traktSettingsDataStore.postPlayRatingPrompt,
                traktAuthDataStore.isEffectivelyAuthenticated
            ) { playerState, timeline, playerSettings, enabled, authenticated ->
                val durationMs = timeline.duration.takeIf { it > 0L } ?: playbackController.lastKnownDuration
                PromptSnapshot(
                    identity = playbackController.contentId?.let { "$it|${playerState.currentVideoId.orEmpty()}" },
                    shouldPrompt = shouldPromptPostPlayTraktRating(
                        contentType = playerState.contentType,
                        playbackEnded = playerState.playbackEnded,
                        positionMs = timeline.currentPosition,
                        durationMs = durationMs,
                        skipIntervals = playbackController.skipIntervals,
                        movieThresholdPercent = playerSettings.postPlayMovieThresholdPercent,
                        episodeThresholdMode = playbackController.nextEpisodeThresholdModeSetting,
                        episodeThresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
                        episodeThresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
                    ),
                    hasError = !playerState.error.isNullOrBlank(),
                    hasBlockingInteraction = playerState.blocksPostPlayRecommendation(),
                    enabled = enabled,
                    authenticated = authenticated
                )
            }
                .distinctUntilChanged()
                .collect(::evaluate)
        }
    }

    fun onStep(delta: Int) {
        val state = _uiState.value
        if (!state.isVisible) return
        val updated = state.withSelection(stepPostPlayTraktRating(state.selectedRating, delta))
        if (updated == state) return
        userAdjusted = true
        _uiState.value = updated
    }

    fun onSubmit() {
        val state = _uiState.value
        val ratingTarget = target ?: return
        if (!state.isVisible) return
        if (state.isSelectionSaved && state.status != PostPlayTraktRatingStatus.SAVING) {
            // The rating is already on Trakt: OK simply confirms it and moves on.
            dismiss()
            return
        }
        if (!state.canSubmit()) return
        val rating = state.selectedRating
        val generation = promptGeneration
        userAdjusted = true
        _uiState.update { it.copy(status = PostPlayTraktRatingStatus.SAVING) }
        submitJob?.cancel()
        submitJob = scope.launch {
            // Keep the write alive if the user leaves the player right after pressing OK.
            val saved = withContext(NonCancellable) {
                traktRatingService.rateMovie(
                    title = ratingTarget.title,
                    year = ratingTarget.year,
                    ids = ratingTarget.ids,
                    rating = rating
                )
            }
            if (promptGeneration != generation) return@launch
            if (!saved) {
                _uiState.update { it.copy(status = PostPlayTraktRatingStatus.FAILED) }
                return@launch
            }
            _uiState.update { it.copy(savedRating = rating, status = PostPlayTraktRatingStatus.SAVED) }
            delay(POST_PLAY_TRAKT_RATING_SAVED_DISMISS_MS)
            if (_uiState.value.status == PostPlayTraktRatingStatus.SAVED) dismiss()
        }
    }

    fun onSkip() {
        if (_uiState.value.status == PostPlayTraktRatingStatus.SAVING) return
        dismiss()
    }

    fun stop() {
        existingRatingJob?.cancel()
        existingRatingJob = null
        submitJob?.cancel()
        submitJob = null
        target = null
        hasPrompted = false
        promptGeneration++
        lastIdentity = null
        userAdjusted = false
        _uiState.value = PostPlayTraktRatingUiState()
    }

    private fun evaluate(snapshot: PromptSnapshot) {
        if (snapshot.identity != lastIdentity) {
            // A different title is playing in this player: allow a fresh prompt for it.
            if (lastIdentity != null) stop()
            lastIdentity = snapshot.identity
        }
        if (_uiState.value.isVisible) {
            if (!snapshot.enabled || !snapshot.authenticated || snapshot.hasError) dismiss()
            return
        }
        if (hasPrompted) return
        if (!snapshot.shouldPrompt || !snapshot.enabled || !snapshot.authenticated) return
        if (snapshot.hasError || snapshot.hasBlockingInteraction) return
        val resolved = playbackController.currentScrobbleItem?.toPostPlayTraktRatingTarget() ?: return
        show(resolved)
    }

    private fun show(resolved: PostPlayTraktRatingTarget) {
        target = resolved
        hasPrompted = true
        val generation = ++promptGeneration
        userAdjusted = false
        _uiState.value = PostPlayTraktRatingUiState(isVisible = true)
        existingRatingJob?.cancel()
        existingRatingJob = scope.launch {
            val existing = withTimeoutOrNull(EXISTING_RATING_TIMEOUT_MS) {
                traktRatingService.getMovieRating(resolved.ids)
            } ?: return@launch
            if (userAdjusted || promptGeneration != generation) return@launch
            _uiState.update { it.copy(selectedRating = existing, savedRating = existing) }
        }
    }

    private fun dismiss() {
        existingRatingJob?.cancel()
        existingRatingJob = null
        _uiState.update { it.copy(isVisible = false) }
    }
}
