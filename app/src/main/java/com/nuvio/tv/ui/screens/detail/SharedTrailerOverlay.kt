package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import android.view.KeyEvent

@Stable
class TrailerSeekOverlayState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SharedTrailerOverlay(
    title: String,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    var isPaused by remember { mutableStateOf(false) }
    var seekOverlayVisible by remember { mutableStateOf(false) }
    val seekOverlayState = remember { TrailerSeekOverlayState() }
    var seekToken by remember { mutableIntStateOf(0) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }

    val canControlPlayback = !trailerUrl.isNullOrBlank() && !isLoading && errorMessage == null

    LaunchedEffect(trailerUrl, trailerAudioUrl, isLoading, errorMessage) {
        isPaused = false
        seekOverlayVisible = false
    }

    LaunchedEffect(seekOverlayVisible, canControlPlayback, seekToken) {
        if (seekOverlayVisible && canControlPlayback) {
            delay(3000)
            seekOverlayVisible = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }

                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            onDismiss()
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = !isPaused
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = true
                            true
                        }

                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            isPaused = false
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                            seekDeltaMs = when {
                                repeatCount >= 12 -> -12_000L
                                repeatCount >= 6 -> -8_000L
                                repeatCount >= 2 -> -5_000L
                                else -> -3_000L
                            }
                            seekToken += 1
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                            seekDeltaMs = when {
                                repeatCount >= 12 -> 12_000L
                                repeatCount >= 6 -> 8_000L
                                repeatCount >= 2 -> 5_000L
                                else -> 3_000L
                            }
                            seekToken += 1
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            seekOverlayVisible = true
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!canControlPlayback) return@onPreviewKeyEvent false
                            seekOverlayVisible = false
                            true
                        }

                        else -> false
                    }
                }
        ) {
            if (!trailerUrl.isNullOrBlank() && errorMessage == null) {
                TrailerPlayer(
                    trailerUrl = trailerUrl,
                    trailerAudioUrl = trailerAudioUrl,
                    isPlaying = true,
                    isPaused = isPaused,
                    seekRequestToken = seekToken,
                    seekDeltaMs = seekDeltaMs,
                    onProgressChanged = { position, duration ->
                        seekOverlayState.positionMs = position
                        seekOverlayState.durationMs = duration
                    },
                    onEnded = onDismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.hero_press_back_trailer),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Button(onClick = onRetry) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }

            TrailerSeekOverlayHost(
                visible = canControlPlayback && seekOverlayVisible,
                overlayState = seekOverlayState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun TrailerSeekOverlayHost(
    visible: Boolean,
    overlayState: TrailerSeekOverlayState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        TrailerSeekOverlay(
            currentPosition = overlayState.positionMs,
            duration = overlayState.durationMs
        )
    }
}

@Composable
fun TrailerSeekOverlay(
    currentPosition: Long,
    duration: Long
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "trailerSeekProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NuvioColors.Secondary)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatPlaybackTime(currentPosition)} / ${formatPlaybackTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
