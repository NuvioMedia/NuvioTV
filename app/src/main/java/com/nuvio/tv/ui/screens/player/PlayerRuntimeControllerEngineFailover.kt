package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (!allowEngineFailover) return false
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (startupEngineFailoverTriggered) return false
    if (!isStartupPhaseForEngineFailover()) return false

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
    }
    Log.w(PlayerRuntimeController.TAG, "Startup playback error; auto-switching engine: $detailedError")
    startupEngineFailoverTriggered = true

    val targetEngineLabel = when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
    }
    val switchMessage = context.getString(R.string.player_engine_switching_message, targetEngineLabel)

    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage
        )
    }

    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = switchingToMpv
    delayMpvResumeSeekUntilVideoTrack = switchingToMpv
    releasePlayer(flushPlaybackState = false)
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = targetEngine,
        allowEngineFailover = false
    )
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }

    return true
}

private fun PlayerRuntimeController.isStartupPhaseForEngineFailover(): Boolean {
    val state = _uiState.value
    return !hasRenderedFirstFrame && (state.showLoadingOverlay || state.isBuffering || state.currentPosition <= 0L)
}
