package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval

internal const val SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS = 10_000

internal fun findActiveSkipInterval(
    intervals: List<SkipInterval>,
    positionMs: Long,
): SkipInterval? {
    if (intervals.isEmpty()) return null
    val positionSec = positionMs / 1000.0
    return intervals.find { interval ->
        interval.type != "post-credits" &&
            positionSec >= interval.startTime && positionSec < interval.endTime
    }
}

internal fun nextActiveSkipInterval(
    intervals: List<SkipInterval>,
    positionMs: Long,
): SkipInterval? = findActiveSkipInterval(intervals, positionMs)

internal fun SkipInterval.followingPostCreditsScene(
    intervals: List<SkipInterval>,
    durationMs: Long,
): SkipInterval? {
    if (type != "movie-credits" && type !in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES) return null
    // Explicit post-credits scene from IntroDb
    val explicit = intervals.filter {
        it.type == "post-credits" && it.startTime.isFinite() && it.endTime.isFinite() &&
            it.startTime >= endTime && it.endTime > it.startTime &&
            // A different release can end before the submitted scene does; its start is still playable.
            (durationMs <= 0L || it.startTime * 1000.0 < durationMs)
    }.minByOrNull { it.startTime }
    if (explicit != null) return explicit
    // Heuristic: significant content after credits/outro suggests a post-credits scene.
    if (durationMs > 0L) {
        val creditsEndMs = (endTime * 1000.0).toLong()
        val gapMs = durationMs - creditsEndMs
        if (gapMs > PlayerNextEpisodeRules.POST_OUTRO_AUTOPLAY_GAP_MS) {
            return SkipInterval(
                startTime = endTime,
                endTime = durationMs / 1000.0,
                type = "post-credits",
                provider = "heuristic"
            )
        }
    }
    return null
}

internal fun isSkipIntroButtonVisible(
    hasActiveInterval: Boolean,
    dismissed: Boolean,
    controlsVisible: Boolean,
    autoHidden: Boolean,
): Boolean {
    val shouldShow = hasActiveInterval && (!dismissed || controlsVisible)
    return shouldShow && (!autoHidden || controlsVisible)
}

/**
 * Whether the Skip Intro button may accept D-pad focus.
 *
 * While the subtitle selection overlay is open, Skip Intro stays on screen but
 * must not be focusable — otherwise DPAD_DOWN past the last language/track card
 * escapes the overlay onto Skip Intro (#2874).
 */
internal fun isSkipIntroCanFocus(
    subtitleOverlayVisible: Boolean,
): Boolean = !subtitleOverlayVisible

internal fun skipIntroAutoHideRemainingMs(
    progress: Float,
    totalTimeoutMs: Int = SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS,
): Int = ((1f - progress.coerceIn(0f, 1f)) * totalTimeoutMs).toInt().coerceAtLeast(1)
