package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.rememberPulsingFocusBorderColor

/**
 * A standard Nuvio button with a built-in pulsing focus border.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.colors(
        containerColor = NuvioColors.BackgroundCard,
        contentColor = NuvioColors.TextPrimary,
        focusedContainerColor = NuvioColors.FocusBackground,
        focusedContentColor = NuvioColors.Primary
    ),
    content: @Composable RowScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animatedBorderColor = rememberPulsingFocusBorderColor(isFocused = isFocused)

    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
        enabled = enabled,
        shape = ButtonDefaults.shape(shape),
        colors = colors,
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, animatedBorderColor),
                shape = shape
            )
        ),
        content = content
    )
}
