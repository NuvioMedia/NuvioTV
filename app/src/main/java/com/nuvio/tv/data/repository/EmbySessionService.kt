package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.EmbyAuthDataStore
import com.nuvio.tv.data.remote.api.EmbyApi
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackProgressDto
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackStartDto
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackStopDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmbySessionService"

@Singleton
class EmbySessionService @Inject constructor(
    private val embyApi: EmbyApi,
    private val embyAuthDataStore: EmbyAuthDataStore
) {
    private var currentItemId: String? = null
    private var currentMediaSourceId: String? = null
    private var hasReportedStart: Boolean = false
    private var lastProgressReportMs: Long = 0L
    private val progressIntervalMs = 10_000L  // Report every 10 seconds

    /**
     * Report playback start to Emby.
     * Safe to call multiple times — deduplicates by itemId.
     */
    suspend fun reportStart(itemId: String, mediaSourceId: String, positionMs: Long = 0) {
        if (!isConnected()) return
        if (hasReportedStart && currentItemId == itemId) return

        try {
            val response = embyApi.reportPlaybackStart(
                EmbyPlaybackStartDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = msToTicks(positionMs)
                )
            )
            if (response.isSuccessful) {
                currentItemId = itemId
                currentMediaSourceId = mediaSourceId
                hasReportedStart = true
                lastProgressReportMs = System.currentTimeMillis()
                Log.d(TAG, "Reported playback start: $itemId")
            } else {
                Log.w(TAG, "Failed to report start: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting playback start: ${e.message}", e)
        }
    }

    /**
     * Report playback progress to Emby.
     * Throttled to every 10 seconds. Safe to call frequently.
     */
    suspend fun reportProgress(positionMs: Long, isPaused: Boolean = false) {
        if (!isConnected()) return
        val itemId = currentItemId ?: return
        val mediaSourceId = currentMediaSourceId ?: return
        if (!hasReportedStart) return

        val now = System.currentTimeMillis()
        if (now - lastProgressReportMs < progressIntervalMs) return

        try {
            val response = embyApi.reportPlaybackProgress(
                EmbyPlaybackProgressDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = msToTicks(positionMs),
                    isPaused = isPaused
                )
            )
            if (response.isSuccessful) {
                lastProgressReportMs = now
                Log.d(TAG, "Reported progress: $itemId at ${positionMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting progress: ${e.message}", e)
        }
    }

    /**
     * Report playback stopped to Emby.
     */
    suspend fun reportStop(positionMs: Long = 0) {
        if (!isConnected()) return
        val itemId = currentItemId ?: return
        val mediaSourceId = currentMediaSourceId ?: return
        if (!hasReportedStart) return

        try {
            val response = embyApi.reportPlaybackStopped(
                EmbyPlaybackStopDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = msToTicks(positionMs)
                )
            )
            if (response.isSuccessful) {
                Log.d(TAG, "Reported playback stopped: $itemId at ${positionMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting playback stop: ${e.message}", e)
        } finally {
            resetSession()
        }
    }

    /**
     * Reset session state. Called on stop or when switching content.
     */
    fun resetSession() {
        currentItemId = null
        currentMediaSourceId = null
        hasReportedStart = false
        lastProgressReportMs = 0L
    }

    private suspend fun isConnected(): Boolean {
        return embyAuthDataStore.state.first().isConnected
    }

    /**
     * Convert milliseconds to Emby ticks (1 ms = 10,000 ticks).
     */
    private fun msToTicks(ms: Long): Long = ms * 10_000L
}
