package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.EmbyAuthDataStore
import com.nuvio.tv.data.remote.api.EmbyApi
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackProgressDto
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackStartDto
import com.nuvio.tv.data.remote.dto.emby.EmbyPlaybackStopDto
import kotlinx.coroutines.flow.first
import java.util.UUID
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
    private var currentPlaySessionId: String? = null
    private var hasReportedStart: Boolean = false
    private var lastProgressReportMs: Long = 0L
    private val progressIntervalMs = 10_000L  // Report every 10 seconds

    /**
     * Report playback start to Emby.
     * Safe to call multiple times — deduplicates by itemId.
     */
    suspend fun reportStart(itemId: String, mediaSourceId: String, positionMs: Long = 0) {
        val connected = isConnected()
        Log.d(TAG, "reportStart: itemId=$itemId, mediaSourceId=$mediaSourceId, positionMs=$positionMs, isConnected=$connected")
        if (!connected) {
            Log.w(TAG, "reportStart: NOT connected — aborting")
            return
        }
        if (hasReportedStart && currentItemId == itemId) {
            Log.d(TAG, "reportStart: already reported for $itemId — skipping")
            return
        }

        try {
            val playSessionId = UUID.randomUUID().toString()
            val response = embyApi.reportPlaybackStart(
                EmbyPlaybackStartDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                    positionTicks = msToTicks(positionMs)
                )
            )
            if (response.isSuccessful) {
                currentItemId = itemId
                currentMediaSourceId = mediaSourceId
                currentPlaySessionId = playSessionId
                hasReportedStart = true
                lastProgressReportMs = System.currentTimeMillis()
                Log.d(TAG, "Reported playback start: $itemId")
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "Failed to report start: ${response.code()} — body: $errorBody")
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
        val playSessionId = currentPlaySessionId ?: return
        if (!hasReportedStart) return

        val now = System.currentTimeMillis()
        if (now - lastProgressReportMs < progressIntervalMs) return

        try {
            val response = embyApi.reportPlaybackProgress(
                EmbyPlaybackProgressDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
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
        val playSessionId = currentPlaySessionId ?: return
        if (!hasReportedStart) return

        try {
            val response = embyApi.reportPlaybackStopped(
                EmbyPlaybackStopDto(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
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
        currentPlaySessionId = null
        hasReportedStart = false
        lastProgressReportMs = 0L
    }

    private suspend fun isConnected(): Boolean {
        val state = embyAuthDataStore.state.first()
        val connected = state.isConnected
        if (!connected) {
            Log.d(TAG, "isConnected=false — serverUrl='${state.serverUrl}', apiKey='${if (state.apiKey.isNullOrBlank()) "BLANK" else "SET"}', userId='${if (state.userId.isNullOrBlank()) "BLANK" else "SET"}'")
        }
        return connected
    }

    /**
     * Convert milliseconds to Emby ticks (1 ms = 10,000 ticks).
     */
    private fun msToTicks(ms: Long): Long = ms * 10_000L
}
