@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    onSetBufferEngineEnabled: (Boolean) -> Unit,
    onSetParallelNetworkEnabled: (Boolean) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetAllowLargeTargetBuffer: (Boolean) -> Unit,
    onSetBufferBudgetManaged: (Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onSetVodCacheEnabled: (Boolean) -> Unit,
    onSetVodCacheSizeMode: (VodCacheSizeMode) -> Unit,
    onSetVodCacheSizeMb: (Int) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeMb: (Int) -> Unit,
    onResetNetworkToDefaults: () -> Unit
) {
    // ── Master toggle: custom buffer engine ──
    item {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = "Custom Playback Buffers",
            subtitle = "Override Media3's default buffering with the values below. When off, the player uses Media3's stock buffer durations and target size.",
            isChecked = playerSettings.bufferEngineEnabled,
            onCheckedChange = onSetBufferEngineEnabled
        )
    }

    if (playerSettings.bufferEngineEnabled) {
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
            val maxBufferSeconds = playerSettings.bufferSettings.maxBufferMs / 1000
            SliderSettingsItem(
                icon = Icons.Default.Speed,
                title = "Max Buffer Duration",
                subtitle = "Maximum amount of media to buffer. Must be at least the minimum buffer duration. Higher values use more memory but provide smoother playback on unstable connections.",
                value = maxBufferSeconds,
                valueText = if (maxBufferSeconds == minBufferSeconds) {
                    "${maxBufferSeconds}s (same as min)"
                } else {
                    "${maxBufferSeconds}s"
                },
                minValue = 5,
                maxValue = 120,
                step = 5,
                onValueChange = { onSetBufferMaxBufferMs(maxOf(it, minBufferSeconds) * 1000) }
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
            // Live estimate of the back-buffer byte reserve Media3 holds on top of the
            // target buffer. Reserve = targetBuffer * backBufferMs / maxBufferMs.
            val backBufferMs = playerSettings.bufferSettings.backBufferDurationMs
            val maxBufferMs = playerSettings.bufferSettings.maxBufferMs
            if (backBufferMs > 0 && maxBufferMs > 0) {
                val targetMb = MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                val reserveMb = (targetMb.toLong() * backBufferMs / maxBufferMs).toInt()
                Text(
                    text = "Reserves ~${reserveMb}MB on top of Target Buffer. " +
                        "Raising Max Buffer Duration shrinks this without losing back-buffer time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
        }

        item {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = "Managed Memory Budget",
                subtitle = "Caps the buffer to a safe share of this device's memory. Turn off to set the Target Buffer Size yourself (advanced — large values can crash low-memory devices).",
                isChecked = playerSettings.bufferBudgetManaged,
                onCheckedChange = onSetBufferBudgetManaged
            )
        }

        item {
            val budgetManaged = playerSettings.bufferBudgetManaged
            val parallelOverheadMb = if (playerSettings.parallelNetworkEnabled && playerSettings.useParallelConnections)
                MemoryBudget.parallelOverheadMb(playerSettings.parallelConnectionCount, playerSettings.parallelChunkSizeMb) else 0
            val safeMaxMb = MemoryBudget.maxBufferMb(parallelOverheadMb)
            val maxBufferSizeMb = MemoryBudget.maxBufferMbWithOverride(parallelOverheadMb, playerSettings.allowLargeTargetBuffer)
            val minBufferSizeMb = ((MemoryBudget.defaultBufferSizeMb / 2) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
                .coerceIn(MemoryBudget.MIN_BUFFER_MB, maxBufferSizeMb)
            val bufferSizeMb = MemoryBudget
                .effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                .coerceIn(minBufferSizeMb, maxBufferSizeMb)
            SliderSettingsItem(
                icon = Icons.Default.Storage,
                title = "Target Buffer Size",
                subtitle = "Maximum RAM used for ahead-buffering. The maximum is calculated from your device's available memory (Android's per-app heap limit), so higher-RAM devices unlock larger caps automatically.",
                value = bufferSizeMb,
                valueText = "$bufferSizeMb MB",
                minValue = minBufferSizeMb,
                maxValue = maxBufferSizeMb,
                step = MemoryBudget.BUFFER_STEP_MB,
                onValueChange = onSetBufferTargetSizeMb,
                enabled = !budgetManaged
            )
            if (budgetManaged) {
                Text(
                    text = "Managed by the device memory budget. Turn off Managed Memory Budget to set this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
            if (!budgetManaged && playerSettings.allowLargeTargetBuffer && bufferSizeMb > safeMaxMb) {
                Text(
                    text = "Warning: above device safe limit (${safeMaxMb} MB). App may crash during playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336),
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
        }

        item {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = "Allow Larger Target Buffer",
                subtitle = "Removes the device-memory cap on the Target Buffer Size slider, allowing values up to 2GB. May crash on devices with less than 2GB of RAM.",
                isChecked = playerSettings.allowLargeTargetBuffer,
                onCheckedChange = onSetAllowLargeTargetBuffer,
                enabled = !playerSettings.bufferBudgetManaged
            )
        }

        // ── Disk cache (extends the in-memory back buffer) ──
        item {
            Text(
                text = "Disk Cache",
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            ToggleSettingsItem(
                icon = Icons.Default.Storage,
                title = "VOD Disk Cache",
                subtitle = "Persist downloaded bytes to disk for the current stream. Extends instant seek-back beyond the in-memory back buffer and survives brief network drops. Only applies to progressive streams (no HLS/DASH).",
                isChecked = playerSettings.vodCacheEnabled,
                onCheckedChange = onSetVodCacheEnabled
            )
        }

        if (playerSettings.vodCacheEnabled) {
            // Sub-option of the master VOD Disk Cache toggle. Indented to make
            // the parent/child relationship visually clear so this doesn't read
            // as a second redundant on/off switch.
            item {
                val autoMode = playerSettings.vodCacheSizeMode == VodCacheSizeMode.AUTO
                Box(modifier = Modifier.padding(start = 32.dp)) {
                    ToggleSettingsItem(
                        icon = Icons.Default.Tune,
                        title = "Auto Size",
                        subtitle = "When on, the cache is sized from free disk space. Turn off to pick a size manually.",
                        isChecked = autoMode,
                        onCheckedChange = { enabled ->
                            onSetVodCacheSizeMode(if (enabled) VodCacheSizeMode.AUTO else VodCacheSizeMode.MANUAL)
                        }
                    )
                }
            }

            if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.MANUAL) {
                item {
                    val context = LocalContext.current
                    val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
                    val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
                    val manualCacheMb = playerSettings.vodCacheSizeMb.coerceIn(
                        PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                        maxManualCacheMb
                    )
                    SliderSettingsItem(
                        icon = Icons.Default.Storage,
                        title = "VOD Cache Size",
                        subtitle = "Maximum disk usage for progressive VOD cache (LRU-evicted).",
                        value = manualCacheMb,
                        valueText = "${manualCacheMb} MB",
                        minValue = PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                        maxValue = maxManualCacheMb,
                        step = 50,
                        onValueChange = onSetVodCacheSizeMb
                    )
                }
            }

            item {
                val context = LocalContext.current
                val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
                val freeDiskLabel = formatStorageSize(freeDiskBytes)
                val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
                val manualMode = playerSettings.vodCacheSizeMode == VodCacheSizeMode.MANUAL
                val infoText = buildString {
                    append("Range: ${PlayerSettings.MIN_VOD_CACHE_SIZE_MB}-${maxManualCacheMb} MB. ")
                    append("Auto mode targets about 10% of free space.")
                    append(" Manual mode keeps about ${VOD_CACHE_FREE_SPACE_RESERVE_MB}MB headroom.")
                    if (manualMode) {
                        append(" Free disk available: $freeDiskLabel.")
                        append(" New cache cap applies after app restart when changing between modes/sizes.")
                    }
                }
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
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
    }

    // ── Master toggle: parallel connections ──
    item {
        ToggleSettingsItem(
            icon = Icons.Default.Hub,
            title = "Custom Network",
            subtitle = "Use multiple parallel connections to fetch progressive streams. When off, a single connection is used.",
            isChecked = playerSettings.parallelNetworkEnabled,
            onCheckedChange = onSetParallelNetworkEnabled
        )
    }

    if (playerSettings.parallelNetworkEnabled) {
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
}

private fun formatStorageSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 10.0) return String.format("%.0f GB", gb)
    if (gb >= 1.0) return String.format("%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format("%.0f MB", mb)
}

private fun resolveManualVodCacheMaxMb(freeDiskBytes: Long): Int {
    val freeDiskMb = freeDiskBytes.coerceAtLeast(0L) / (1024L * 1024L)
    val dynamicMaxMb = when {
        freeDiskMb > VOD_CACHE_FREE_SPACE_RESERVE_MB -> freeDiskMb - VOD_CACHE_FREE_SPACE_RESERVE_MB
        else -> (freeDiskMb * 8L) / 10L
    }
    val boundedMb = min(
        PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong(),
        dynamicMaxMb.coerceAtLeast(PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong())
    )
    return boundedMb.toInt()
}

private const val VOD_CACHE_FREE_SPACE_RESERVE_MB = 1024L