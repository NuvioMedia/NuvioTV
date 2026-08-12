package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchProgressLookup

/**
 * Pure Continue Watching resume decisions. Extracted so unit tests can reproduce
 * the "resume starts at 0:00" cases without spinning up ExoPlayer.
 */
internal object WatchProgressResumePolicy {

    sealed class ReadySeekDecision {
        data class Seek(val positionMs: Long) : ReadySeekDecision()
        data object KeepPending : ReadySeekDecision()
        data object Clear : ReadySeekDecision()
    }

    fun isResumable(progress: WatchProgress): Boolean = WatchProgressLookup.isResumable(progress)

    fun pickResumeProgress(provider: WatchProgress?, local: WatchProgress?): WatchProgress? =
        WatchProgressLookup.pickResumeProgress(provider, local)

    fun pickResumeProgressFromCandidates(candidates: List<WatchProgress>): WatchProgress? =
        WatchProgressLookup.pickResumeProgressFromCandidates(candidates)

    /**
     * Position to pass into ExoPlayer.setMediaSource / MPV setMedia.
     * Percent-only Trakt/Simkl rows have position=0 and duration=0, so this
     * returns 0 and the real seek must happen once the player knows duration.
     */
    fun resolveInitialResumePosition(saved: WatchProgress): Long {
        if (!isResumable(saved)) return 0L
        return when {
            saved.duration > 0L -> saved.resolveResumePosition(saved.duration)
            saved.position > 0L -> saved.position
            else -> 0L
        }.coerceAtLeast(0L)
    }

    fun shouldKeepPendingAfterInitialPrepare(saved: WatchProgress, initialPositionMs: Long): Boolean {
        if (!isResumable(saved)) return false
        if (initialPositionMs > 0L) return false
        return saved.progressPercent != null || saved.position > 0L
    }

    fun decideReadySeek(
        saved: WatchProgress,
        playerDurationMs: Long,
        isSeekable: Boolean
    ): ReadySeekDecision {
        if (!isResumable(saved)) return ReadySeekDecision.Clear
        if (!isSeekable) return ReadySeekDecision.KeepPending

        val needsDurationForPercent = saved.position <= 0L &&
            saved.progressPercent != null &&
            playerDurationMs <= 0L
        if (needsDurationForPercent) return ReadySeekDecision.KeepPending

        val target = when {
            playerDurationMs > 0L -> saved.resolveResumePosition(playerDurationMs)
            saved.position > 0L -> saved.position
            else -> 0L
        }
        return if (target > 0L) {
            ReadySeekDecision.Seek(target)
        } else {
            ReadySeekDecision.KeepPending
        }
    }
}
