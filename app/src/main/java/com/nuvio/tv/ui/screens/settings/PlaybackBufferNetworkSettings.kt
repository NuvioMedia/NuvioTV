@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioColors

@androidx.annotation.OptIn(UnstableApi::class)
internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onResetToDefaults: () -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeMb: (Int) -> Unit,
    onResetNetworkToDefaults: () -> Unit
) {
    item {
        Text(
            text = "Buffer",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "These settings affect buffering behavior. Incorrect values may cause playback issues.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Speed,
            title = "Min Buffer Duration",
            subtitle = "Minimum amount of media to buffer. The player will try to ensure at least this much content is always buffered ahead of the current playback position.",
            value = playerSettings.bufferSettings.minBufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.minBufferMs / 1000}s",
            minValue = 5,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMinBufferMs(it * 1000) }
        )
    }

    item {
        val minBufferSeconds = playerSettings.bufferSettings.minBufferMs / 1000
        SliderSettingsItem(
            icon = Icons.Default.Speed,
            title = "Max Buffer Duration",
            subtitle = "Maximum amount of media to buffer. Higher values use more memory but provide smoother playback on unstable connections.",
            value = playerSettings.bufferSettings.maxBufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.maxBufferMs / 1000}s",
            minValue = minBufferSeconds,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMaxBufferMs(it * 1000) }
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.PlayArrow,
            title = "Initial Buffer",
            subtitle = "How much content must be buffered before playback starts. Lower values start faster but may cause initial stuttering on slow connections.",
            value = playerSettings.bufferSettings.bufferForPlaybackMs / 1000,
            valueText = "${playerSettings.bufferSettings.bufferForPlaybackMs / 1000}s",
            minValue = 1,
            maxValue = 60,
            step = 1,
            onValueChange = { onSetBufferForPlaybackMs(it * 1000) }
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Refresh,
            title = "Buffer After Rebuffer",
            subtitle = "How much content to buffer after playback stalls due to buffering. Higher values reduce repeated buffering interruptions.",
            value = playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000}s",
            minValue = 1,
            maxValue = 120,
            step = 1,
            onValueChange = { onSetBufferForPlaybackAfterRebufferMs(it * 1000) }
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.History,
            title = "Back Buffer Duration",
            subtitle = "How much already-played content to keep in memory. Enables fast backward seeking without re-downloading. Set to 0 to disable and save memory.",
            value = playerSettings.bufferSettings.backBufferDurationMs / 1000,
            valueText = "${playerSettings.bufferSettings.backBufferDurationMs / 1000}s",
            minValue = 0,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferBackBufferDurationMs(it * 1000) }
        )
    }

    item {
        val parallelOverheadMb = if (playerSettings.useParallelConnections)
            MemoryBudget.parallelOverheadMb(playerSettings.parallelConnectionCount, playerSettings.parallelChunkSizeMb) else 0
        val maxBufferSizeMb = MemoryBudget.maxBufferMb(parallelOverheadMb)
        val minBufferSizeMb = ((MemoryBudget.defaultBufferSizeMb / 2) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
            .coerceIn(MemoryBudget.MIN_BUFFER_MB, maxBufferSizeMb)
        val bufferSizeMb = playerSettings.bufferSettings.targetBufferSizeMb.coerceIn(0, maxBufferSizeMb)
        SliderSettingsItem(
            icon = Icons.Default.Storage,
            title = "Target Buffer Size",
            subtitle = "Maximum memory for buffering.",
            value = bufferSizeMb,
            valueText = "$bufferSizeMb MB",
            minValue = minBufferSizeMb,
            maxValue = maxBufferSizeMb,
            step = MemoryBudget.BUFFER_STEP_MB,
            onValueChange = onSetBufferTargetSizeMb
        )
    }

    item {
        Button(
            onClick = onResetToDefaults,
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.Background,
                focusedContainerColor = NuvioColors.Background
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(1.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            )
        ) {
            Text(
                text = "Reset to Default",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary
            )
        }
    }

    item {
        Text(
            text = "Network",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = "Parallel Connections",
            subtitle = "Use multiple connections in parallel for fetching streams. Activate when you experience buffering although your download speed is definitely more than sufficient.",
            isChecked = playerSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections
        )
    }

    if (playerSettings.useParallelConnections) {
        item {
            SliderSettingsItem(
                icon = Icons.Default.Hub,
                title = "Connection Count",
                subtitle = "Number of parallel TCP connections. Higher values increase memory usage and throughput but with diminishing returns.",
                value = playerSettings.parallelConnectionCount,
                valueText = playerSettings.parallelConnectionCount.toString(),
                minValue = MemoryBudget.MIN_CONNECTIONS,
                maxValue = MemoryBudget.MAX_CONNECTIONS,
                step = 1,
                onValueChange = onSetParallelConnectionCount
            )
        }

        item {
            val effectiveBufferMb = MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
            val maxChunkSizeMb = MemoryBudget.maxChunkMb(effectiveBufferMb, playerSettings.parallelConnectionCount)
            val chunkSizeMb = playerSettings.parallelChunkSizeMb.coerceAtMost(maxChunkSizeMb)
            SliderSettingsItem(
                icon = Icons.Default.Storage,
                title = "Chunk Size",
                subtitle = "Size of each download chunk per connection. Larger chunks reduce overhead but use more memory.",
                value = chunkSizeMb,
                valueText = "$chunkSizeMb MB",
                minValue = MemoryBudget.MIN_CHUNK_MB,
                maxValue = maxChunkSizeMb,
                step = 8,
                onValueChange = onSetParallelChunkSizeMb
            )
        }
    }

    item {
        Button(
            onClick = onResetNetworkToDefaults,
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.Background,
                focusedContainerColor = NuvioColors.Background
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(1.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            )
        ) {
            Text(
                text = "Reset to Default",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextPrimary
            )
        }
    }

}
