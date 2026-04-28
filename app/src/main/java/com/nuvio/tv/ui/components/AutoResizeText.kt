package com.nuvio.tv.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle

/**
 * Single-line text that shrinks its font size to fit the available width
 * instead of truncating with an ellipsis. Backed by Compose's official
 * BasicText autoSize support.
 */
@Composable
fun AutoResizeText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 9.sp,
    textAlign: TextAlign? = null
) {
    val maxFontSize = if (style.fontSize.type == TextUnitType.Sp) style.fontSize else 14.sp
    val autoSize = remember(minFontSize, maxFontSize) {
        TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 1.sp
        )
    }
    val mergedStyle = remember(style, textAlign) {
        if (textAlign != null) style.copy(textAlign = textAlign) else style
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = mergedStyle,
        color = ColorProducer { color },
        autoSize = autoSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}
