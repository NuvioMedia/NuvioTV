package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"
    private const val SWITCH_TIMEOUT_MS = 5000L
    private const val REFRESH_MATCH_MIN_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var timeoutHandler: Handler? = null
    private var timeoutRunnable: Runnable? = null
    data class DisplayModeSwitchResult(
        val appliedMode: Display.Mode,
        val isFallback: Boolean
    )

    private var pendingAfterSwitch: ((DisplayModeSwitchResult) -> Unit)? = null
    private var pendingDisplayId: Int? = null
    private var pendingMode: Display.Mode? = null
    private var originalModeId: Int? = null

    data class FrameRateDetection(
        val raw: Float,
        val snapped: Float
    )

    private fun matchesTargetRefresh(refreshRate: Float, target: Float): Boolean {
        val tolerance = max(REFRESH_MATCH_MIN_TOLERANCE_HZ, target * 0.003f)
        return abs(refreshRate - target) <= tolerance
    }

    private fun pickBestForTarget(modes: List<Display.Mode>, target: Float): Display.Mode? {
        if (target <= 0f) return null
        val closest = modes.minByOrNull { abs(it.refreshRate - target) } ?: return null
        return if (matchesTargetRefresh(closest.refreshRate, target)) closest else null
    }

    private fun refreshWeight(refresh: Float, fps: Float): Float {
        if (fps <= 0f) return Float.MAX_VALUE
        val div = refresh / fps
        val rounded = div.roundToInt()
        var weight = if (rounded < 1) {
            (fps - refresh) / fps
        } else {
            abs(div / rounded - 1f)
        }
        if (refresh > 60f && rounded > 1) {
            weight += rounded / 10000f
        }
        return weight
    }

    private fun completeSwitch(reason: String) {
        Log.d(TAG, "Display mode switch completed ($reason)")
        val callback = pendingAfterSwitch
        val requestedMode = pendingMode
        val realMode = runCatching {
            val displayId = pendingDisplayId
            if (displayId != null) {
                displayManager?.getDisplay(displayId)?.mode
            } else {
                null
            }
        }.getOrNull()
        val appliedMode = realMode ?: requestedMode
        val isFallback = requestedMode != null && realMode != null && realMode.modeId != requestedMode.modeId
        cleanupDisplayListener()
        if (callback != null && appliedMode != null) {
            callback(DisplayModeSwitchResult(appliedMode = appliedMode, isFallback = isFallback))
        }
    }

    private fun scheduleSwitchTimeout() {
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutRunnable = Runnable {
            Log.w(TAG, "Display mode switch timeout after ${SWITCH_TIMEOUT_MS}ms")
            completeSwitch("timeout")
        }
        timeoutHandler?.postDelayed(timeoutRunnable!!, SWITCH_TIMEOUT_MS)
    }

    private fun recordOriginalMode(display: Display) {
        if (originalModeId == null) {
            originalModeId = display.mode.modeId
        }
    }

    /**
     * Refine ambiguous cinema rates for the current display capabilities.
     * Useful when probe reports ~24.x but panel supports both 23.976 and 24.000.
     */
    fun refineFrameRateForDisplay(
        activity: Activity,
        detectedFps: Float,
        prefer23976Near24: Boolean = false
    ): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return detectedFps
        if (detectedFps !in 23.5f..24.5f) return detectedFps

        return try {
            val window = activity.window ?: return detectedFps
            val display = window.decorView.display ?: return detectedFps
            val activeMode = display.mode
            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.isEmpty()) return detectedFps

            val has23976 = pickBestForTarget(sameSizeModes, NTSC_FILM_FPS) != null
            val has24 = pickBestForTarget(sameSizeModes, CINEMA_24_FPS) != null

            when {
                has23976 && has24 -> {
                    if (prefer23976Near24) {
                        NTSC_FILM_FPS
                    } else if (abs(detectedFps - NTSC_FILM_FPS) <= abs(detectedFps - CINEMA_24_FPS)) {
                        NTSC_FILM_FPS
                    } else {
                        CINEMA_24_FPS
                    }
                }
                has23976 -> NTSC_FILM_FPS
                has24 -> CINEMA_24_FPS
                else -> detectedFps
            }
        } catch (_: Exception) {
            detectedFps
        }
    }

    fun matchFrameRate(
        activity: Activity,
        frameRate: Float,
        onBeforeSwitch: (() -> Unit)? = null,
        onAfterSwitch: ((DisplayModeSwitchResult) -> Unit)? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (frameRate <= 0f) return false

        return try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            val supportedModes = display.supportedModes
            val activeMode = display.mode

            if (supportedModes.size <= 1) return false

            val sameSizeModes = supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.size <= 1) return false

            // Kodi-like priority without user whitelist:
            // exact -> 2x -> 3:2 pulldown -> weighted fallback.
            val modeExact = pickBestForTarget(sameSizeModes, frameRate)
            val modeDouble = pickBestForTarget(sameSizeModes, frameRate * 2f)
            val modePulldown = pickBestForTarget(sameSizeModes, frameRate * 2.5f)
            val modeFallback = sameSizeModes.minByOrNull { refreshWeight(it.refreshRate, frameRate) }

            val modeBest = modeExact ?: modeDouble ?: modePulldown ?: modeFallback ?: activeMode
            val switchNeeded = modeBest.modeId != activeMode.modeId

            if (switchNeeded) {
                Log.d(
                    TAG,
                    "Switching display mode: ${activeMode.refreshRate}Hz -> ${modeBest.refreshRate}Hz " +
                        "(video ${frameRate}fps)"
                )

                cleanupDisplayListener()
                recordOriginalMode(display)

                var completeImmediately = false
                if (onAfterSwitch != null) {
                    pendingAfterSwitch = onAfterSwitch
                    pendingMode = modeBest
                    pendingDisplayId = display.displayId
                    displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    displayListener = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) = Unit
                        override fun onDisplayRemoved(displayId: Int) = Unit
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId != pendingDisplayId) return
                            completeSwitch("displayChanged")
                        }
                    }
                    if (displayManager != null) {
                        displayManager?.registerDisplayListener(
                            displayListener,
                            Handler(Looper.getMainLooper())
                        )
                        scheduleSwitchTimeout()
                    } else {
                        completeImmediately = true
                    }
                }

                onBeforeSwitch?.invoke()

                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = modeBest.modeId
                window.attributes = layoutParams

                if (completeImmediately) {
                    completeSwitch("noDisplayManager")
                }
            } else {
                Log.d(TAG, "Display already at optimal rate ${activeMode.refreshRate}Hz for ${frameRate}fps")
            }

            switchNeeded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to match frame rate", e)
            if (pendingAfterSwitch != null) {
                completeSwitch("error")
            }
            false
        }
    }

    fun cleanupDisplayListener() {
        timeoutRunnable?.let { timeoutHandler?.removeCallbacks(it) }
        timeoutRunnable = null
        timeoutHandler = null

        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        displayManager = null

        pendingAfterSwitch = null
        pendingDisplayId = null
        pendingMode = null
    }

    fun clearOriginalDisplayMode() {
        originalModeId = null
    }

    fun restoreOriginalDisplayMode(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val targetModeId = originalModeId ?: return false

        return try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            if (display.mode.modeId == targetModeId) {
                originalModeId = null
                true
            } else {
                cleanupDisplayListener()
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = targetModeId
                window.attributes = layoutParams
                originalModeId = null
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore display mode", e)
            false
        }
    }

    fun snapToStandardRate(formatFrameRate: Float): Float {
        if (formatFrameRate <= 0f) return formatFrameRate
        return when {
            formatFrameRate in 23.90f..23.988f -> NTSC_FILM_FPS
            formatFrameRate in 23.988f..24.1f -> CINEMA_24_FPS
            formatFrameRate in 24.9f..25.1f -> 25f
            formatFrameRate in 29.90f..29.985f -> 30000f / 1001f
            formatFrameRate in 29.985f..30.1f -> 30f
            formatFrameRate in 49.9f..50.1f -> 50f
            formatFrameRate in 59.9f..59.97f -> 60000f / 1001f
            formatFrameRate in 59.97f..60.1f -> 60f
            else -> formatFrameRate
        }
    }

    private fun snapProbeRateByFrameDuration(measuredFps: Float, averageFrameDurationUs: Float): Float {
        if (measuredFps in 23.5f..24.5f) {
            val frameUs23976 = 1_000_000f / NTSC_FILM_FPS
            val frameUs24 = 1_000_000f / CINEMA_24_FPS
            val diff23976 = abs(averageFrameDurationUs - frameUs23976)
            val diff24 = abs(averageFrameDurationUs - frameUs24)
            val nearestCinema = if (diff23976 <= diff24) NTSC_FILM_FPS else CINEMA_24_FPS
            val nearestDiff = min(diff23976, diff24)

            // If probe timing is reasonably close to cinema cadence, trust frame-duration matching.
            if (nearestDiff <= 120f) {
                return nearestCinema
            }
        }
        return snapToStandardRate(measuredFps)
    }

    fun detectFrameRateFromSource(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String> = emptyMap()
    ): FrameRateDetection? {
        val extractor = MediaExtractor()
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(sourceUrl, headers)
                else -> extractor.setDataSource(context, uri, headers)
            }

            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex < 0) return null

            extractor.selectTrack(videoTrackIndex)
            val timestamps = ArrayList<Long>(400)
            val ignoreSamples = 30
            val targetSamples = 350 + ignoreSamples

            while (timestamps.size < targetSamples) {
                val ts = extractor.sampleTime
                if (ts < 0) break
                timestamps.add(ts)
                if (!extractor.advance()) break
            }

            if (timestamps.size <= ignoreSamples + 1) return null

            var totalFrameDurationUs = 0L
            for (i in (ignoreSamples + 1) until timestamps.size) {
                totalFrameDurationUs += (timestamps[i] - timestamps[i - 1])
            }

            val sampleCount = (timestamps.size - ignoreSamples - 1).coerceAtLeast(1)
            if (sampleCount < 90) return null

            val averageFrameDurationUs = totalFrameDurationUs.toFloat() / sampleCount.toFloat()
            if (averageFrameDurationUs <= 0f) return null

            val measured = 1_000_000f / averageFrameDurationUs
            if (measured < 10f || measured > 120f) return null

            FrameRateDetection(
                raw = measured,
                snapped = snapProbeRateByFrameDuration(measured, averageFrameDurationUs)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Frame rate probe failed: ${e.message}")
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
