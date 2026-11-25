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

    // If auto-play is enabled and the user dismissed the card earlier,
    // still auto-play the next episode when playback ends naturally.
    val state = _uiState.value
    val autoPlayEligible = streamAutoPlayNextEpisodeEnabledSetting &&
        state.nextEpisode?.hasAired == true &&
        nextEpisodeVideo != null
    if (autoPlayEligible && (state.postPlayDismissedForCurrentEpisode || state.postPlayMode == null)) {
        playNextEpisode()
        return
    }

    resetPostPlayOverlayState(clearEpisode = false)
}

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
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return

    val effectiveDuration = effectiveDurationEarly

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

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else {
        val ne = state.nextEpisode
        val isUnplayable = !ne.hasAired ||
            (ne.released.isNullOrBlank() && ne.available == false)
        if (isUnplayable) return

        _uiState.update {
            it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode))
        }
        if (streamAutoPlayNextEpisodeEnabledSetting) {
            playNextEpisode()
        }
    }
}
