package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.WatchProgress
import java.util.concurrent.TimeUnit

internal fun formatContinueWatchingProgressLabel(
    progress: WatchProgress,
    hoursMinLeftLabel: String,
    minLeftLabel: String
): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(progress.remainingTime)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> hoursMinLeftLabel.format(hours, minutes)
        else -> minLeftLabel.format(totalMinutes.coerceAtLeast(1))
    }
}
