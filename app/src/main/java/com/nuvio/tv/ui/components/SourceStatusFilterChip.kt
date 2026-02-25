package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors

enum class SourceChipStatus {
    LOADING,
    SUCCESS,
    ERROR
}

data class SourceChipItem(
    val name: String,
    val status: SourceChipStatus
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceStatusFilterChip(
    name: String,
    isSelected: Boolean,
    status: SourceChipStatus = SourceChipStatus.SUCCESS,
    isSelectable: Boolean = true,
    onClick: () -> Unit,
    onFocusSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val isError = status == SourceChipStatus.ERROR
    val isLoading = status == SourceChipStatus.LOADING
    val shouldUseNormalColors = !isError
    val shakeOffsetPx = remember { Animatable(0f) }
    val alphaPulse = remember { Animatable(1f) }

    LaunchedEffect(isError) {
        shakeOffsetPx.snapTo(0f)
        alphaPulse.snapTo(1f)
        if (!isError) return@LaunchedEffect

        alphaPulse.animateTo(
            targetValue = 0.9f,
            animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
        )
        alphaPulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
        )

        // Two gentle shakes (less abrupt than the previous infinite shake/blink loop).
        shakeOffsetPx.animateTo(3f, tween(80, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(-3f, tween(110, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(2f, tween(90, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(-2f, tween(90, easing = FastOutSlowInEasing))
        shakeOffsetPx.animateTo(0f, tween(140, easing = FastOutSlowInEasing))
    }

    val textColor = when {
        isError -> NuvioColors.Error
        isFocused || isSelected -> NuvioColors.OnSecondary
        else -> NuvioColors.TextSecondary
    }

    FilterChip(
        selected = isSelected,
        onClick = { if (isSelectable) onClick() },
        modifier = modifier
            .graphicsLayer {
                alpha = alphaPulse.value
                translationX = shakeOffsetPx.value
            }
            .onFocusChanged {
                val nowFocused = it.isFocused
                isFocused = nowFocused
                if (nowFocused && !isSelected && isSelectable) {
                    onFocusSelect?.invoke()
                }
            },
        colors = FilterChipDefaults.colors(
            containerColor = if (shouldUseNormalColors) NuvioColors.BackgroundCard else NuvioColors.Error.copy(alpha = 0.05f),
            focusedContainerColor = if (shouldUseNormalColors) NuvioColors.Secondary else NuvioColors.Error.copy(alpha = 0.08f),
            selectedContainerColor = if (shouldUseNormalColors) NuvioColors.Secondary else NuvioColors.Error.copy(alpha = 0.07f),
            focusedSelectedContainerColor = if (shouldUseNormalColors) NuvioColors.Secondary else NuvioColors.Error.copy(alpha = 0.09f),
            contentColor = textColor,
            focusedContentColor = textColor,
            selectedContentColor = textColor,
            focusedSelectedContentColor = textColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (isError) NuvioColors.Error.copy(alpha = 0.7f) else NuvioColors.Border),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (isError) NuvioColors.Error.copy(alpha = 0.8f) else NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            ),
            selectedBorder = Border(
                border = BorderStroke(1.dp, if (isError) NuvioColors.Error.copy(alpha = 0.75f) else NuvioColors.Primary),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedSelectedBorder = Border(
                border = BorderStroke(2.dp, if (isError) NuvioColors.Error.copy(alpha = 0.8f) else NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(20.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading) {
                MaterialCircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = if (isFocused || isSelected) NuvioColors.OnSecondary else NuvioColors.TextSecondary,
                    strokeWidth = 1.5.dp
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
        }
    }
}
