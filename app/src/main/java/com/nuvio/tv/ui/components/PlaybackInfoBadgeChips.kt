package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlaybackInfoBadgeStyle
import com.nuvio.tv.ui.screens.player.PlaybackInfoBadge
import com.nuvio.tv.ui.screens.player.PlaybackInfoIcon
import com.nuvio.tv.ui.screens.player.PlaybackInfoSegment
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Chips showing the active playback info (resolution, HDR, audio) beside the
 * player title: video group, divider, audio group. Fixed white-on-dark like the
 * rest of the player overlay since it sits on top of video.
 */
@Composable
fun PlaybackInfoBadgeChips(
    badge: PlaybackInfoBadge,
    style: PlaybackInfoBadgeStyle,
    modifier: Modifier = Modifier
) {
    if (badge.isEmpty) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        badge.videoSegments.forEach { PlaybackInfoChip(it, style) }
        if (badge.videoSegments.isNotEmpty() && badge.audioSegments.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }
        badge.audioSegments.forEach { PlaybackInfoChip(it, style) }
    }
}

@Composable
private fun PlaybackInfoChip(segment: PlaybackInfoSegment, style: PlaybackInfoBadgeStyle) {
    val icon: ImageVector? = when (segment.icon) {
        PlaybackInfoIcon.HDR -> Icons.Rounded.HdrOn
        PlaybackInfoIcon.AUDIO_FORMAT -> Icons.Rounded.SurroundSound
        PlaybackInfoIcon.AUDIO_TRACK -> Icons.Rounded.VolumeUp
        null -> null
    }
    val shape: Shape = when (style) {
        PlaybackInfoBadgeStyle.PILL -> CircleShape
        PlaybackInfoBadgeStyle.ROUNDED -> RoundedCornerShape(NuvioTheme.radii.sm)
        PlaybackInfoBadgeStyle.PLAIN -> CircleShape
    }
    val decorated = style != PlaybackInfoBadgeStyle.PLAIN
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .then(
                if (decorated) {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.55f), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (decorated) 12.dp else 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .height(16.dp)
                        .width(16.dp)
                )
                Box(modifier = Modifier.width(5.dp))
            }
            Text(
                text = segment.text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
