package com.nuvio.tv.ui.screens.player

/**
 * Scrub / seek step sizes for remote D-pad and media keys.
 *
 * Android TV remotes fire repeated ACTION_DOWN events while a direction
 * key is held (`KeyEvent.repeatCount` increases). Mapping that count to
 * progressively larger steps makes long holds scrub faster without changing
 * the feel of a single tap.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L
    const val STEP_MEDIUM_MS = 20_000L
    const val STEP_LONG_MS = 30_000L
    const val STEP_VERY_LONG_MS = 60_000L

    private const val MEDIUM_REPEAT_THRESHOLD = 3
    private const val LONG_REPEAT_THRESHOLD = 8
    private const val VERY_LONG_REPEAT_THRESHOLD = 15

    /**
     * Returns the seek delta magnitude (always positive) for the given key
     * repeat count. Callers apply the sign for forward / backward.
     */
    fun stepMsForKeyRepeat(repeatCount: Int): Long {
        val count = repeatCount.coerceAtLeast(0)
        return when {
            count >= VERY_LONG_REPEAT_THRESHOLD -> STEP_VERY_LONG_MS
            count >= LONG_REPEAT_THRESHOLD -> STEP_LONG_MS
            count >= MEDIUM_REPEAT_THRESHOLD -> STEP_MEDIUM_MS
            else -> STEP_SHORT_MS
        }
    }

    /** Signed delta for a left/rewind (negative) or right/forward (positive) scrub. */
    fun deltaMsForKeyRepeat(repeatCount: Int, forward: Boolean): Long {
        val step = stepMsForKeyRepeat(repeatCount)
        return if (forward) step else -step
    }
}
