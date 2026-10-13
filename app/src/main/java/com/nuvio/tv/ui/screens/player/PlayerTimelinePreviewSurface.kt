package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.LocalUiMotion
import com.nuvio.tv.ui.theme.NuvioTheme

/** Shares the ViewModel's single extraction lane; it does not take focus or issue a playback seek. */
@Composable
internal fun PlayerTimelinePreviewHost(viewModel: PlayerViewModel) {
    val ui by viewModel.uiState.collectAsState()
    val timeline by viewModel.playbackTimeline.collectAsState()
    val owner by viewModel.sceneBookmarks.state.collectAsState()
    val state by viewModel.timelinePreviewState.collectAsState()
    val pending = ui.pendingPreviewSeekPosition ?: return
    val requested = normalizedTimelinePreviewPosition(pending, timeline.duration) ?: return
    // Recheck live account/profile/source ownership at composition, not only when decoding started.
    val current = owner.scope != null && viewModel.isTimelinePreviewCurrent()
    val displayed = if (current) state else TimelineFrameState.Unavailable(requested)
    PlayerTimelinePreviewSurface(displayed, pending, requested,
        compact = LocalConfiguration.current.screenHeightDp < 440)
}

/** Pure production surface. Loading/unavailable states never substitute a poster for a decoded scene. */
@Composable
internal fun PlayerTimelinePreviewSurface(
    state: TimelineFrameState,
    displayPositionMs: Long,
    requestedFrameMs: Long,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = LocalUiMotion.current
    val ready = (state as? TimelineFrameState.Ready)?.takeIf { it.image.requestedMs == requestedFrameMs || it.provisional }
    val loading = state is TimelineFrameState.Loading || state is TimelineFrameState.Idle
    val title = stringResource(R.string.timeline_preview_position, formatTime(displayPositionMs))
    val frameWidth = if (compact) 192.dp else 272.dp
    val tileWidth = if (compact) 60.dp else 76.dp
    val shape = RoundedCornerShape(if (compact) 10.dp else 14.dp)
    val neighbors = ready?.let { result ->
        (result.neighbors + result.image).distinctBy { it.decodedMs }.sortedBy { it.decodedMs }.take(5)
    }.orEmpty()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier.fillMaxWidth().padding(bottom = if (compact) 6.dp else 10.dp)
        .testTag("timeline-preview-surface"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("timeline-preview-position"))
        Box(Modifier.width(frameWidth).aspectRatio(16f / 9f).clip(shape)
            .background(Color(0xF0101720)).border(1.dp, NuvioTheme.colors.Primary.copy(alpha = .65f), shape),
            contentAlignment = Alignment.Center) {
            if (ready != null) {
                val bitmap = remember(ready.image.bitmap) { ready.image.bitmap.asImageBitmap() }
                Image(bitmap, stringResource(R.string.timeline_preview_decoded, formatTime(ready.image.decodedMs)),
                    Modifier.fillMaxSize().testTag("timeline-preview-image"), contentScale = ContentScale.Fit)
                Text(formatTime(ready.image.decodedMs), color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                if (ready.provisional) Text(stringResource(R.string.timeline_preview_catching_up), color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(4.dp))
            } else {
                Text(stringResource(if (loading) R.string.timeline_preview_loading else R.string.timeline_preview_unavailable),
                    color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, modifier = Modifier.padding(12.dp).testTag("timeline-preview-status"))
            }
        }
        AnimatedVisibility(visible = neighbors.size > 1,
            enter = fadeIn(tween(motion.durationMillis(120))), exit = fadeOut(tween(motion.durationMillis(100)))) {
            val description = stringResource(R.string.timeline_preview_filmstrip)
            Row(Modifier.testTag("timeline-preview-filmstrip").semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                neighbors.forEach { frame ->
                    val selected = frame.decodedMs == ready?.image?.decodedMs
                    Column(Modifier.width(tileWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        val tileShape = RoundedCornerShape(6.dp)
                        Image(remember(frame.bitmap) { frame.bitmap.asImageBitmap() },
                            stringResource(R.string.timeline_preview_decoded, formatTime(frame.decodedMs)),
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(tileShape)
                                .border(if (selected) 2.dp else 1.dp,
                                    if (selected) NuvioTheme.colors.Primary else Color.White.copy(alpha = .2f), tileShape)
                                .testTag("timeline-preview-tile-${frame.decodedMs}"), contentScale = ContentScale.Fit)
                        Text(formatTime(frame.decodedMs), style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color.White else Color.White.copy(alpha = .75f), maxLines = 1)
                    }
                }
            }
        }
    }
    }
}
