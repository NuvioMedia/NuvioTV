package com.nuvio.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

val LocalFastHorizontalNavigationEnabled = compositionLocalOf { false }

/**
 * Throttles D-pad key repeats to prevent HWUI overload and focus jank
 * when a directional key is held down.  Consumes rapid repeats and
 * manually moves focus at a controlled rate.
 *
 * @param horizontalGateMs minimum interval between horizontal repeats
 * @param verticalGateMs   minimum interval between vertical repeats
 * @param scrollableState  optional vertical list driven by the same D-pad.
 *                         Rows outside the composed window are not focusable
 *                         yet, so [FocusDirection] movement can fail even
 *                         though the list still has content in that
 *                         direction. When that happens we scroll one step so
 *                         the neighbouring row composes and retry, instead of
 *                         letting the key repeat walk focus out of the
 *                         content (and get stuck on the tab bar).
 */
fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = 80L,
    verticalGateMs: Long = 80L,
    scrollableState: LazyListState? = null
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val fastHorizontalNavigationEnabled = LocalFastHorizontalNavigationEnabled.current
    val lastRepeatTime = remember { longArrayOf(0L) }
    val scope = rememberCoroutineScope()

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
            val gateMs = if (isVertical) {
                verticalGateMs
            } else if (fastHorizontalNavigationEnabled) {
                48L
            } else {
                horizontalGateMs
            }
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
            // SU sulle righe: lascio gestire la pressione a rowsListUpRecovery,
            // che verifica dove finisce il focus e fa emergere la riga sopra
            // invece di fuggire in barra. Anche a lista in cima il moveFocus
            // alla cieca qui sotto è pericoloso: nelle schermate sezione
            // (Anime/Extra/Calendario) aggancia il rail laterale invece della
            // tab di sezione — che viene gestita dall'intercettazione SU di
            // MainActivity sul tasto non consumato.
            // GIÙ: stesso ragionamento in direzione opposta — il focus search
            // geometrico di Compose aggancia le icone del rail laterale (in
            // overlap X con le righe) invece di restare sull'ultimo elemento:
            // la pressione passa a rowsListDownRecovery, che muove, verifica e
            // riporta il focus dentro se è uscito sul rail.
            if ((direction == FocusDirection.Up || direction == FocusDirection.Down) &&
                scrollableState != null
            ) {
                return@onPreviewKeyEvent false
            }
            if (direction != null) {
                val moved = focusManager.moveFocus(direction)
                val list = scrollableState
                if (!moved && list != null &&
                    (direction == FocusDirection.Up || direction == FocusDirection.Down)
                ) {
                    val up = direction == FocusDirection.Up
                    scope.launch {
                        val info = list.layoutInfo
                        val target = if (up) {
                            (list.firstVisibleItemIndex - 1).coerceAtLeast(0)
                        } else {
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                                ?: list.firstVisibleItemIndex
                            (lastVisible + 1).coerceAtMost(
                                (info.totalItemsCount - 1).coerceAtLeast(0)
                            )
                        }
                        val canMove = if (up) list.canScrollBackward else list.canScrollForward
                        if (canMove) {
                            list.scrollToItem(target)
                            focusManager.moveFocus(direction)
                        }
                    }
                }
            }
            return@onPreviewKeyEvent true
        }
        false
    }
}
