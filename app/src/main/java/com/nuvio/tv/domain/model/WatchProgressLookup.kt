package com.nuvio.tv.domain.model

/**
 * Picks the watch-progress row that should drive Continue Watching resume.
 * Local playback stores a millisecond position; Trakt/Simkl often only have
 * a percent with position=0. Prefer the precise local row when both exist.
 */
internal object WatchProgressLookup {

    fun isResumable(progress: WatchProgress): Boolean {
        if (progress.isCompleted()) return false
        if (progress.position > 0L) return true
        return progress.isInProgress()
    }

    fun pickResumeProgress(provider: WatchProgress?, local: WatchProgress?): WatchProgress? {
        val resumableProvider = provider?.takeIf(::isResumable)
        val resumableLocal = local?.takeIf(::isResumable)
        if (resumableProvider == null) return resumableLocal
        if (resumableLocal == null) return resumableProvider

        val providerHasPosition = resumableProvider.position > 0L
        val localHasPosition = resumableLocal.position > 0L
        return when {
            localHasPosition && !providerHasPosition -> resumableLocal
            providerHasPosition && !localHasPosition -> resumableProvider
            else -> {
                if (resumableLocal.lastWatched >= resumableProvider.lastWatched) {
                    resumableLocal
                } else {
                    resumableProvider
                }
            }
        }
    }

    fun pickResumeProgressFromCandidates(candidates: List<WatchProgress>): WatchProgress? {
        val resumable = candidates.filter(::isResumable)
        if (resumable.isEmpty()) return null
        val withPosition = resumable.filter { it.position > 0L }
        val pool = withPosition.ifEmpty { resumable }
        return pool.maxWithOrNull(
            compareBy<WatchProgress> { it.lastWatched }
                .thenBy { it.position }
        )
    }
}
