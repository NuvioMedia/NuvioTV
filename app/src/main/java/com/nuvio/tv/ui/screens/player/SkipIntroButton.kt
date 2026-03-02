@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

/**
 * Skip Intro/Outro/Recap button for the player.
 * Appears at bottom-left when playback is within a skip interval.
 * Auto-hides after 15 seconds. Focusable for D-pad navigation.
 */
@Composable
fun SkipIntroButton(
    interval: SkipInterval?,
    dismissed: Boolean,
    controlsVisible: Boolean,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit = {},
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    var lastType by remember { mutableStateOf(interval?.type) }
    if (interval != null) lastType = interval.type
    val shouldShow = interval != null && (!dismissed || controlsVisible)

    var autoHidden by remember { mutableStateOf(false) }
    var manuallyDismissed by remember { mutableStateOf(false) }
    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: internalFocusRequester
    var isFocused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }

    // Reset auto-hide and progress when interval changes
    LaunchedEffect(interval?.startTime, interval?.type) {
        autoHidden = false
        manuallyDismissed = false
        progress.snapTo(0f)
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) {
            if (!manuallyDismissed) {
                autoHidden = false
                progress.snapTo(0f)
            }
        } else {
            manuallyDismissed = true
        }
    }

    // Auto-hide after 10 seconds — pause countdown while controls are visible
    LaunchedEffect(shouldShow, autoHidden, controlsVisible) {
        if (shouldShow && !autoHidden && !controlsVisible) {
            progress.animateTo(1f, animationSpec = tween(
                durationMillis = ((1f - progress.value) * 10000).toInt().coerceAtLeast(1),
                easing = LinearEasing
            ))
            autoHidden = true
        }
    }

    // Re-show when controls become visible while auto-hidden — but don't restart countdown
    // When controls hide again and we were auto-hidden, stay hidden
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible && autoHidden) {
            // controls closed, stay hidden — nothing to do
        }
    }

    val isVisible = shouldShow && (!autoHidden || controlsVisible)

    LaunchedEffect(isVisible) { onVisibilityChanged(isVisible) }

    // Request focus when becoming visible or when controls hide
    LaunchedEffect(isVisible, controlsVisible) {
        if (isVisible && !controlsVisible) {
            try { activeFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier = modifier
    ) {
        Card(
            onClick = onSkip,
            modifier = Modifier
                .focusRequester(activeFocusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                },
            colors = CardDefaults.colors(
                containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f),
                focusedContainerColor = NuvioColors.Secondary
            ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.width(IntrinsicSize.Max)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        tint = if (isFocused) NuvioColors.OnSecondary else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = getSkipLabel(lastType),
                        color = if (isFocused) NuvioColors.OnSecondary else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        .background(Color.White.copy(alpha = if (controlsVisible || autoHidden || dismissed) 0f else 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.value)
                            .height(4.dp)
                            .background(Color(0xFF1E1E1E).copy(alpha = if (controlsVisible || autoHidden || dismissed) 0f else 0.85f))
                    )
                }
            }
        }
    }
}

@Composable
private fun getSkipLabel(type: String?): String = when (type) {
    "op", "mixed-op", "intro" -> stringResource(R.string.skip_intro)
    "ed", "mixed-ed", "outro" -> stringResource(R.string.skip_ending)
    "recap" -> stringResource(R.string.skip_recap)
    else -> stringResource(R.string.skip_generic)
}
