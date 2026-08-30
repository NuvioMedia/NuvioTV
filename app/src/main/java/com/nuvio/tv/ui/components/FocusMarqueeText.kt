package com.nuvio.tv.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

// Compose's default marquee velocity (MarqueeDefaults.Velocity) is 30.dp/s, which at our title font
// is only ~3.5 characters/second. Screen-reading research on horizontally scrolling text shows
// comprehension stays high (~95%) up to ~8.5 chars/second (~85 wpm), so 45.dp/s (~5.3 cps) reads
// noticeably faster while keeping a comfortable margin below that rate.
private val MarqueeVelocity = 45.dp

// A marquee is an animation: it wakes the Compose frame clock ~50 times a second for as long as it
// runs, and on a TV the focus can rest on the same row or card for hours. Unbounded
// (Int.MAX_VALUE) that cost never ends -- a Settings sidebar left on "Content & Discovery" measured
// 1460 frames and 271 CPU ticks over 30s on a Fire TV Cube, and would have gone on doing that all
// night. Three passes is enough to read a long label, and matches what the platform has always
// defaulted to for TextView (android:marqueeRepeatLimit="3"); after that the text rests and the
// screen goes quiet. Moving focus away and back replays it, because the modifier only exists while
// the item is focused.
internal const val MarqueeIterations = 3

/**
 * Returns true if the first strongly-directional character in this string is RTL (Hebrew, Arabic,
 * etc.), false if it's LTR. Digits, punctuation, and spaces are skipped since they have no
 * inherent direction. Same "first-strong" heuristic already used by [P2pConsentDialog].
 */
private fun String.isRtl(): Boolean {
    for (char in this) {
        val directionality = Character.getDirectionality(char)
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return true
        }
        if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return false
        }
    }
    return false
}

/**
 * Single-line text that scrolls (marquees) horizontally while [focused] if the content overflows,
 * and otherwise ellipsizes. Lets long titles/labels become fully readable when their card or row is
 * focused, while staying visually identical to a normal ellipsized [Text] when unfocused.
 *
 * Scrolling only happens while [focused] and when the text actually overflows (Compose's
 * [basicMarquee] is a no-op when it already fits).
 */
@Composable
fun FocusMarqueeText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    val marqueeText: @Composable () -> Unit = {
        Text(
            text = text,
            modifier = if (focused) {
                modifier.basicMarquee(iterations = MarqueeIterations, velocity = MarqueeVelocity)
            } else {
                modifier
            },
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
    }

    // Text alignment, ellipsis side, and basicMarquee's scroll direction all follow
    // LocalLayoutDirection, which reflects the app/UI locale (e.g. Hebrew -> Rtl) rather than
    // this particular string's script. Override it per-text so an English title inside a
    // Hebrew/Arabic UI always rests and scrolls from the left (its own reading direction)
    // instead of the right, and vice versa for an RTL title in an LTR UI. This is applied
    // unconditionally (not just while focused) so the resting/ellipsized state already matches
    // where the marquee will anchor once focused, and focusing never causes the title to jump
    // from one side of the card to the other.
    val textDirection = remember(text) {
        if (text.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
        marqueeText()
    }
}
