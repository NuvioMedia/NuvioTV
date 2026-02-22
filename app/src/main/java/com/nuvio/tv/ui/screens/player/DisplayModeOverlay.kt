@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ROW_HEIGHT = 18.dp
private val ROW_GAP = 2.dp

@Composable
fun DisplayModeOverlay(
    info: DisplayModeInfo?,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (info == null) return

    val items = listOf(
        "Refresh" to formatHz(info.refreshRate),
        "Resolution" to "${info.width}x${info.height}"
    )
    val count = items.size
    val totalLineHeight = (ROW_HEIGHT.value * count) + (ROW_GAP.value * (count - 1))

    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(info) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible, info) {
        if (isVisible && !animating) {
            animating = true

            containerAlpha.animateTo(1f, tween(300))
            lineHeightFraction.animateTo(1f, tween(400, easing = FastOutSlowInEasing))

            for (i in 0 until count) {
                delay(80)
                itemAlphas[i].animateTo(1f, tween(200))
            }

            delay(5000)

            for (i in (count - 1) downTo 0) {
                delay(60)
                itemAlphas[i].animateTo(0f, tween(150))
            }

            delay(100)
            lineHeightFraction.animateTo(0f, tween(300, easing = FastOutSlowInEasing))

            delay(200)
            containerAlpha.animateTo(0f, tween(200))

            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    if (containerAlpha.value <= 0f) return

    Column(
        modifier = modifier
            .alpha(containerAlpha.value)
            .padding(end = 32.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        val statusMessage = info.statusMessage?.takeIf { it.isNotBlank() }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                fontSize = 11.sp,
                color = NuvioColors.Secondary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                horizontalAlignment = Alignment.End
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .height(ROW_HEIGHT)
                            .alpha(itemAlphas.getOrNull(index)?.value ?: 0f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.first,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End
                        )
                        Text(
                            text = " - ",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f),
                        )
                        Text(
                            text = item.second,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((totalLineHeight * lineHeightFraction.value).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(NuvioColors.Secondary)
            )
        }
    }
}

private fun formatHz(rate: Float): String {
    val rounded = (rate * 1000f).roundToInt() / 1000f
    val whole = rounded.roundToInt()
    val display = if (abs(rounded - whole.toFloat()) < 0.01f) {
        whole.toString()
    } else {
        String.format("%.3f", rounded)
    }
    return "$display Hz"
}
