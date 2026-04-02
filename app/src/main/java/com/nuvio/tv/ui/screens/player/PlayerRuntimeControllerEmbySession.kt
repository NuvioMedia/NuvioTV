package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val EMBY_TAG = "EmbySession"
private const val EMBY_PROGRESS_INTERVAL_MS = 10_000L

internal fun PlayerRuntimeController.initEmbyItemId() {
    val metadata = embyMediaService.getMetadataForStream(currentStreamUrl)
    if (metadata != null) {
        currentEmbyItemId = metadata.embyItemId
        currentEmbyMediaSourceId = metadata.mediaSourceId
        Log.d(EMBY_TAG, "Emby item resolved: ${metadata.embyItemId} for stream $currentStreamUrl")
    } else {
        currentEmbyItemId = null
        currentEmbyMediaSourceId = null
    }
}

internal fun PlayerRuntimeController.startEmbySession() {
    val itemId = currentEmbyItemId ?: return
    val mediaSourceId = currentEmbyMediaSourceId ?: return
    val positionMs = _exoPlayer?.currentPosition ?: 0L

    scope.launch {
        embySessionService.reportStart(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            positionMs = positionMs
        )
    }
    startEmbyProgressReporting()
}

internal fun PlayerRuntimeController.startEmbyProgressReporting() {
    embyProgressJob?.cancel()
    embyProgressJob = scope.launch {
        while (isActive) {
            delay(EMBY_PROGRESS_INTERVAL_MS)
            val player = _exoPlayer ?: break
            val isPaused = !player.isPlaying
            embySessionService.reportProgress(
                positionMs = player.currentPosition,
                isPaused = isPaused
            )
        }
    }
}

internal fun PlayerRuntimeController.stopEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null
    val itemId = currentEmbyItemId ?: return
    val positionMs = _exoPlayer?.currentPosition ?: 0L

    scope.launch {
        embySessionService.reportStop(positionMs = positionMs)
    }
}

internal fun PlayerRuntimeController.resetEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null
    currentEmbyItemId = null
    currentEmbyMediaSourceId = null
    embySessionService.resetSession()
}
