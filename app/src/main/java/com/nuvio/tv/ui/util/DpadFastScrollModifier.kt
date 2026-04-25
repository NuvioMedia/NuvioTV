package com.nuvio.tv.ui.util

import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drag velocity for the vertical takeover, expressed in dp per second. Tuned
 * so that holding DPAD_DOWN on the Modern home feels like a long press on a
 * native TV launcher — the list snaps along at a readable pace without
 * tearing on typical TV refresh rates.
 */
private const val DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC = 3200f

/**
 * Maximum idle gap between key events before the drag is torn down on its
 * own. ACTION_UP is the primary release signal; this guard only matters when
 * focus shifts away from the container (e.g. to a system IME) so we never
 * get a matching UP for the DOWN that started the drag.
 */
private const val DEFAULT_END_TIMEOUT_MS = 160L

/**
 * Upper bound on a single frame's delta so a long frame stall (GC, layout
 * thrash) can't teleport the list by hundreds of dp on the next frame.
 * Roughly three frames at 60 Hz.
 */
private const val DEFAULT_MAX_FRAME_DT_SEC = 0.048f

private enum class FastScrollMode { None, Vertical }

/**
 * Adds the Modern-home fast-scroll behaviour to any scrollable container.
 *
 * Matches the inline implementation that shipped in ModernHomeContent and
 * ClassicHomeContent, extracted here so we stop copy-pasting the same key
 * handling into every vertical list.
 *
 * Behaviour:
 *
 * - DPAD_UP / DPAD_DOWN repeats take over [scrollableState] with a
 *   frame-driven coroutine that drags the list at constant velocity while
 *   focus stays frozen on the originating card. On release (ACTION_UP), or
 *   when the list hits its edge, or after [endTimeoutMs] of silence, the
 *   drag ends and [resolveVerticalLanding] is asked for the [FocusRequester]
 *   to hand focus to.
 * - DPAD_LEFT / DPAD_RIGHT repeats tear down any in-flight vertical drag
 *   and then fall through to a chained [dpadRepeatThrottle] (installed
 *   internally) so horizontal navigation keeps stepping one card at a time
 *   at the standard repeat gate — no more duplicated throttle logic.
 * - First-press (non-repeat) DPAD events always fall through so Compose's
 *   default focus navigation handles single-step movement.
 *
 * [onFastScrollingChanged] fires whenever the vertical takeover starts /
 * ends so callers can plumb `LocalFastScrollActive` and hide focus chrome
 * on the cards inside the scrollable while a drag is in progress.
 *
 * [shouldHaltForward] lets the caller veto downward drag when the list has
 * content padding that would otherwise push the last row above the
 * viewport (the Modern home uses a viewport-sized bottom padding for the
 * hero area). Returning `true` both prevents a new drag from starting and
 * breaks the running scroll loop so focus lands on the last row cleanly.
 *
 * @param scrollableState           list / grid state to drag on vertical repeat
 * @param resolveVerticalLanding    invoked on drag end with the direction
 *                                  (`-1` = up, `+1` = down). Return the
 *                                  [FocusRequester] that should receive
 *                                  focus, or `null` to leave focus alone.
 * @param onFastScrollingChanged    observer for the vertical-drag flag
 * @param shouldHaltForward         optional downward halt guard, see above
 * @param horizontalGateMs          min interval between horizontal repeats
 *                                  (passed through to [dpadRepeatThrottle])
 * @param verticalVelocityDpPerSec  drag speed in dp per second
 * @param endTimeoutMs              idle gap before the drag self-terminates
 * @param maxFrameDtSec             per-frame delta clamp (jitter guard)
 */
