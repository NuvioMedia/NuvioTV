package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingRefreshGate

const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS =
    SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

fun shouldRunSimklRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true
    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

typealias SimklRefreshGate = TrackingRefreshGate
