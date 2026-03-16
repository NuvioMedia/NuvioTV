package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent

@Composable
internal fun PlayerOverlayScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    captureKeys: Boolean = true,
    dismissOnCenter: Boolean = false,
    dismissOnBackgroundClick: Boolean = false,
    overlayTint: Color = Color.Black.copy(alpha = 0.34f),
    contentPadding: PaddingValues = PaddingValues(),
    topEndContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        val focusRequester = remember { FocusRequester() }
        val interactionSource = remember { MutableInteractionSource() }

        LaunchedEffect(visible, captureKeys) {
            if (visible && captureKeys) {
                runCatching { focusRequester.requestFocus() }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dismissOnBackgroundClick) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onDismiss
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (captureKeys) {
                        Modifier
                            .focusRequester(focusRequester)
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                                    return@onKeyEvent false
                                }
                                when (event.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_BACK,
                                    KeyEvent.KEYCODE_ESCAPE -> {
                                        onDismiss()
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        if (dismissOnCenter) {
                                            onDismiss()
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    else -> false
                                }
                            }
                            .focusable()
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val horizontalGradient = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.88f),
                                Color.Transparent
                            )
                        )
                        val verticalGradient = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.6f),
                                0.3f to Color.Black.copy(alpha = 0.4f),
                                0.6f to Color.Black.copy(alpha = 0.2f),
                                1f to Color.Transparent
                            )
                        )
                        onDrawBehind {
                            drawRect(brush = horizontalGradient)
                            if (overlayTint.alpha > 0f) {
                                drawRect(color = overlayTint)
                            }
                            drawRect(brush = verticalGradient)
                        }
                    }
                    .padding(contentPadding)
            ) {
                if (topEndContent != null) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        topEndContent()
                    }
                }
                content()
            }
        }
    }
}
