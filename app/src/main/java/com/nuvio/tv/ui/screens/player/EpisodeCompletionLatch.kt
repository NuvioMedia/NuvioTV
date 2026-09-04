package com.nuvio.tv.ui.screens.player

/**
 * Guards against completing the current item more than once.
 *
 * [claim] before any suspending work. Splitting the read from the write across a suspension is
 * what this type exists to prevent: a reset can land in that gap, so the write applies to whatever
 * plays next and suppresses its completion. There is no API here that lets that shape be written.
 *
 * [reset] runs from resetLoadingOverlayForNewStream, so the lifetime is a stream rather than an
 * episode: a same-episode source or engine switch also clears it. That is pre-existing and benign,
 * because WatchProgressRepository.markAsCompleted is idempotent, but it means a claim guards one
 * stream's completion write, not one episode's.
 *
 * Confined to the player's main-thread callbacks and its [kotlinx.coroutines.MainScope]-dispatched
 * jobs, so a plain field is enough. An atomic would suggest cross-thread use that does not exist,
 * and would not help: the property that matters is no suspension between the check and the
 * mutation, not lock-free access.
 */
internal class EpisodeCompletionLatch {

    private var claimed = false

    /** True only for the caller that should write the completion. */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }

    fun reset() {
        claimed = false
    }
}
