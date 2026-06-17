package com.nuvio.tv.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

// Compose's default marquee velocity (MarqueeDefaults.Velocity) is 30.dp/s, which at our title font
// is only ~3.5 characters/second. Screen-reading research on horizontally scrolling text shows
// comprehension stays high (~95%) up to ~8.5 chars/second (~85 wpm), so 45.dp/s (~5.3 cps) reads
// noticeably faster while keeping a comfortable margin below that rate.
private val MarqueeVelocity = 45.dp

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
    Text(
        text = text,
        modifier = if (focused) {
            modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = MarqueeVelocity)
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
