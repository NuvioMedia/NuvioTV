package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }

    // If auto-play is enabled and the user dismissed the card earlier, or the card was still
    // waiting for the auto-play point, auto-play the next episode when playback ends naturally.
    val state = _uiState.value
    if (shouldAutoPlayNextEpisodeOnPlaybackEnded(
            state = state,
            autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
            hasNextEpisodeVideo = nextEpisodeVideo != null
        )
    ) {
        val cardWasWaiting = state.postPlayMode is PostPlayMode.AutoPlay &&
            !state.postPlayDismissedForCurrentEpisode
        val stillWatchingDue = cardWasWaiting && shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = stillWatchingEnabledSetting,
            autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
            nextEpisodeHasAired = state.nextEpisode?.hasAired == true,
            consecutiveAutoPlayCount = consecutiveAutoPlayCount,
            threshold = stillWatchingEpisodeThresholdSetting,
        )
        if (stillWatchingDue) enterStillWatchingPromptMode() else playNextEpisode()
        return
    }

    resetPostPlayOverlayState(clearEpisode = false)
}

/** Call only after [shouldResetPostPlayStateAfterPlaybackEnded] returned true. */
internal fun shouldAutoPlayNextEpisodeOnPlaybackEnded(
    state: PlayerUiState,
    autoPlayNextEpisodeEnabled: Boolean,
    hasNextEpisodeVideo: Boolean
): Boolean {
    if (!autoPlayNextEpisodeEnabled || !hasNextEpisodeVideo) return false
    if (state.nextEpisode?.hasAired != true) return false
    val mode = state.postPlayMode
    return state.postPlayDismissedForCurrentEpisode ||
        mode == null ||
        (mode is PostPlayMode.AutoPlay && !mode.blocksNaturalCompletion())
}

/** True for a shown card that is not yet searching or counting down. */
internal fun isNextEpisodeCardWaitingForAutoPlay(state: PlayerUiState): Boolean =
    (state.postPlayMode as? PostPlayMode.AutoPlay)?.takeIf { !it.blocksNaturalCompletion() } != null

/** A rewind out of the next-episode window hides a card that is still waiting. */
internal fun shouldHideWaitingNextEpisodeCard(state: PlayerUiState, awayFromEnd: Boolean): Boolean =
    awayFromEnd && isNextEpisodeCardWaitingForAutoPlay(state)

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    if (_playbackTimeline.value.isLive) return
    if (!hasRenderedFirstFrame) return
    // Short debrid/error clips must never arm next-episode auto-play (see #2819).
    // Prefer the largest known duration; the per-poll value can drop transiently.
    val effectiveDurationEarly = maxOf(durationMs, lastKnownDuration)
    if (isShortPlaceholderDuration(effectiveDurationEarly)) return
    // Act only after this stream has reported a position away from its end.
    if (!endDetectionArmed) {
        if (!PlayerNextEpisodeRules.isAwayFromEnd(
                positionMs = positionMs,
                durationMs = effectiveDurationEarly,
                skipIntervals = skipIntervals,
                thresholdMode = nextEpisodeThresholdModeSetting,
                thresholdPercent = nextEpisodeThresholdPercentSetting,
                thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
            )
        ) return
        endDetectionArmed = true
    }
    if (!_uiState.value.error.isNullOrBlank()) return
    // The end of a disc extra is not the episode's.
    if (isPlayingDiscExtra()) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayDismissedForCurrentEpisode) return
    // A card that is shown but not yet searching or counting down is still waiting for the
    // auto-play point, so keep evaluating it.
    val cardWaitingForAutoPlay = isNextEpisodeCardWaitingForAutoPlay(state)
    if (state.postPlayMode != null && !cardWaitingForAutoPlay) return

    val effectiveDuration = effectiveDurationEarly

    if (shouldHideWaitingNextEpisodeCard(
            state = state,
            awayFromEnd = PlayerNextEpisodeRules.isAwayFromEnd(
                positionMs = positionMs,
                durationMs = effectiveDuration,
                skipIntervals = skipIntervals,
                thresholdMode = nextEpisodeThresholdModeSetting,
                thresholdPercent = nextEpisodeThresholdPercentSetting,
                thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
            )
        )
    ) {
        _uiState.update { it.copy(postPlayMode = null) }
        return
    }

    // Preload: start fetching sources for next episode before the button appears.
    if (preloadNextEpisodeSourcesSetting && !nextEpisodePreloadTriggered && state.nextEpisode != null) {
        val preloadLeadMs = maxOf(streamAutoPlayTimeoutSecondsSetting.toLong() * 1_000L, 10_000L)
            .coerceAtMost(30_000L)
        val shouldPreloadByCard = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs + preloadLeadMs,
            durationMs = effectiveDuration,
            skipIntervals = skipIntervals,
            thresholdMode = nextEpisodeThresholdModeSetting,
            thresholdPercent = nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
        )
        val shouldPreloadByEnd = effectiveDuration > 0L &&
            (effectiveDuration - positionMs) <= preloadLeadMs
        if (shouldPreloadByCard || shouldPreloadByEnd) {
            preloadNextEpisodeSources()
        }
    }

    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (!shouldShow) return

    if (_uiState.value.postPlayDismissedForCurrentEpisode) return

    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    val autoAdvanceDue = streamAutoPlayNextEpisodeEnabledSetting &&
        PlayerNextEpisodeRules.shouldAutoAdvanceToNextEpisode(
            positionMs = positionMs,
            durationMs = effectiveDuration,
            skipIntervals = skipIntervals,
            thresholdMode = nextEpisodeThresholdModeSetting,
            thresholdPercent = nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
        )

    // The still-watching prompt pauses playback, so it waits for the auto-play point too.
    if (shouldEnterStillWatching && autoAdvanceDue) {
        enterStillWatchingPromptMode()
        return
    }

    val ne = state.nextEpisode
    val isUnplayable = !ne.hasAired ||
        (ne.released.isNullOrBlank() && ne.available == false)
    if (isUnplayable) return

    if (!cardWaitingForAutoPlay) {
        _uiState.update {
            it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode))
        }
    }
    if (autoAdvanceDue) {
        playNextEpisode()
    }
}
