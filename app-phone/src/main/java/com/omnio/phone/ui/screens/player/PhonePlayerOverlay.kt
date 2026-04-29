package com.omnio.phone.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.omnio.tv.core.player.PlayerUiState
import kotlin.math.max

@Composable
internal fun PhonePlayerOverlay(
    uiState: PlayerUiState,
    visible: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (positionMs: Long) -> Unit,
    onShowAudioSheet: () -> Unit,
    onShowSubtitleSheet: () -> Unit,
    onShowSourcesSheet: () -> Unit,
    onToggleAspect: () -> Unit,
    onTogglePip: () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        ) {
            TopBar(
                title = uiState.title,
                streamName = uiState.currentStreamName,
                onBack = onBack,
                onTogglePip = onTogglePip,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            CenterControls(
                isPlaying = uiState.isPlaying,
                isBuffering = uiState.isBuffering && !uiState.playbackEnded,
                onPlayPause = onPlayPause,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                modifier = Modifier.align(Alignment.Center)
            )

            BottomBar(
                uiState = uiState,
                onSeekTo = onSeekTo,
                onShowAudioSheet = onShowAudioSheet,
                onShowSubtitleSheet = onShowSubtitleSheet,
                onShowSourcesSheet = onShowSourcesSheet,
                onToggleAspect = onToggleAspect,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    streamName: String?,
    onBack: () -> Unit,
    onTogglePip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!streamName.isNullOrBlank()) {
                Text(
                    text = streamName,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onTogglePip) {
            Icon(
                imageVector = Icons.Filled.PictureInPicture,
                contentDescription = "Picture in picture",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun CenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        IconButton(onClick = onSeekBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }
        }
        IconButton(onClick = onSeekForward, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun BottomBar(
    uiState: PlayerUiState,
    onSeekTo: (Long) -> Unit,
    onShowAudioSheet: () -> Unit,
    onShowSubtitleSheet: () -> Unit,
    onShowSourcesSheet: () -> Unit,
    onToggleAspect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = max(uiState.duration, 1L)
    val pos = uiState.currentPosition.coerceIn(0L, duration)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(pos),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp)
            )
            Slider(
                value = pos.toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(
                text = formatTime(duration - pos, prefix = "-"),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton(
                icon = Icons.Filled.Headphones,
                label = "Audio",
                badge = uiState.audioTracks.size.takeIf { it > 1 }?.toString(),
                onClick = onShowAudioSheet
            )
            ActionButton(
                icon = Icons.Filled.ClosedCaption,
                label = "Subtitles",
                badge = uiState.subtitleTracks.size.takeIf { it > 0 }?.toString(),
                onClick = onShowSubtitleSheet
            )
            ActionButton(
                icon = Icons.Filled.SwapHoriz,
                label = "Source",
                onClick = onShowSourcesSheet
            )
            ActionButton(
                icon = Icons.Filled.AspectRatio,
                label = "Aspect",
                onClick = onToggleAspect
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(imageVector = icon, contentDescription = label, tint = Color.White)
            }
            if (!badge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
internal fun PhonePlayerSeekHud(
    visibleSeekDeltaMs: Long?,
    targetPositionMs: Long?,
    durationMs: Long
) {
    if (visibleSeekDeltaMs == null || targetPositionMs == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val sign = if (visibleSeekDeltaMs >= 0) "+" else "-"
            Text(
                text = "$sign${formatTime(kotlin.math.abs(visibleSeekDeltaMs))}",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${formatTime(targetPositionMs)} / ${formatTime(durationMs)}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun PhonePlayerLevelHud(
    level01: Float?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    if (level01 == null) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .width(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { level01.coerceIn(0f, 1f) },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${(level01.coerceIn(0f, 1f) * 100).toInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

internal val IconBrightness = Icons.Filled.BrightnessMedium
internal val IconVolume = Icons.AutoMirrored.Filled.VolumeUp

private fun formatTime(ms: Long, prefix: String = ""): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$prefix%d:%02d:%02d".format(h, m, s)
    } else {
        "$prefix%d:%02d".format(m, s)
    }
}
