package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STILL_WATCHING_COUNTDOWN_SECONDS = 60
private const val STILL_WATCHING_COUNTDOWN_TICK_MS = 1_000L

internal fun shouldEnterStillWatchingPrompt(
    stillWatchingEnabled: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    nextEpisodeHasAired: Boolean,
    consecutiveAutoPlayCount: Int,
    threshold: Int,
): Boolean = stillWatchingEnabled &&
    autoPlayNextEpisodeEnabled &&
    nextEpisodeHasAired &&
    consecutiveAutoPlayCount >= threshold

internal fun PlayerRuntimeController.enterStillWatchingPromptMode() {
    val nextInfo = _uiState.value.nextEpisode ?: return
    if (_uiState.value.postPlayMode is PostPlayMode.StillWatching && stillWatchingPromptJob?.isActive == true) {
        return
    }
    pauseForStillWatchingPrompt()
    stillWatchingPromptJob?.cancel()
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.StillWatching(
                nextEpisode = nextInfo,
                countdownSec = STILL_WATCHING_COUNTDOWN_SECONDS,
            ),
            showControls = false,
        )
    }
    val job = scope.launch {
        try {
            for (remaining in (STILL_WATCHING_COUNTDOWN_SECONDS - 1) downTo 0) {
                delay(STILL_WATCHING_COUNTDOWN_TICK_MS)
                _uiState.update { state ->
                    val current = state.postPlayMode as? PostPlayMode.StillWatching
                        ?: return@update state
                    state.copy(postPlayMode = current.copy(countdownSec = remaining))
                }
            }
            if (_uiState.value.postPlayMode is PostPlayMode.StillWatching) {
                exitFromStillWatching()
            }
        } finally {
            if (stillWatchingPromptJob === coroutineContext.job) {
                stillWatchingPromptJob = null
            }
        }
    }
    stillWatchingPromptJob = job
}

internal fun PlayerRuntimeController.onStillWatchingContinue() {
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    consecutiveAutoPlayCount = 0
    _uiState.update { it.copy(postPlayMode = null) }
    playNextEpisode(userInitiated = true)
}

internal fun PlayerRuntimeController.onDismissStillWatchingPrompt() {
    exitFromStillWatching()
}

internal fun PlayerRuntimeController.exitFromStillWatching() {
    consecutiveAutoPlayCount = 0
    resetPostPlayOverlayState(clearEpisode = true)
    _uiState.update { it.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt) }
}
