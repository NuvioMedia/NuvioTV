@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.theme.NuvioColors

private const val INDICATOR_FADE_MS = 150
private val IndicatorIconSize = 32.dp
private val IndicatorEdgeInset = 2.dp

@Composable
internal fun BoxScope.SettingsVerticalScrollIndicators(
    state: ScrollableState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.canScrollBackward,
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(top = IndicatorEdgeInset),
        enter = fadeIn(animationSpec = tween(INDICATOR_FADE_MS)),
        exit = fadeOut(animationSpec = tween(INDICATOR_FADE_MS))
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = NuvioColors.TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.size(IndicatorIconSize)
        )
    }
    AnimatedVisibility(
        visible = state.canScrollForward,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = IndicatorEdgeInset),
        enter = fadeIn(animationSpec = tween(INDICATOR_FADE_MS)),
        exit = fadeOut(animationSpec = tween(INDICATOR_FADE_MS))
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = NuvioColors.TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.size(IndicatorIconSize)
        )
    }
}

@Composable
internal fun BoxScope.SettingsHorizontalScrollIndicators(
    state: ScrollableState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.canScrollBackward,
        modifier = modifier
            .align(Alignment.CenterStart)
            .padding(start = IndicatorEdgeInset),
        enter = fadeIn(animationSpec = tween(INDICATOR_FADE_MS)),
        exit = fadeOut(animationSpec = tween(INDICATOR_FADE_MS))
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = NuvioColors.TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.size(IndicatorIconSize)
        )
    }
    AnimatedVisibility(
        visible = state.canScrollForward,
        modifier = modifier
            .align(Alignment.CenterEnd)
            .padding(end = IndicatorEdgeInset),
        enter = fadeIn(animationSpec = tween(INDICATOR_FADE_MS)),
        exit = fadeOut(animationSpec = tween(INDICATOR_FADE_MS))
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NuvioColors.TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.size(IndicatorIconSize)
        )
    }
}
