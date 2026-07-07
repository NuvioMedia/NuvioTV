package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.util.Log
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 6000L
private const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 4000L

/**
 * Runs the NextLib probe on an abandonable daemon thread so that a stuck native
 * build() cannot hold playback start past [timeoutMs]. NextLib exposes no handle
 * to interrupt an in-progress build (the MediaInfo only exists once build()
 * returns, unlike MediaExtractor which the extractor watchdog release()s
 * mid-setDataSource), so on timeout the coroutine returns via a cancellable
 * await and the worker thread is abandoned — it ends when FFmpeg's network op
 * completes or fails. NextLib stays the primary detector; this only bounds how
 * long start waits for it.
 */
private suspend fun PlayerRuntimeController.probeNextLibBounded(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long
): FrameRateUtils.FrameRateDetection? {
    val result = CompletableDeferred<FrameRateUtils.FrameRateDetection?>()
    Thread({
        val detection = try {
            FrameRateUtils.detectFrameRateFromNextLib(context = context, sourceUrl = url, headers = headers)
        } catch (t: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AFR ExoPlayer preflight: NextLib probe threw: ${t.message}")
            null
        }
        result.complete(detection)
    }, "afr-nextlib-probe").apply {
        isDaemon = true
        start()
    }
    return withTimeoutOrNull(timeoutMs) { result.await() }
}

/**
 * ExoPlayer engine path: NextLib-primary preflight, track-format fallback.
 *
 * Detection order:
 *   1. cached detection (instant), else
 *   2. NextLib (io.github.anilbeesetti.nextlib.mediainfo) — the primary, most
 *      reliable FPS source; hard-bounded by AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS (an
 *      abandonable probe thread, see probeNextLibBounded) and, in Initialization,
 *      by the absolute preflight deadline, so it cannot hold start hostage.
 *   3. on NextLib miss/timeout: NO blocking MediaExtractor fallback — that
 *      native setDataSource() is the ≥109 s non-faststart-MP4 hang. Instead this
 *      returns and the frame rate is taken from ExoPlayer's own track format
 *      after prepare() (see PlayerRuntimeControllerAfrTrack.kt): a non-blocking
 *      fallback, not a replacement for NextLib.
 *
 * The blocking extractor probe remains only in runAfrPreflightIfEnabled (MPV).
 */
internal suspend fun PlayerRuntimeController.runAfrExoPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    mpvDelayStartAfterAfrSwitch = false
    exoDelayStartAfterAfrSwitch = false

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
        Log.w(PlayerRuntimeController.TAG, "AFR ExoPlayer preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR ExoPlayer preflight: already running or completed, skipping")
        return
    }

    // 1. Cache.
    var detection = FrameRateUtils.getCachedFrameRate(url, headers)
    var detectionSource = "cache"

    // 2. NextLib (primary) on cache miss — bounded, no extractor fallback.
    if (detection == null) {
        detectionSource = "NextLib"
        // Mark the probe in flight so track-format AFR stands down while NextLib
        // runs. try/finally + NonCancellable so a cancellation mid-probe (the
        // 15 s absolute deadline, or a stream/episode switch) cannot leave the
        // flag stuck true — a stuck flag blocks every later preflight AND
        // AfrTrack via the afrProbeRunning guard.
        _uiState.update { it.copy(afrProbeRunning = true) }
        try {
            val streamHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }
            // Bounded on an abandonable thread: a stuck native build() cannot hold
            // start past the budget (a cooperative timeout around withContext(IO)
            // could not — the native call ignores cancellation).
            detection = probeNextLibBounded(url, streamHeaders, AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS)
            if (detection != null) {
                FrameRateUtils.cacheFrameRate(url, headers, detection)
            }
        } finally {
            withContext(NonCancellable) {
                _uiState.update { it.copy(afrProbeRunning = false) }
            }
        }
    }

    // 3. No detection → defer to track-format AFR after prepare (non-blocking fallback).
    if (detection == null) {
        Log.d(
            PlayerRuntimeController.TAG,
            "AFR ExoPlayer preflight: NextLib miss/timeout after ${AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS}ms; deferring to track-format AFR after prepare"
        )
        return
    }

    // 4. Apply the pre-prepare display-mode switch from the primary detection.
    Log.d(PlayerRuntimeController.TAG, "AFR ExoPlayer preflight: $detectionSource FPS=${detection.snapped}; applying pre-prepare switch")
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
        exoDelayStartAfterAfrSwitch = switchedDisplayMode
        // Track-format AFR stands down: the preflight already ran a mode selection.
        afrModeAppliedPreStart = true

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
}

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    mpvDelayStartAfterAfrSwitch = false
    exoDelayStartAfterAfrSwitch = false

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
    // If these contain any entries, the stream likely requires auth headers that NextLib cannot forward.
    val streamHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }
    // Extractor fallback headers – add Connection: close for proper connection teardown.
    val probeHeaders = streamHeaders.toMutableMap().apply {
        put("Connection", "close")
    }

    try {
        val cached = FrameRateUtils.getCachedFrameRate(url, headers)
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
                exoDelayStartAfterAfrSwitch = switchedDisplayMode

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

        val nextLibDetection = withTimeoutOrNull(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                FrameRateUtils.detectFrameRateFromNextLib(
                    context = context,
                    sourceUrl = url,
                    headers = streamHeaders
                )
            }
        }
        val detection = if (nextLibDetection != null) {
            nextLibDetection
        } else {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight NextLib probe failed/timed out after ${AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS}ms; trying extractor fallback"
            )
            withTimeoutOrNull(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    FrameRateUtils.detectFrameRateFromExtractor(
                        context = context,
                        sourceUrl = url,
                        headers = probeHeaders
                    )
                }
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (NextLib + extractor fallback)"
            )
            return
        }

        FrameRateUtils.cacheFrameRate(url, headers, detection)

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
            exoDelayStartAfterAfrSwitch = switchedDisplayMode

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
