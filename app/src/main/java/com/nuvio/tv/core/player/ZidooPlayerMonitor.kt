package com.nuvio.tv.core.player

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Monitors the Zidoo internal player (VS10 engine) via its local REST API.
 *
 * Zidoo media players expose a local HTTP API on port 9529 that provides
 * playback status including current position and duration. Unlike standard
 * Android players, Zidoo does NOT return results via ActivityResult — instead
 * we poll this API to track playback progress.
 *
 * API endpoint: http://127.0.0.1:9529/ZidooVideoPlay/getPlayStatus
 * Response format:
 * {
 *   "status": 200,
 *   "video": {
 *     "status": 1,           // 1 = playing/paused, 0 = stopped
 *     "currentPosition": 438994,  // ms
 *     "duration": 7448480,        // ms
 *     "title": "...",
 *     "path": "..."
 *   }
 * }
 */
object ZidooPlayerMonitor {

    private const val TAG = "ZidooPlayerMonitor"
    private const val BASE_URL = "http://127.0.0.1:9529"
    private const val PLAY_STATUS_PATH = "/ZidooVideoPlay/getPlayStatus"
    private const val POLL_INTERVAL_MS = 2000L
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 2000
    private const val MAX_POLL_TIMEOUT_MS = 8L * 60 * 60 * 1000 // 8 hours max

    /**
     * Result from monitoring Zidoo player playback.
     */
    data class ZidooPlaybackResult(
        val positionMs: Long,
        val durationMs: Long
    )

    /**
     * Returns true if the current device is likely a Zidoo media player.
     * Checks manufacturer and whether the Zidoo player API is reachable.
     */
    fun isZidooDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        return manufacturer.contains("zidoo") ||
            brand.contains("zidoo") ||
            model.contains("zidoo")
    }

    /**
     * Probes whether the Zidoo player API is available on localhost.
     * This is a quick connectivity check (not a full playback status check).
     */
    suspend fun isApiAvailable(): Boolean {
        return try {
            val status = fetchPlayStatus()
            status != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /**
     * Polls the Zidoo player API until playback stops, then returns the last known
     * position and duration. Returns null if the API is unreachable or playback
     * was never detected.
     *
     * This is a suspending function that should be launched in a coroutine scope
     * tied to the lifecycle of the screen that initiated external playback.
     *
     * @param initialDelayMs Delay before first poll to give the player time to start.
     * @param resumePositionMs If > 0, seek to this position once playback is detected.
     */
    suspend fun awaitPlaybackEnd(
        initialDelayMs: Long = 3000L,
        resumePositionMs: Long = 0L
    ): ZidooPlaybackResult? {
        delay(initialDelayMs)

        var lastPosition: Long = 0L
        var lastDuration: Long = 0L
        var wasPlaying = false
        var hasSeeked = false
        var consecutiveStoppedPolls = 0
        val requiredStoppedPolls = 2 // Require 2 consecutive "stopped" to confirm

        return withTimeoutOrNull(MAX_POLL_TIMEOUT_MS) {
            while (true) {
                val status = fetchPlayStatus()

                if (status == null) {
                    // API unreachable — if we were tracking playback, treat as stopped
                    if (wasPlaying) {
                        Log.d(TAG, "API unreachable after playback detected, returning last position")
                        return@withTimeoutOrNull if (lastPosition > 0L) {
                            ZidooPlaybackResult(lastPosition, lastDuration)
                        } else null
                    }
                    // Never saw playback — might not be a Zidoo device or player not started yet
                    consecutiveStoppedPolls++
                    if (consecutiveStoppedPolls > 5) {
                        Log.d(TAG, "API never showed playback after multiple polls, giving up")
                        return@withTimeoutOrNull null
                    }
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                val videoStatus = status.optInt("status", -1)
                val position = status.optLong("currentPosition", 0L)
                val duration = status.optLong("duration", 0L)

                if (videoStatus == 1 && duration > 0L) {
                    // Playing or paused
                    wasPlaying = true
                    consecutiveStoppedPolls = 0
                    lastPosition = position
                    lastDuration = duration

                    // Seek to resume position once playback starts
                    if (!hasSeeked && resumePositionMs > 0L && position < resumePositionMs) {
                        hasSeeked = true
                        Log.d(TAG, "Seeking Zidoo player to ${resumePositionMs}ms")
                        seekTo(resumePositionMs)
                    }
                } else if (wasPlaying) {
                    // Was playing, now stopped
                    consecutiveStoppedPolls++
                    if (consecutiveStoppedPolls >= requiredStoppedPolls) {
                        Log.d(TAG, "Playback stopped. pos=${lastPosition}ms, dur=${lastDuration}ms")
                        return@withTimeoutOrNull if (lastPosition > 0L) {
                            ZidooPlaybackResult(lastPosition, lastDuration)
                        } else null
                    }
                } else {
                    // Not playing yet
                    consecutiveStoppedPolls++
                    if (consecutiveStoppedPolls > 10) {
                        // Player never started within ~20 seconds
                        Log.d(TAG, "Player never started, giving up")
                        return@withTimeoutOrNull null
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * Fetches current play status from the Zidoo API.
     * Returns the "video" JSON object, or null if unreachable/error.
     */
    private suspend fun fetchPlayStatus(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL$PLAY_STATUS_PATH")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"

            try {
                val responseCode = connection.responseCode
                if (responseCode != 200) return@withContext null

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                if (json.optInt("status", -1) != 200) return@withContext null

                json.optJSONObject("video")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Connection refused, timeout, etc. — expected on non-Zidoo devices
            null
        }
    }

    /**
     * Seeks the Zidoo player to the given position in milliseconds.
     * Note: The Zidoo API has a known typo — the parameter is "positon" (not "position").
     */
    private suspend fun seekTo(positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Known Zidoo API typo: "positon" instead of "position"
            val url = URL("$BASE_URL/ZidooVideoPlay/seekTo?positon=$positionMs")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"

            try {
                val responseCode = connection.responseCode
                val success = responseCode == 200
                if (!success) {
                    Log.w(TAG, "seekTo failed with code $responseCode")
                }
                success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "seekTo failed", e)
            false
        }
    }
}
