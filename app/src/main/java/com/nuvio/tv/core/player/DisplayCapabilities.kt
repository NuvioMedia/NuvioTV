package com.nuvio.tv.core.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt

/**
 * Heuristic detection of whether the current display supports refresh-rate
 * and resolution switching via Display.getSupportedModes().
 *
 * EDID-based detection is best-effort: some boxes/TVs misreport modes in
 * either direction. The result is intended to inform the UI (so the user
 * can disable a setting that appears to do nothing on their hardware), not
 * to gate execution — FrameRateUtils already silently no-ops when no
 * matching mode is available.
 */
object DisplayCapabilities {

    private const val TAG = "DisplayCapabilities"

    data class Snapshot(
        val supportsFrameRateSwitching: Boolean,
        val supportsResolutionSwitching: Boolean,
        val supportedModes: List<Display.Mode>,
        val currentModeId: Int,
        val apiSupported: Boolean,
    ) {
        companion object {
            val Unknown = Snapshot(
                supportsFrameRateSwitching = false,
                supportsResolutionSwitching = false,
                supportedModes = emptyList(),
                currentModeId = -1,
                apiSupported = false,
            )
        }
    }

    fun detect(activity: Activity): Snapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Snapshot.Unknown
        }
        val display = activity.window?.decorView?.display ?: return Snapshot.Unknown
        val modes = display.supportedModes.toList()
        val current = display.mode
        val (afr, res) = deriveSupport(modes, current.modeId)
        return Snapshot(
            supportsFrameRateSwitching = afr,
            supportsResolutionSwitching = res,
            supportedModes = modes,
            currentModeId = current.modeId,
            apiSupported = true,
        )
    }

    fun logSummary(snapshot: Snapshot) {
        if (!snapshot.apiSupported) {
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} apiSupported=false (no display introspection)")
            return
        }
        val current = snapshot.supportedModes.firstOrNull { it.modeId == snapshot.currentModeId }
        val rates = snapshot.supportedModes
            .map { roundedMilliHz(it.refreshRate) / 1000f }
            .distinct()
            .sorted()
            .joinToString(",")
        val resolutions = snapshot.supportedModes
            .map { "${it.physicalWidth}x${it.physicalHeight}" }
            .distinct()
            .joinToString(",")
        val currentDesc = current?.let {
            "${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(it.refreshRate)}Hz"
        } ?: "unknown"
        Log.i(
            TAG,
            "api=${Build.VERSION.SDK_INT} current=$currentDesc modeCount=${snapshot.supportedModes.size} " +
                "rates=[$rates] resolutions=[$resolutions] " +
                "afrSupported=${snapshot.supportsFrameRateSwitching} " +
                "resSupported=${snapshot.supportsResolutionSwitching}"
        )
    }

    /**
     * Pure predicate, extracted for unit testing.
     *
     * Returns Pair(supportsFrameRateSwitching, supportsResolutionSwitching).
     * Refresh rates are deduped at millihertz precision to avoid float-noise
     * false positives like 59.94f != 59.940002f.
     */
    internal fun deriveSupport(modes: List<Display.Mode>, currentModeId: Int): Pair<Boolean, Boolean> {
        if (modes.isEmpty()) return false to false
        val current = modes.firstOrNull { it.modeId == currentModeId } ?: modes.first()
        val sameResModes = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val distinctRates = sameResModes.map { roundedMilliHz(it.refreshRate) }.toSet()
        val distinctResolutions = modes.map { it.physicalWidth to it.physicalHeight }.toSet()
        return (distinctRates.size >= 2) to (distinctResolutions.size >= 2)
    }

    private fun roundedMilliHz(refreshRate: Float): Int = (refreshRate * 1000f).roundToInt()
}
