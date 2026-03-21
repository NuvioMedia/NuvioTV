package com.nuvio.tv.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class PosterCardStyle(
    val width: Dp = 126.dp,
    val height: Dp = 189.dp,
    val focusedBorderWidth: Dp = 2.dp,
    val focusedScale: Float = 1.02f
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()
}
