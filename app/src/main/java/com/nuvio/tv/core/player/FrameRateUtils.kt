package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"
    private const val SWITCH_TIMEOUT_MS = 4000L
    private const val REFRESH_MATCH_MIN_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f
    private val NEXTLIB_HTTP_SCHEMES = setOf("http", "https")
    private val LIVE_STREAM_EXTENSIONS = listOf(".m3u8", ".mpd", ".ism/manifest")
    private const val MKV_EXTENSION = ".mkv"
    private const val SWITCH_POLL_INTERVAL_MS = 60L
    private const val SWITCH_REQUIRED_STABLE_POLLS = 2

    data class DisplayModeSwitchResult(
        val appliedMode: Display.Mode
    )

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

    private fun chooseBestModeForFrameRate(
        activeMode: Display.Mode,
        modes: List<Display.Mode>,
        frameRate: Float
    ): Display.Mode {
        val modeExact = pickBestForTarget(modes, frameRate)
        val modeDouble = pickBestForTarget(modes, frameRate * 2f)
        val modePulldown = pickBestForTarget(modes, frameRate * 2.5f)
        val modeFallback = modes.minByOrNull { refreshWeight(it.refreshRate, frameRate) }
        return modeExact ?: modeDouble ?: modePulldown ?: modeFallback ?: activeMode
    }

    suspend fun matchFrameRateAndWait(
        activity: Activity,
        frameRate: Float
    ): DisplayModeSwitchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (frameRate <= 0f) return null

        val switchPlan = withContext(Dispatchers.Main) {
            val window = activity.window ?: return@withContext null
            val display = window.decorView.display ?: return@withContext null
            val activeMode = display.mode

            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.size <= 1) {
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }

            val modeBest = chooseBestModeForFrameRate(
                activeMode = activeMode,
                modes = sameSizeModes,
                frameRate = frameRate
            )
            recordOriginalMode(display)
            if (modeBest.modeId == activeMode.modeId) {
                Log.d(TAG, "Display already at optimal rate ${activeMode.refreshRate}Hz for ${frameRate}fps")
                return@withContext Pair<Display.Mode?, DisplayModeSwitchResult?>(
                    null,
                    DisplayModeSwitchResult(activeMode)
                )
            }

            Log.d(
                TAG,
                "Switching display mode: ${activeMode.refreshRate}Hz -> ${modeBest.refreshRate}Hz " +
                    "(video ${frameRate}fps)"
            )

            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = modeBest.modeId
            window.attributes = layoutParams
            Pair(modeBest, null)
        } ?: return null

        val immediateResult = switchPlan.second
        if (immediateResult != null) return immediateResult

        val expectedMode = switchPlan.first ?: return null
        var stablePolls = 0
        var lastMode: Display.Mode? = null
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < SWITCH_TIMEOUT_MS) {
            val mode = withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode
            } ?: break

            lastMode = mode
            val modeStable =
                mode.modeId == expectedMode.modeId ||
                    matchesTargetRefresh(mode.refreshRate, expectedMode.refreshRate)
            if (modeStable) {
                stablePolls += 1
                if (stablePolls >= SWITCH_REQUIRED_STABLE_POLLS) {
                    Log.d(
                        TAG,
                        "Display mode switch stabilized at ${mode.refreshRate}Hz (modeId=${mode.modeId})"
                    )
                    return DisplayModeSwitchResult(mode)
                }
            } else {
                stablePolls = 0
            }
            delay(SWITCH_POLL_INTERVAL_MS)
        }

        val fallbackMode = lastMode ?: expectedMode
        Log.w(
            TAG,
            "Display mode polling timed out after ${SWITCH_TIMEOUT_MS}ms, using ${fallbackMode.refreshRate}Hz"
        )
        return DisplayModeSwitchResult(fallbackMode)
    }

    fun cleanupDisplayListener() {
        // Kept for API compatibility with existing call sites.
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
        detectFrameRateWithNextLib(context, sourceUrl, headers)?.let { return it }

        if (isResolveProxyUrl(sourceUrl)) {
            val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank()) {
                detectFrameRateWithExtractor(context, embeddedResolveUrl, headers)?.let { return it }
            }
        }
        return detectFrameRateWithExtractor(context, sourceUrl, headers)
    }

    private fun detectFrameRateWithNextLib(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>
    ): FrameRateDetection? {
        if (!shouldUseNextLibProbe(sourceUrl, headers)) return null

        val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
        val shouldPreferEmbedded = isResolveProxyUrl(sourceUrl)
        val candidates = buildList {
            if (shouldPreferEmbedded && !embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
                add(sourceUrl)
                return@buildList
            }

            add(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
            }
        }

        candidates.forEach { candidateUrl ->
            var mediaInfo: MediaInfo? = null
            try {
                val uri = Uri.parse(candidateUrl)
                val builder = MediaInfoBuilder().from(context = context, uri = uri)
                mediaInfo = builder.build() ?: return@forEach

                val video = mediaInfo.videoStream ?: return@forEach
                val measured = video.frameRate.toFloat()
                if (!measured.isFinite() || measured <= 0f) return@forEach

                return FrameRateDetection(
                    raw = measured,
                    snapped = snapToStandardRate(measured)
                )
            } catch (e: Throwable) {
                Log.w(TAG, "NextLib frame rate probe failed: ${e.message}")
            } finally {
                runCatching { mediaInfo?.release() }
            }
        }
        return null
    }

    private fun detectFrameRateWithExtractor(
        context: Context,
        sourceUrl: String,
        headers: Map<String, String>
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

    private fun shouldUseNextLibProbe(sourceUrl: String, headers: Map<String, String>): Boolean {
        if (sourceUrl.isBlank()) return false
        if (isLiveStreamUrl(sourceUrl)) return false
        if (isMkvSource(sourceUrl)) return true

        val scheme = Uri.parse(sourceUrl).scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            in NEXTLIB_HTTP_SCHEMES -> true
            "file", "content" -> true
            null -> true
            else -> false
        }
    }

    private fun isLiveStreamUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return LIVE_STREAM_EXTENSIONS.any { ext -> normalized.endsWith(ext) }
    }

    private fun isMkvSource(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return normalized.endsWith(MKV_EXTENSION)
    }

    private fun isResolveProxyUrl(sourceUrl: String): Boolean {
        val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
        return "/resolve/" in normalized
    }

    private fun extractEmbeddedResolveUrl(sourceUrl: String): String? {
        val marker = "/resolve/"
        val markerIndex = sourceUrl.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null

        val afterResolve = sourceUrl.substring(markerIndex + marker.length)
        val nestedEncoded = afterResolve.substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/', missingDelimiterValue = "")
        if (nestedEncoded.isBlank()) return null

        return runCatching {
            URLDecoder.decode(nestedEncoded, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }
}
