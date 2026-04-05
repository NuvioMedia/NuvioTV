package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EMBY_TAG = "EmbySession"
private const val EMBY_PROGRESS_INTERVAL_MS = 10_000L
private const val EMBY_INIT_RETRY_DELAY_MS = 2_000L
private const val EMBY_INIT_MAX_RETRIES = 5

private fun PlayerRuntimeController.tryResolveEmbyMetadata(): Boolean {
    val metadata = embyMediaService.getMetadataForStream(currentStreamUrl)
    if (metadata != null) {
        currentEmbyItemId = metadata.embyItemId
        currentEmbyMediaSourceId = metadata.mediaSourceId
        return true
    }
    val fallback = embyMediaService.findMetadataByItemUrlPattern(currentStreamUrl)
    if (fallback != null) {
        currentEmbyItemId = fallback.embyItemId
        currentEmbyMediaSourceId = fallback.mediaSourceId
        return true
    }
    return false
}

/**
 * Initialize Emby item ID and start session reporting.
 * If metadata is not yet available (map empty — Emby fetch still in progress),
 * retries up to [EMBY_INIT_MAX_RETRIES] times with [EMBY_INIT_RETRY_DELAY_MS] delay.
 */
internal fun PlayerRuntimeController.initEmbyItemIdAndStartSession() {
    embyInitJob?.cancel()

    if (tryResolveEmbyMetadata()) {
        startEmbySession()
        return
    }

    embyInitJob = scope.launch {
        for (attempt in 1..EMBY_INIT_MAX_RETRIES) {
            delay(EMBY_INIT_RETRY_DELAY_MS)
            if (!isActive) return@launch

            if (tryResolveEmbyMetadata()) {
                startEmbySession()
                return@launch
            }
        }
        currentEmbyItemId = null
        currentEmbyMediaSourceId = null
    }
}

internal fun PlayerRuntimeController.startEmbySession() {
    val itemId = currentEmbyItemId ?: return
    val mediaSourceId = currentEmbyMediaSourceId ?: return
    val positionMs = currentPlaybackPositionMs() ?: 0L

    scope.launch {
        try {
            embySessionService.reportStart(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                positionMs = positionMs
            )
        } catch (e: Exception) {
            Log.e(EMBY_TAG, "reportStart failed: ${e.message}", e)
        }
    }
    startEmbyProgressReporting()
}

internal fun PlayerRuntimeController.startEmbyProgressReporting() {
    embyProgressJob?.cancel()
    embyProgressJob = scope.launch {
        while (isActive) {
            delay(EMBY_PROGRESS_INTERVAL_MS)
            val isPlaying = isPlaybackCurrentlyPlaying()
            val positionMs = currentPlaybackPositionMs() ?: continue
            val isPaused = !isPlaying
            embySessionService.reportProgress(
                positionMs = positionMs,
                isPaused = isPaused
            )
        }
    }
}

internal fun PlayerRuntimeController.stopEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null
    embyInitJob?.cancel()
    embyInitJob = null
    val itemId = currentEmbyItemId ?: return
    val positionMs = currentPlaybackPositionMs() ?: 0L

    scope.launch {
        withContext(NonCancellable) {
            embySessionService.reportStop(positionMs = positionMs)
        }
    }
}

internal fun PlayerRuntimeController.resetEmbySession() {
    embyProgressJob?.cancel()
    embyProgressJob = null
    embyInitJob?.cancel()
    embyInitJob = null
    currentEmbyItemId = null
    currentEmbyMediaSourceId = null
    embySessionService.resetSession()
}
