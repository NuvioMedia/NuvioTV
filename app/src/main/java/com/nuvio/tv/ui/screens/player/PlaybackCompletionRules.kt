package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.WatchProgress

/**
 * Decides whether leaving an item counts as having finished it.
 *
 * Nothing below [PROVIDER_COMPLETION_PERCENT] can qualify, so local completion never precedes the
 * provider's. Above that floor an exit qualifies on remaining time or on the credits having
 * started, with the percentage as a fallback.
 */
internal object PlaybackCompletionRules {

    /** Fallback for exits, not a ceiling: the rules below can qualify an exit under it. Derived
     * from [WatchProgress.COMPLETED_THRESHOLD] so it tracks that constant. */
    const val FALLBACK_COMPLETION_PERCENT = WatchProgress.COMPLETED_THRESHOLD * 100f

    /** Remaining time that reads as "the episode is over", fixed rather than a percentage so it
     * means the same on a 22 minute episode and a 60 minute one. Only opens a band under
     * [FALLBACK_COMPLETION_PERCENT] between 20 and 40 minutes of runtime. */
    const val COMPLETION_REMAINING_MS = 4 * 60_000L

    /** [positionMs] is null when the player cannot report a position, which never completes. */
    fun resolveExitCompletion(
        leavesCurrentItem: Boolean,
        positionMs: Long?,
        durationMs: Long,
        skipIntervals: List<SkipInterval> = emptyList()
    ): Boolean {
        if (!leavesCurrentItem) return false
        val position = positionMs ?: return false
        return isFinishedOnExit(position, durationMs, skipIntervals)
    }

    fun isFinishedOnExit(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval> = emptyList()
    ): Boolean {
        if (durationMs <= 0L || positionMs <= 0L) return false
        val percent = (positionMs.toDouble() / durationMs.toDouble()) * 100.0
        if (percent < PROVIDER_COMPLETION_PERCENT) return false
        if (percent >= FALLBACK_COMPLETION_PERCENT) return true
        if (durationMs - positionMs <= COMPLETION_REMAINING_MS) return true
        return hasEnteredTerminalEnding(positionMs, durationMs, skipIntervals)
    }

    /**
     * True once playback has crossed the start of a terminal ending. Being past the start is the
     * signal; playback need not still be inside the segment.
     *
     * Segments are evaluated individually, so this does not rely on
     * SkipIntroRepository.mergeByPriority keeping one interval per category.
     */
    private fun hasEnteredTerminalEnding(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>
    ): Boolean {
        val positionSeconds = positionMs / 1_000.0
        return skipIntervals.any { segment ->
            isTerminalEndingSegment(segment, durationMs) && positionSeconds >= segment.startTime
        }
    }

    /**
     * Whether a segment is credits running to the end of the file, judged on the annotation alone.
     *
     * It must end within [COMPLETION_REMAINING_MS] of the file end and run no longer than that.
     * The length is a deliberate cutoff, not something the metadata asserts: it separates credits
     * from an arbitrary span that happens to reach the file end, at the cost of a genuinely
     * longer ending. Open-ended animeskip intervals fail both bounds.
     *
     * Non-finite times are rejected explicitly. The comparisons would reject them anyway, but
     * malformed metadata is what this guards against, so the check is visible.
     */
    private fun isTerminalEndingSegment(segment: SkipInterval, durationMs: Long): Boolean {
        if (segment.type !in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES) return false
        if (!segment.startTime.isFinite() || !segment.endTime.isFinite()) return false
        if (segment.startTime < 0.0 || segment.startTime > segment.endTime) return false

        val fileEndSeconds = durationMs / 1_000.0
        val windowSeconds = COMPLETION_REMAINING_MS / 1_000.0
        if (segment.endTime - segment.startTime > windowSeconds) return false
        return segment.endTime >= fileEndSeconds - windowSeconds &&
            segment.endTime <= fileEndSeconds + windowSeconds
    }
}
