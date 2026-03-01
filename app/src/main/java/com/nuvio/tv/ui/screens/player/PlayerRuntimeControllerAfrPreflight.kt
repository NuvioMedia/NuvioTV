package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val AFR_PREFLIGHT_PROBE_TIMEOUT_MS = 4000L

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode
) {
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

    _uiState.update {
        it.copy(
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    try {
        val detection = withTimeoutOrNull(AFR_PREFLIGHT_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                FrameRateUtils.detectFrameRateFromSource(
                    context = context,
                    sourceUrl = url,
                    headers = headers
                )
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed after ${AFR_PREFLIGHT_PROBE_TIMEOUT_MS}ms"
            )
            return
        }

        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }

        val prefer23976ProbeBias = detection.raw in 23.95f..24.12f
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = detection.snapped,
            prefer23976Near24 = prefer23976ProbeBias
        )

        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate
        )

        if (result != null) {
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
