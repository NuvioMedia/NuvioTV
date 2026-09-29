package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress

internal fun resolveNextToWatchLatestProgress(
    latestProgress: WatchProgress?,
    progressEntries: Collection<WatchProgress>,
    isResumable: (WatchProgress) -> Boolean
): WatchProgress? {
    val resumeOverride = progressEntries
        .filter(isResumable)
        .maxWithOrNull(
            compareByDescending<WatchProgress> { it.lastWatched }
                .thenByDescending { it.season ?: 0 }
                .thenByDescending { it.episode ?: 0 }
        )
    return when {
        resumeOverride != null && (
            latestProgress == null ||
                !isResumable(latestProgress) ||
                resumeOverride.lastWatched >= latestProgress.lastWatched
            ) -> resumeOverride
        else -> latestProgress
    }
}
