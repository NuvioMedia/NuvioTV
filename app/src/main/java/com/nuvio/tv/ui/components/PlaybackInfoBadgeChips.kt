package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Compact text chips showing the active playback info (resolution, HDR, audio)
 * beside the player title. Fixed white-on-dark like the rest of the player
 * overlay; chip styling follows the StreamBadgeChips recipe.
 */
@Composable
fun PlaybackInfoBadgeChips(
    segments: List<String>,
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .clip(NuvioTheme.shapes.badge)
                    .background(Color(0xFF0A0C0C), NuvioTheme.shapes.badge)
                    .border(NuvioTheme.spacing.hairline, Color(0xFF0A0C0C), NuvioTheme.shapes.badge)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = segment,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
