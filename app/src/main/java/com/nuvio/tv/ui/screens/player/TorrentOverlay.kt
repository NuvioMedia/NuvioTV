package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R

@Composable
fun TorrentOverlay(
    visible: Boolean,
    downloadSpeed: Long,
    uploadSpeed: Long,
    peers: Int,
    seeds: Int,
    totalProgress: Float,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .background(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(NuvioTheme.radii.sm)
                )
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "P2P",
                    color = Color(0xFF4CAF50),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "↓ ${formatSpeed(downloadSpeed)}",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Text(
                    text = "↑ ${formatSpeed(uploadSpeed)}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
            Text(
                text = stringResource(R.string.player_torrent_stats, peers, seeds, (totalProgress * 100).toInt()),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> stringResource(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 -> stringResource(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else -> stringResource(R.string.unit_speed_b_s, bytesPerSec)
    }
}