fun Modifier.dpadVerticalFastScroll(
    scrollableState: ScrollableState,
    resolveVerticalLanding: (sign: Int) -> FocusRequester?,
    onFastScrollingChanged: (Boolean) -> Unit = {},
    shouldHaltForward: () -> Boolean = { false },
    horizontalGateMs: Long = 80L,
    verticalVelocityDpPerSec: Float = DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC,
    endTimeoutMs: Long = DEFAULT_END_TIMEOUT_MS,
    maxFrameDtSec: Float = DEFAULT_MAX_FRAME_DT_SEC,
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Plain atomics instead of mutableState — nothing in the UI observes
    // these directly. The only observable piece of state is the
    // isFastScrolling flag, which we surface via [onFastScrollingChanged].
    val jobRef = remember { AtomicReference<Job?>(null) }
    val endTimerRef = remember { AtomicReference<Job?>(null) }
    val modeRef = remember { AtomicReference(FastScrollMode.None) }
    val directionRef = remember { AtomicInteger(0) }
    val isActiveRef = remember { AtomicReference(false) }

    DisposableEffect(Unit) {
        onDispose {
            jobRef.getAndSet(null)?.cancel()
            endTimerRef.getAndSet(null)?.cancel()
        }
    }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val kc = native.keyCode
        val isHoriz = kc == KeyEvent.KEYCODE_DPAD_LEFT ||
            kc == KeyEvent.KEYCODE_DPAD_RIGHT
        val isVert = kc == KeyEvent.KEYCODE_DPAD_UP ||
            kc == KeyEvent.KEYCODE_DPAD_DOWN

        fun setActive(value: Boolean) {
            if (isActiveRef.getAndSet(value) != value) {
                onFastScrollingChanged(value)
            }
        }

        fun endFastScroll() {
            val mode = modeRef.getAndSet(FastScrollMode.None)
            val direction = directionRef.getAndSet(0)
            jobRef.getAndSet(null)?.cancel()
            endTimerRef.getAndSet(null)?.cancel()
            setActive(false)
            if (mode == FastScrollMode.Vertical) {
                val sign = if (direction == 0) 1 else direction
                resolveVerticalLanding(sign)?.let { runCatching { it.requestFocus() } }
            }
        }

        if (!isHoriz && !isVert) return@onPreviewKeyEvent false

        // Release: stop the drag (landing happens inside endFastScroll) and
        // let default handling proceed so anything else watching ACTION_UP
        // still sees it.
        if (native.action == KeyEvent.ACTION_UP) {
            if (modeRef.get() != FastScrollMode.None) endFastScroll()
            return@onPreviewKeyEvent false
        }

        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

        // First press (not a repeat) — always fall through so Compose's
        // default focus navigation moves focus by exactly one cell. Fast
        // scroll only engages past the system repeat threshold.
        if (native.repeatCount == 0) return@onPreviewKeyEvent false

        if (isHoriz) {
            // Any in-flight vertical drag gets torn down first so the flag
            // doesn't stay latched while the user starts navigating on
            // another axis. The actual horizontal throttle + moveFocus is
            // handled by the chained [dpadRepeatThrottle] below, so we fall
            // through with `false`.
            if (modeRef.get() != FastScrollMode.None) endFastScroll()
            return@onPreviewKeyEvent false
        }

        // Vertical repeat: enter or extend fast-scroll drag mode.
        val sign = if (kc == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
        val needsStart = modeRef.get() != FastScrollMode.Vertical ||
            directionRef.get() != sign ||
            jobRef.get()?.isActive != true

        if (needsStart) {
            jobRef.getAndSet(null)?.cancel()

            // Don't kick off a fresh cycle if nothing can actually move in
            // this direction right now. Without this guard, a user who keeps
            // holding DPAD_DOWN after the list has landed on the last row
            // would see the coroutine immediately break, invoke
            // endFastScroll, flip the flag on-then-off every repeat, and
            // the focus chrome would flicker at the key-repeat rate.
            val atScrollEdge = (sign > 0 && !scrollableState.canScrollForward) ||
                (sign < 0 && !scrollableState.canScrollBackward)
            val halted = sign > 0 && shouldHaltForward()
            if (atScrollEdge || halted) {
                if (modeRef.get() != FastScrollMode.None) endFastScroll()
                return@onPreviewKeyEvent true
            }

            modeRef.set(FastScrollMode.Vertical)
            directionRef.set(sign)
            setActive(true)

            val velocityPxPerSec = with(density) { verticalVelocityDpPerSec.dp.toPx() }

            jobRef.set(
                scope.launch {
                    try {
                        scrollableState.scroll {
                            var lastFrame = withFrameNanos { it }
                            while (true) {
                                val now = withFrameNanos { it }
                                val dtSec = ((now - lastFrame) / 1_000_000_000f)
                                    .coerceAtMost(maxFrameDtSec)
                                lastFrame = now

                                if (sign > 0 && shouldHaltForward()) break

                                val delta = sign * velocityPxPerSec * dtSec
                                val consumed = scrollBy(delta)
                                if (consumed == 0f && delta != 0f) break
                            }
                        }
                        // Loop exited because the list can't move further
                        // (or the forward halt fired). Land focus immediately
                        // so the user sees the scroll stop cleanly rather
                        // than waiting for ACTION_UP or the safety timer.
                        endFastScroll()
                    } catch (_: CancellationException) {
                        // expected on release / axis change
                    }
                }
            )
        }

        // (Re)arm the safety timer — if ACTION_UP is ever lost (system IME,
        // foreground change) we still end the drag after a short idle so
        // the list doesn't keep scrolling silently with no focus chrome.
        endTimerRef.getAndSet(null)?.cancel()
        endTimerRef.set(
            scope.launch {
                delay(endTimeoutMs)
                endFastScroll()
            }
        )

        true
    }.dpadRepeatThrottle(
        horizontalGateMs = horizontalGateMs,
        // Vertical repeats are fully consumed by the preview handler above,
        // so they never reach the throttle. `Long.MAX_VALUE` keeps the
        // throttle as a pure horizontal gate without any accidental
        // vertical side effects if a key somehow slips past.
        verticalGateMs = Long.MAX_VALUE,
    )
}
