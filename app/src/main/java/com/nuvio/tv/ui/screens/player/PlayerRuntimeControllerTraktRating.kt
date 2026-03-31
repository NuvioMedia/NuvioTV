package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.TraktRatingItem
import com.nuvio.tv.data.repository.hasAnyId
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.toTraktIds
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TRAKT_RATING_PROMPT_THRESHOLD_PERCENT = 80f

internal fun PlayerRuntimeController.handlePlaybackEndedForTraktRating() {
    pendingCompletionAction = PlayerRuntimeController.PendingCompletionAction.ExitAfterPlaybackEnded
    scope.launch {
        if (!showTraktRatingDialogIfEligible()) {
            completePendingCompletionAction()
        }
    }
}

internal fun PlayerRuntimeController.handleExitRequestWithTraktRating() {
    pendingCompletionAction = PlayerRuntimeController.PendingCompletionAction.ExitPlayer
    if (currentPlaybackProgressPercent() < TRAKT_RATING_PROMPT_THRESHOLD_PERCENT) {
        completePendingCompletionAction()
        return
    }

    scope.launch {
        if (!showTraktRatingDialogIfEligible()) {
            completePendingCompletionAction()
        }
    }
}

internal fun PlayerRuntimeController.interceptEpisodeSwitchForTraktRating(
    stream: com.nuvio.tv.domain.model.Stream,
    forcedTargetVideo: com.nuvio.tv.domain.model.Video?
): Boolean {
    if (currentPlaybackProgressPercent() < TRAKT_RATING_PROMPT_THRESHOLD_PERCENT) {
        return false
    }

    pendingCompletionAction = PlayerRuntimeController.PendingCompletionAction.SwitchToEpisodeStream(
        stream = stream,
        forcedTargetVideo = forcedTargetVideo
    )
    scope.launch {
        if (!showTraktRatingDialogIfEligible()) {
            completePendingCompletionAction()
        }
    }
    return true
}

internal suspend fun PlayerRuntimeController.showTraktRatingDialogIfEligible(): Boolean {
    if (_uiState.value.showTraktRatingDialog) {
        return true
    }

    val item = buildCurrentTraktRatingItem() ?: run {
        pendingTraktRatingItem = null
        return false
    }
    if (!traktRatingService.canPromptForRating(item)) {
        pendingTraktRatingItem = null
        return false
    }

    val defaultRating = traktRatingService.getDefaultRating()
    val existingRating = traktRatingService.getExistingRating(item)
    _exoPlayer?.pause()
    pendingTraktRatingItem = item
    _uiState.update {
        it.copy(
            showTraktRatingDialog = true,
            selectedTraktRating = existingRating ?: defaultRating,
            existingTraktRating = existingRating,
            traktRatingSubmitting = false,
            traktRatingError = null,
            playbackCompletionReadyToExit = false,
            exitPlayerReady = false,
            showControls = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showSourcesPanel = false,
            showEpisodesPanel = false
        )
    }
    return true
}

internal fun PlayerRuntimeController.submitTraktRating(ratingOverride: Int? = null) {
    val item = pendingTraktRatingItem ?: run {
        dismissTraktRatingDialog()
        return
    }
    val rating = (ratingOverride ?: _uiState.value.selectedTraktRating).coerceIn(1, 10)

    scope.launch {
        _uiState.update { it.copy(traktRatingSubmitting = true, traktRatingError = null) }
        val result = traktRatingService.submitRating(item = item, rating = rating)
        result
            .onSuccess {
                _uiState.update {
                    it.copy(
                        showTraktRatingDialog = false,
                        existingTraktRating = rating,
                        traktRatingSubmitting = false,
                        traktRatingError = null
                    )
                }
                pendingTraktRatingItem = null
                if (pendingCompletionAction != null) {
                    completePendingCompletionAction()
                }
            }
            .onFailure {
                _uiState.update {
                    it.copy(
                        traktRatingSubmitting = false,
                        traktRatingError = "Unable to save rating to Trakt."
                    )
                }
            }
    }
}

internal fun PlayerRuntimeController.dismissTraktRatingDialog() {
    _uiState.update {
        it.copy(
            showTraktRatingDialog = false,
            existingTraktRating = null,
            traktRatingSubmitting = false,
            traktRatingError = null
        )
    }
    pendingTraktRatingItem = null
    if (pendingCompletionAction != null) {
        completePendingCompletionAction()
    }
}

internal fun PlayerRuntimeController.completePendingCompletionAction() {
    when (val action = pendingCompletionAction ?: return) {
        PlayerRuntimeController.PendingCompletionAction.ExitAfterPlaybackEnded -> {
            pendingCompletionAction = null
            _uiState.update {
                it.copy(
                    playbackCompletionReadyToExit = true,
                    exitPlayerReady = false
                )
            }
        }

        PlayerRuntimeController.PendingCompletionAction.ExitPlayer -> {
            pendingCompletionAction = null
            _uiState.update {
                it.copy(
                    playbackCompletionReadyToExit = false,
                    exitPlayerReady = true
                )
            }
        }

        is PlayerRuntimeController.PendingCompletionAction.SwitchToEpisodeStream -> {
            pendingCompletionAction = null
            _uiState.update {
                it.copy(
                    playbackCompletionReadyToExit = false,
                    exitPlayerReady = false
                )
            }
            performSwitchToEpisodeStream(
                stream = action.stream,
                forcedTargetVideo = action.forcedTargetVideo
            )
        }
    }
}

private fun PlayerRuntimeController.buildCurrentTraktRatingItem(): TraktRatingItem? {
    val normalizedType = contentType?.lowercase()
    val currentMappingKey = currentEpisodeMappingCacheKey()
    val mappedEpisode = if (currentTraktEpisodeMappingKey == currentMappingKey) {
        currentTraktEpisodeMapping
    } else {
        null
    }

    val isEpisode = normalizedType in listOf("series", "tv") &&
        currentSeason != null &&
        currentEpisode != null

    return if (isEpisode) {
        val resolvedEpisode = mappedEpisode ?: return null
        val episodeIds = resolvedEpisode.ids?.takeIf { it.trakt != null || it.tvdb != null } ?: return null
        TraktRatingItem.Episode(
            ids = episodeIds,
            showTitle = contentName ?: title,
            season = resolvedEpisode.season,
            number = resolvedEpisode.episode,
            episodeTitle = currentEpisodeTitle
        )
    } else {
        val ids = sequenceOf(contentId, currentVideoId)
            .map { toTraktIds(parseContentIds(it)) }
            .firstOrNull { it.hasAnyId() }
            ?: return null
        TraktRatingItem.Movie(
            ids = ids,
            title = contentName ?: title
        )
    }
}
