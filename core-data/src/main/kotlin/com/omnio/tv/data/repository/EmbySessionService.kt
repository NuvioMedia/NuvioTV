package com.omnio.tv.data.repository

import android.util.Log
import com.omnio.tv.data.local.EmbyCredentialsDataStore
import com.omnio.tv.data.remote.api.EmbyApi
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackProgressDto
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackStartDto
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackStopDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmbySessionService"

@Singleton
class EmbySessionService @Inject constructor(
    private val embyApi: EmbyApi,
    private val embyCredentialsDataStore: EmbyCredentialsDataStore
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentItemId: String? = null
    private var currentMediaSourceId: String? = null
    private var currentPlaySessionId: String? = null
    private var hasReportedStart: Boolean = false
    private var lastProgressReportMs: Long = 0L
    private val progressIntervalMs = 10_000L

    suspend fun reportStart(itemId: String, mediaSourceId: String, positionMs: Long = 0L) {
        if (!isConnected()) return
        if (hasReportedStart && currentItemId == itemId && currentMediaSourceId == mediaSourceId) return

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
                Log.w(TAG, "Failed to report start: ${response.code()}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error reporting playback start: ${error.message}", error)
        }
    }

    suspend fun reportProgress(positionMs: Long, isPaused: Boolean = false, force: Boolean = false) {
        if (!isConnected()) return
        val itemId = currentItemId ?: return
        val mediaSourceId = currentMediaSourceId ?: return
        val playSessionId = currentPlaySessionId ?: return
        if (!hasReportedStart) return

        val now = System.currentTimeMillis()
        if (!force && now - lastProgressReportMs < progressIntervalMs) return

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
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error reporting progress: ${error.message}", error)
        }
    }

    fun reportStop(positionMs: Long = 0L) {
        val itemId = currentItemId
        val mediaSourceId = currentMediaSourceId
        val playSessionId = currentPlaySessionId
        val wasStarted = hasReportedStart
        resetSession()

        if (!wasStarted || itemId == null || mediaSourceId == null || playSessionId == null) return

        // Launched on serviceScope (not the caller's scope) so the stop event still fires
        // when the player's viewModelScope has already been cancelled by ViewModel.clear().
        serviceScope.launch {
            if (!isConnected()) return@launch
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
                } else {
                    Log.w(TAG, "Failed to report stop: ${response.code()}")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error reporting playback stop: ${error.message}", error)
            }
        }
    }

    fun resetSession() {
        currentItemId = null
        currentMediaSourceId = null
        currentPlaySessionId = null
        hasReportedStart = false
        lastProgressReportMs = 0L
    }

    private suspend fun isConnected(): Boolean {
        return embyCredentialsDataStore.credentials.first().isConfigured
    }

    private fun msToTicks(ms: Long): Long = ms * 10_000L
}
