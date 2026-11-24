package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRatings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

private const val StarCount = 5

@Composable
internal fun RatingStars(
    value: Int,
    onValueChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 48.dp,
    focusRequester: FocusRequester? = null
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    var cascadeFrom by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var confirmArmed by remember { mutableStateOf(false) }
    val description = stringResource(R.string.rating_value, value.toString())
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    val container by animateColorAsState(
        targetValue = if (focused) NuvioTheme.colors.FocusBackground.copy(alpha = 0.32f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "rating_stars_container"
    )
    val border by animateColorAsState(
        targetValue = if (focused) NuvioTheme.colors.FocusRing else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "rating_stars_border"
    )

    fun select(next: Int) {
        val clamped = next.coerceIn(1, TrackingRatings.MAX)
        if (clamped == currentValue) return
        cascadeFrom = min(currentValue, clamped) / 2
        currentOnValueChange(clamped)
    }

    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) confirmArmed = false
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                val step = when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
                    else -> 0
                }
                when {
                    step != 0 -> {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN) select(currentValue + step)
                        true
                    }
                    native.keyCode in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            val digit = native.keyCode - AndroidKeyEvent.KEYCODE_0
                            select(if (digit == 0) TrackingRatings.MAX else digit)
                        }
                        true
                    }
                    isRatingSelectKey(native.keyCode) -> {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            confirmArmed = true
                        } else if (native.action == AndroidKeyEvent.ACTION_UP && confirmArmed) {
                            confirmArmed = false
                            currentOnConfirm()
                        }
                        true
                    }
                    else -> false
                }
            }
            .semantics(mergeDescendants = true) {
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = 0f..TrackingRatings.MAX.toFloat(),
                    steps = TrackingRatings.MAX - 1
                )
                setProgress { target ->
                    select(target.roundToInt())
                    true
                }
            }
            .focusable()
            .background(container, shape)
            .border(NuvioTheme.spacing.xxs, border, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(starSize / 6)
    ) {
        repeat(StarCount) { index ->
            Star(
                fraction = (value / 2f - index).coerceIn(0f, 1f),
                delayMillis = (index - cascadeFrom).coerceAtLeast(0) * 40L,
                size = starSize,
                rtl = rtl,
                filledColor = NuvioTheme.colors.Secondary,
                emptyColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f)
            )
        }
    }
}

@Composable
private fun Star(
    fraction: Float,
    delayMillis: Long,
    size: Dp,
    rtl: Boolean,
    filledColor: Color,
    emptyColor: Color
) {
    val fill by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "rating_star_fill"
    )
    val scale = remember { Animatable(1f) }
    var settled by remember { mutableFloatStateOf(fraction) }

    LaunchedEffect(fraction) {
        val grew = fraction > settled
        settled = fraction
        if (!grew) return@LaunchedEffect
        delay(delayMillis)
        scale.animateTo(1.2f, tween(durationMillis = 90, easing = FastOutSlowInEasing))
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = emptyColor,
            modifier = Modifier.fillMaxSize()
        )
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = filledColor,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val width = this.size.width * fill
                    clipRect(
                        left = if (rtl) this.size.width - width else 0f,
                        right = if (rtl) this.size.width else width
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
        )
    }
}

private fun isRatingSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
