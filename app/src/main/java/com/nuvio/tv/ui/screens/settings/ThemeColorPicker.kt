package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.formatHexColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

private val colorSwatches = listOf(
    0xFF6B6B, 0xFFB37A, 0xFFD45C, 0x22D37C, 0x4DE3FF,
    0x3185F5, 0xB75AFF, 0xEC70A9, 0xFFFFFF
)

@Composable
internal fun ThemeColorPicker(
    color: Int,
    colorIndex: Int,
    onColorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var hsv by remember(colorIndex) { mutableStateOf(color.toHsv()) }
    var emittedColor by remember(colorIndex) { mutableStateOf(color) }
    val firstSwatchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(color, colorIndex) {
        if (color != emittedColor) {
            hsv = color.toHsv()
            emittedColor = color
        }
    }

    fun updateChannel(channel: Int, value: Float) {
        hsv = hsv.copyOf().also { it[channel] = value }
        emittedColor = Color.hsv(hsv[0], hsv[1], hsv[2]).toArgb() and 0xFFFFFF
        onColorChanged(emittedColor)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().settingsOptionRow(firstSwatchFocusRequester),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            colorSwatches.forEachIndexed { index, swatch ->
                Card(
                    onClick = { onColorChanged(swatch) },
                    modifier = Modifier
                        .size(30.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstSwatchFocusRequester) else Modifier)
                        .semantics { contentDescription = formatHexColor(swatch) },
                    shape = CardDefaults.shape(CircleShape),
                    colors = CardDefaults.colors(
                        containerColor = Color(swatch or 0xFF000000.toInt()),
                        focusedContainerColor = Color(swatch or 0xFF000000.toInt())
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CircleShape)
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.15f, pressedScale = 1f)
                ) { Box(Modifier.fillMaxSize()) }
            }
        }

        ColorChannelSlider(
            title = stringResource(R.string.custom_theme_hue),
            value = hsv[0].roundToInt(),
            maximum = 359,
            step = 3,
            valueLabel = "${hsv[0].roundToInt()}°",
            brush = remember {
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })
            },
            onValueChange = { updateChannel(0, it.toFloat()) }
        )
        ColorChannelSlider(
            title = stringResource(R.string.custom_theme_saturation),
            value = (hsv[1] * 100).roundToInt(),
            maximum = 100,
            valueLabel = "${(hsv[1] * 100).roundToInt()}%",
            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hsv[0], 1f, 1f))),
            onValueChange = { updateChannel(1, it / 100f) }
        )
        ColorChannelSlider(
            title = stringResource(R.string.custom_theme_brightness),
            value = (hsv[2] * 100).roundToInt(),
            maximum = 100,
            valueLabel = "${(hsv[2] * 100).roundToInt()}%",
            brush = Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hsv[0], hsv[1], 1f))),
            onValueChange = { updateChannel(2, it / 100f) }
        )
    }
}

@Composable
private fun ColorChannelSlider(
    title: String,
    value: Int,
    maximum: Int,
    valueLabel: String,
    brush: Brush,
    onValueChange: (Int) -> Unit,
    step: Int = 1
) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val direction = when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (native.action == KeyEvent.ACTION_DOWN) {
                    val increment = if (native.repeatCount > 6) step * 3 else step
                    onValueChange((value + direction * increment).coerceIn(0, maximum))
                }
                true
            }
            .semantics {
                contentDescription = title
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..maximum.toFloat())
                setProgress { target ->
                    onValueChange(target.roundToInt().coerceIn(0, maximum))
                    true
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextPrimary)
                Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .drawWithContent {
                        drawContent()
                        val radius = 6.dp.toPx()
                        val x = radius + (size.width - radius * 2) * value / maximum
                        drawCircle(Color.Black.copy(alpha = 0.6f), radius + 1.dp.toPx(), Offset(x, size.height / 2))
                        drawCircle(Color.White, radius, Offset(x, size.height / 2))
                    }
                    .clip(CircleShape)
                    .background(brush)
            )
        }
    }
}

private fun Int.toHsv(): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(this or 0xFF000000.toInt(), it)
}
