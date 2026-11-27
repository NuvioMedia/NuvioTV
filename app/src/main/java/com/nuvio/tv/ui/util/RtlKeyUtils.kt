package com.nuvio.tv.ui.util

import android.view.KeyEvent

object RtlKeyUtils {
    /**
     * The D-pad key that moves toward the END of a row (where content continues).
     * LTR: right. RTL: left, because the whole layout is mirrored.
     */
    fun endDpadKey(isRtl: Boolean): Int =
        if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

    /** The D-pad key that moves toward the START of a row. LTR: left. RTL: right. */
    fun startDpadKey(isRtl: Boolean): Int =
        if (isRtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT

    /**
     * Returns the appropriate DPAD key to navigate from the search text field
     * to the "Clear search history" button, which sits at the end of the row:
     * right in LTR, left in RTL.
     */
    fun getClearHistoryDpadKey(isRtl: Boolean): Int = endDpadKey(isRtl)
}
