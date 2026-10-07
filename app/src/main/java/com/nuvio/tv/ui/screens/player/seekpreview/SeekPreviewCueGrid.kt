package com.nuvio.tv.ui.screens.player.seekpreview

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The time window of a single seek-preview cue, expressed in the **playback** timebase.
 *
 * Seekr sprite sheets hold one frame per cue (a ~10 second grid today), so a preview
 * thumbnail never represents an exact millisecond — it represents the whole window
 * [startMs]..[endMs]. Resolving that window and keeping it in player state is what lets the
 * scrubber move in whole cues instead of promising a precision the sprite sheet cannot back
 * up.
 *
 * The cue times reported by the SDK are on the *preview* timeline; callers must subtract the
 * active sync offset before constructing this so both ends are directly comparable with
 * playback positions.
 */
data class SeekPreviewCue(
    val startMs: Long,
    val endMs: Long
) {
    /** Length of the cue window; the effective scrub granularity. */
    val durationMs: Long get() = endMs - startMs

    val isValid: Boolean get() = durationMs > 0L

    /** True when [positionMs] falls inside this window (start-inclusive, end-exclusive). */
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs

    /**
     * True when this is the cue whose frame best represents [positionMs] — the one the
     * preview centres on.
     *
     * A cue's frame is captured at its *start*, so the containing cue is not the closest one
     * for positions in the back half of the window: at 18:29 the cue covering 18:20..18:30
     * holds an 18:20 frame, nine seconds stale, while the next cue's 18:30 frame is one second
     * away. Preferring the nearer frame roughly halves the worst-case error and, since the
     * playhead sits between the two, keeps the filmstrip reading symmetrically around it.
     *
     * The window is therefore shifted back by half a cue, but its trailing edge is left at
     * [endMs] rather than pulled in: the last cue of a track has no successor to hand over to,
     * so it has to keep representing the run-out to the end of the media.
     *
     * This approximates the hand-over using *this* cue's length, where [prefersSuccessorFor]
     * decides it exactly using the preceding cue's midpoint. The two agree whenever
     * neighbouring cues are the same length, which is every cue on today's uniform 10s grid.
     * Once the generator writes real keyframe times as cue starts, cue lengths will vary and
     * the two will disagree near a length change — harmlessly, since a cue that fails this
     * check merely falls back to free stepping, but this is the place to revisit if grid
     * locking is to stay exact on a non-uniform grid.
     */
    fun represents(positionMs: Long): Boolean =
        positionMs >= startMs - durationMs / 2 && positionMs < endMs

    /**
     * True when the *next* cue's frame is closer to [positionMs] than this cue's own — i.e.
     * when [positionMs] is past this window's midpoint.
     *
     * Together with the window itself this is the complete input to the preview's re-centring
     * decision, so replaying it is how the host knows a cached frame is still the right one.
     */
    fun prefersSuccessorFor(positionMs: Long): Boolean =
        positionMs - startMs > endMs - positionMs
}

/**
 * Grid-locked scrubbing: turns a free-form seek delta into a move of whole preview cues.
 *
 * ### Why
 * The scrubber can address every millisecond, but previews exist only once per cue. Scrubbing
 * freely therefore lands the user on positions that no thumbnail describes, and the frame they
 * were shown can be several seconds away from where they end up — worst of all across a shot
 * boundary, where "a few seconds out" reads as "the wrong scene". Rather than approximating
 * the missing frames, this narrows the claim: every position the user can stop on is a
 * position we hold a real frame for, so preview and playback always agree.
 *
 * ### How
 * The cue window resolved for the frame currently on screen (see [SeekPreviewCue]) is the only
 * input needed — no global cue table, which the SDK does not expose. Stepping forward one cue
 * is "seek to this cue's end", which is exactly the next cue's start; larger steps extrapolate
 * from the cue's own length. Extrapolation only ever picks the *candidate* position: once the
 * thumbnail for it resolves, the true cue start comes back and [alignedTargetMs] snaps the
 * pending position onto it, so a non-uniform cue grid self-corrects within one frame and can
 * never drift or stall.
 */
object SeekPreviewCueStepper {

    /**
     * The position to scrub to when the user asks to move by [deltaMs] from [fromMs].
     *
     * Steps are measured from the cue the preview is *showing* (see [SeekPreviewCue.represents]),
     * not the one containing [fromMs], so one press always advances the filmstrip by exactly one
     * frame. From 18:29 — where the centre frame is 18:30 — forward lands on 18:40 and back on
     * 18:20, rather than nudging a single second onto the frame already on screen.
     *
     * Falls back to plain [fromMs] + [deltaMs] whenever grid-locking cannot be justified —
     * no cue resolved yet, a degenerate cue, or a cue that does not describe [fromMs]
     * (which happens at the very ends of the track, where the SDK clamps its lookup). Keeping
     * the free-form path for those cases preserves the user's ability to reach 0 and
     * [durationMs] exactly.
     */
    fun targetMs(
        cue: SeekPreviewCue?,
        fromMs: Long,
        deltaMs: Long,
        durationMs: Long
    ): Long {
        val maxMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val free = { (fromMs + deltaMs).coerceIn(0L, maxMs) }
        if (cue == null || !cue.isValid || !cue.represents(fromMs) || deltaMs == 0L) return free()

        val cueMs = cue.durationMs
        val steps = (abs(deltaMs).toDouble() / cueMs).roundToLong().coerceAtLeast(1L)
        val target = if (deltaMs > 0L) {
            // The cue's end *is* the next cue's start, so a single step is exact.
            cue.endMs + (steps - 1L) * cueMs
        } else {
            cue.startMs - steps * cueMs
        }
        return target.coerceIn(0L, maxMs)
    }

    /**
     * The position [pendingMs] should be corrected to now that [cue] has resolved, or `null`
     * to leave it untouched.
     *
     * Snapping the pending position onto the resolved cue's start is what makes the displayed
     * time describe the frame on screen rather than an arbitrary point near it. It is a no-op
     * once aligned, so it converges immediately and does not fight the user.
     *
     * It is deliberately skipped at the media boundaries. A cue window is expressed in the
     * playback timebase, so a non-zero preview sync offset routinely pushes the first cue's
     * start below `0` or the last cue's end past [durationMs]; snapping inside such a cue
     * would drag the pending position out of the seekable range, or pull it a full cue back
     * from an end the user had just reached — making 0 and [durationMs] unreachable and
     * leaving the committed seek disagreeing with the time on screen.
     */
    fun alignedTargetMs(cue: SeekPreviewCue?, pendingMs: Long?, durationMs: Long): Long? {
        if (cue == null || !cue.isValid || pendingMs == null) return null
        if (!cue.represents(pendingMs)) return null
        // The ends of the media are destinations in their own right.
        if (pendingMs <= 0L) return null
        if (durationMs > 0L && pendingMs >= durationMs) return null

        val target = cue.startMs
        if (target < 0L) return null
        if (durationMs > 0L && target > durationMs) return null
        if (target == pendingMs) return null
        return target
    }
}
