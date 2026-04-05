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
        Log.d(EMBY_TAG, "Emby item resolved: ${metadata.embyItemId} for stream $currentStreamUrl")
        return true
    }
    val fallback = embyMediaService.findMetadataByItemUrlPattern(currentStreamUrl)
    if (fallback != null) {
        currentEmbyItemId = fallback.embyItemId
        currentEmbyMediaSourceId = fallback.mediaSourceId
        Log.d(EMBY_TAG, "Emby item resolved via fallback: ${fallback.embyItemId} for stream $currentStreamUrl")
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

    val mapSize = embyMediaService.metadataMapSize()
    Log.d(EMBY_TAG, "Emby metadata not available yet (map has $mapSize entries), will retry up to $EMBY_INIT_MAX_RETRIES times")

    embyInitJob = scope.launch {
        for (attempt in 1..EMBY_INIT_MAX_RETRIES) {
            delay(EMBY_INIT_RETRY_DELAY_MS)
            if (!isActive) return@launch

            if (tryResolveEmbyMetadata()) {
                Log.d(EMBY_TAG, "Emby metadata resolved on retry #$attempt")
                startEmbySession()
                return@launch
            }
            Log.d(EMBY_TAG, "Emby init retry #$attempt — still no metadata (map has ${embyMediaService.metadataMapSize()} entries)")
        }
        Log.d(EMBY_TAG, "Emby init gave up after $EMBY_INIT_MAX_RETRIES retries — not an Emby stream or fetch failed")
        currentEmbyItemId = null
        currentEmbyMediaSourceId = null
    }
}

internal fun PlayerRuntimeController.startEmbySession() {
    val itemId = currentEmbyItemId ?: run {
        Log.w(EMBY_TAG, "startEmbySession() — currentEmbyItemId is null, aborting")
        return
    }
    val mediaSourceId = currentEmbyMediaSourceId ?: run {
        Log.w(EMBY_TAG, "startEmbySession() — currentEmbyMediaSourceId is null, aborting")
        return
    }
    val positionMs = currentPlaybackPositionMs() ?: 0L
    Log.d(EMBY_TAG, "startEmbySession() — itemId=$itemId, mediaSourceId=$mediaSourceId, positionMs=$positionMs")

    scope.launch {
        try {
            embySessionService.reportStart(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                positionMs = positionMs
            )
            Log.d(EMBY_TAG, "startEmbySession() — reportStart completed")
        } catch (e: Exception) {
            Log.e(EMBY_TAG, "startEmbySession() — reportStart failed: ${e.message}", e)
        }
    }
    startEmbyProgressReporting()
}

internal fun PlayerRuntimeController.startEmbyProgressReporting() {
    embyProgressJob?.cancel()
    embyProgressJob = scope.launch {
        while (isActive) {
            delay(EMBY_PROGRESS_INTERVAL_MS)
            if (!isPlaybackCurrentlyPlaying()) continue
            val positionMs = currentPlaybackPositionMs() ?: continue
            val isPaused = !isPlaybackCurrentlyPlaying()
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
