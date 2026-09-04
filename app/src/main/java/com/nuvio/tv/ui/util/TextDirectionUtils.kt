package com.nuvio.tv.ui.util

import androidx.compose.ui.text.style.TextDirection

/**
 * Detects a string's own reading direction from its first strong-directional character
 * (Unicode bidi rules P2/P3), independent of the app's ambient UI locale/LayoutDirection.
 */
fun String.contentTextDirection(): TextDirection {
    for (char in this) {
        val directionality = Character.getDirectionality(char)
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return TextDirection.Rtl
        }
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return TextDirection.Ltr
        }
    }
    return TextDirection.Ltr
}

/** True if the string's own content direction (see [contentTextDirection]) is RTL. */
fun String.isContentRtl(): Boolean = contentTextDirection() == TextDirection.Rtl
