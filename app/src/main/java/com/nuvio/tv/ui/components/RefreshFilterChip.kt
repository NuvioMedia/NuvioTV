package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay as coroutineDelay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RefreshFilterChip(
    onClick: () -> Unit,
    isLoading: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentColor = if (isFocused) {
        NuvioTheme.colors.OnSecondary
    } else {
        NuvioTheme.colors.TextSecondary
    }

    val rotationAnimatable = remember { Animatable(0f) }
    var hasCompletedFullRotation by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            // Spin continuously while loading
            coroutineDelay(100)
            hasCompletedFullRotation = false
            while (true) {
                val remaining = 360f - rotationAnimatable.value
                rotationAnimatable.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(
                        durationMillis = (remaining / 360f * 1000).toInt(),
                        easing = LinearEasing
                    )
                )
                hasCompletedFullRotation = true
                rotationAnimatable.snapTo(0f)
            }
        } else {
            // Complete current rotation then stop
            if (hasCompletedFullRotation && rotationAnimatable.value > 0f) {
                val remaining = 360f - rotationAnimatable.value
                rotationAnimatable.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(
                        durationMillis = (remaining / 360f * 1000).toInt(),
                        easing = LinearEasing
                    )
                )
            }
            rotationAnimatable.snapTo(0f)
            hasCompletedFullRotation = false
        }
    }

    FilterChip(
        selected = false,
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            isFocused = it.isFocused
            onFocusChanged(it.isFocused)
        },
        colors = FilterChipDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = contentColor,
            focusedContentColor = contentColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(20.dp))
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.cd_refresh),
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = rotationAnimatable.value },
            tint = contentColor
        )
    }
}
