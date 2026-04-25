package com.nuvio.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Throttles D-pad key repeats to prevent HWUI overload and focus jank
 * when a directional key is held down.  Consumes rapid repeats and
 * manually moves focus at a controlled rate.
 *
 * @param horizontalGateMs minimum interval between horizontal repeats
 * @param verticalGateMs   minimum interval between vertical repeats
 */
fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = 80L,
    verticalGateMs: Long = 112L
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val lastRepeatTime = remember { longArrayOf(0L) }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN &&
            native.repeatCount > 0 &&
            (native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP
            val gateMs = if (isVertical) verticalGateMs else horizontalGateMs
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatTime[0] < gateMs) {
                return@onPreviewKeyEvent true
            }
            lastRepeatTime[0] = now
            val direction = when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (direction != null) focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
        false
    }
}
