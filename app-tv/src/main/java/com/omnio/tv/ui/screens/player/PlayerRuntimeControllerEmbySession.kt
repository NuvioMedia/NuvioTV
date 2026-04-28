package com.omnio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val EMBY_PROVIDER = "emby"
private const val EMBY_TAG = "EmbySession"
private const val EMBY_PROGRESS_INTERVAL_MS = 10_000L

internal fun PlayerRuntimeController.startEmbySessionIfNeeded() {
    if (!isCurrentStreamFromEmbyProvider()) {
        resetEmbySession()
        return
    }

    val itemId = currentProviderItemId ?: return
    val mediaSourceId = currentProviderMediaSourceId ?: return
    val positionMs = currentPlaybackPositionMs() ?: 0L

    scope.launch {
        try {
            embySessionService.reportStart(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                positionMs = positionMs
            )
        } catch (error: Exception) {
            Log.e(EMBY_TAG, "reportStart failed: ${error.message}", error)
        }
    }

    startEmbyProgressReporting()
}

internal fun PlayerRuntimeController.startEmbyProgressReporting() {
    embyProgressJob?.cancel()
    embyProgressJob = scope.launch {
        while (isActive) {
            delay(EMBY_PROGRESS_INTERVAL_MS)
            if (!isCurrentStreamFromEmbyProvider()) continue
            if (!hasRenderedFirstFrame) continue

            val isPlaying = isPlaybackCurrentlyPlaying()
            val positionMs = currentPlaybackPositionMs() ?: continue
            embySessionService.reportProgress(
                positionMs = positionMs,
                isPaused = !isPlaying
            )
        }
    }
}

internal fun PlayerRuntimeController.reportEmbyPausedProgressNowIfNeeded() {
    if (!isCurrentStreamFromEmbyProvider()) return
    if (!hasRenderedFirstFrame) return

    val positionMs = currentPlaybackPositionMs() ?: return
    scope.launch {
        embySessionService.reportProgress(
            positionMs = positionMs,
            isPaused = true,
            force = true
        )
    }
}

internal fun PlayerRuntimeController.stopEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null

    if (!isCurrentStreamFromEmbyProvider()) {
        embySessionService.resetSession()
        return
    }

    val positionMs = currentPlaybackPositionMs() ?: 0L
    embySessionService.reportStop(positionMs)
}

internal fun PlayerRuntimeController.resetEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null
    embySessionService.resetSession()
}

private fun PlayerRuntimeController.isCurrentStreamFromEmbyProvider(): Boolean {
    return currentStreamProvider.equals(EMBY_PROVIDER, ignoreCase = true) &&
        !currentProviderItemId.isNullOrBlank() &&
        !currentProviderMediaSourceId.isNullOrBlank()
}
