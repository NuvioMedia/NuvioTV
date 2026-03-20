package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.WatchProgress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal fun formatContinueWatchingProgressLabel(
    progress: WatchProgress,
    resumeLabel: String,
    percentWatchedLabel: String,
    hoursMinLeftLabel: String,
    minLeftLabel: String
): String {
    if (progress.duration <= 0L) {
        val percentWatched = (progress.progressPercentage * 100f)
            .roundToInt()
            .coerceIn(0, 100)
        return if (percentWatched > 0) {
            percentWatchedLabel.format(percentWatched)
        } else {
            resumeLabel
        }
    }

    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(progress.remainingTime)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> hoursMinLeftLabel.format(hours, minutes)
        else -> minLeftLabel.format(totalMinutes.coerceAtLeast(1))
    }
}
