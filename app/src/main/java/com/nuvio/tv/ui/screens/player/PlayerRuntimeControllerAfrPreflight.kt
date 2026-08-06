package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * AFR preflight probe budgets.
 *
 * Keep user-facing wait short. Cold debrid/CDN TTFB is handled by a tiny Range warmup
 * inside the OkHttp probe (not by inflating these ceilings). NextLib/extractor only use
 * whatever remains of the total deadline after OkHttp.
 */
internal const val AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS = 15_000L
internal const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 10_000L
internal const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 5_000L
internal const val AFR_PREFLIGHT_TOTAL_TIMEOUT_MS = 18_000L
/** Minimum remaining time to attempt NextLib / extractor after OkHttp. */
internal const val AFR_PREFLIGHT_MIN_STAGE_MS = 2_000L

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean,
    mimeType: String? = null
) {
    mpvDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        }
        return
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "AFR preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR preflight: already running or completed, skipping duplicate execution")
        return
    }

    _uiState.update {
        it.copy(
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    // Original stream headers (without Range) – used for NextLib bypass decision.
    // If these contain auth/custom headers, NextLib is skipped (MediaInfoBuilder cannot forward them).
    val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(headers)
    // Extractor fallback headers – add Connection: close for proper connection teardown.
    val probeHeaders = FrameRateUtils.extractorProbeHeaders(headers)
    val effectiveMimeType = mimeType ?: currentStreamMimeType
    val filename = currentFilename
    val deadlineElapsedRealtime = SystemClock.elapsedRealtime() + AFR_PREFLIGHT_TOTAL_TIMEOUT_MS

    fun remainingMs(): Long =
        (deadlineElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    try {
        val cached = FrameRateUtils.getCachedFrameRate(url, headers, filename)
        if (cached != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: cache hit! Using cached FPS=${cached.snapped}")
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = cached.raw,
                    detectedFrameRate = cached.snapped,
                    detectedFrameRateSource = FrameRateSource.PROBE
                )
            }
            val prefer23976ProbeBias = cached.raw in 23.95f..23.999f
            val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
                activity = activity,
                detectedFps = cached.snapped,
                prefer23976Near24 = prefer23976ProbeBias
            )
            val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                withContext(Dispatchers.Main) {
                    activity.window?.decorView?.display?.mode?.modeId
                }
            } else {
                null
            }

            val result = FrameRateUtils.matchFrameRateAndWait(
                activity = activity,
                frameRate = targetFrameRate,
                videoWidth = cached.videoWidth,
                videoHeight = cached.videoHeight,
                resolutionMatchingEnabled = resolutionMatchingEnabled
            )

            if (result != null) {
                val switchedDisplayMode = initialDisplayModeId != null &&
                    initialDisplayModeId != result.appliedMode.modeId
                mpvDelayStartAfterAfrSwitch = switchedDisplayMode

                _uiState.update {
                    it.copy(
                        displayModeInfo = DisplayModeInfo(
                            width = result.appliedMode.physicalWidth,
                            height = result.appliedMode.physicalHeight,
                            refreshRate = result.appliedMode.refreshRate
                        ),
                        showDisplayModeInfo = true
                    )
                }
            }
            return
        }

        val okHttpBudget = minOf(AFR_PREFLIGHT_OKHTTP_TIMEOUT_MS, remainingMs())
        val okHttpDetection = if (okHttpBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
            withTimeoutOrNull(okHttpBudget) {
                withContext(Dispatchers.IO) {
                    // Blocking OkHttp calls ignore coroutine cancellation; hand the probe a
                    // signal tied to this job so timeout/cancel actually stops the downloads.
                    val probeJob = coroutineContext[Job]
                    FrameRateUtils.detectFrameRateWithOkHttpProbe(
                        context = context,
                        sourceUrl = url,
                        headers = streamHeaders,
                        mimeType = effectiveMimeType,
                        filename = filename,
                        isCancelled = { probeJob?.isActive != true }
                    )
                }
            }
        } else {
            null
        }

        val detection = if (okHttpDetection != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: OkHttp probe succeeded! FPS=${okHttpDetection.snapped}")
            okHttpDetection
        } else {
            val nextLibBudget = minOf(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS, remainingMs())
            val nextLibDetection = if (nextLibBudget >= AFR_PREFLIGHT_MIN_STAGE_MS) {
                withTimeoutOrNull(nextLibBudget) {
                    withContext(Dispatchers.IO) {
                        FrameRateUtils.detectFrameRateFromNextLib(
                            context = context,
                            sourceUrl = url,
                            headers = streamHeaders,
                            mimeType = effectiveMimeType,
                            filename = filename
                        )
                    }
                }
            } else {
                null
            }
            if (nextLibDetection != null) {
                nextLibDetection
            } else {
                val fallbackBudget = minOf(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS, remainingMs())
                if (fallbackBudget < AFR_PREFLIGHT_MIN_STAGE_MS) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight: no time left for extractor fallback (remaining=${remainingMs()}ms)"
                    )
                    null
                } else {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AFR preflight NextLib probe failed/timed out; trying extractor fallback (${fallbackBudget}ms)"
                    )
                    withTimeoutOrNull(fallbackBudget) {
                        withContext(Dispatchers.IO) {
                            FrameRateUtils.detectFrameRateFromExtractor(
                                context = context,
                                sourceUrl = url,
                                headers = probeHeaders
                            )
                        }
                    }
                }
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (OkHttp + NextLib + extractor fallback)"
            )
            return
        }

        FrameRateUtils.cacheFrameRate(url, headers, detection, currentFilename)

        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }

        val prefer23976ProbeBias = detection.raw in 23.95f..23.999f
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = detection.snapped,
            prefer23976Near24 = prefer23976ProbeBias
        )
        val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode?.modeId
            }
        } else {
            null
        }

        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate,
            videoWidth = detection.videoWidth,
            videoHeight = detection.videoHeight,
            resolutionMatchingEnabled = resolutionMatchingEnabled
        )

        if (result != null) {
            val switchedDisplayMode = initialDisplayModeId != null &&
                initialDisplayModeId != result.appliedMode.modeId
            mpvDelayStartAfterAfrSwitch = switchedDisplayMode

            _uiState.update {
                it.copy(
                    displayModeInfo = DisplayModeInfo(
                        width = result.appliedMode.physicalWidth,
                        height = result.appliedMode.physicalHeight,
                        refreshRate = result.appliedMode.refreshRate
                    ),
                    showDisplayModeInfo = true
                )
            }
        }
    } finally {
        withContext(NonCancellable) {
            _uiState.update { it.copy(afrProbeRunning = false) }
        }
    }
}
