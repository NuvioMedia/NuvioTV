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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.util.contentLayoutDirection

private val MarqueeVelocity = 45.dp
internal const val MarqueeIterations = 3

/**
 * Single-line text that scrolls (marquees) horizontally while [focused] if the content overflows,
 * and otherwise ellipsizes.
 *
 * The layout direction is derived from the text's own content (via [contentLayoutDirection]), so RTL titles
 * marquee correctly even when the app's ambient layout direction is LTR, and vice versa.
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
    val currentDirection = LocalLayoutDirection.current
    val textDirection = remember(text) { text.contentLayoutDirection() }
    val needsDirectionOverride = textDirection != currentDirection

    val textModifier = if (focused) {
        modifier.basicMarquee(iterations = MarqueeIterations, velocity = MarqueeVelocity)
    } else {
        modifier
    }
    val textOverflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis

    val content = @Composable {
        Text(
            text = text,
            modifier = textModifier,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = textOverflow,
            textAlign = textAlign,
        )
    }

    if (needsDirectionOverride) {
        CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
            content()
        }
    } else {
        content()
    }
}
