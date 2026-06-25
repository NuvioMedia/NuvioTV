@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    onSetNuvioPerformanceModeEnabled: (Boolean) -> Unit,
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
    onSetEnableHttp2: (Boolean) -> Unit,
    onResetNetworkToDefaults: () -> Unit
) {
    val isSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O

    // ── Master toggle: ExoPlayer Native Memory ──
    item(key = "buffer_net_nuvio_performance_mode") {
        var showWarning by remember { mutableStateOf(false) }

        Column {
            ToggleSettingsItem(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.playback_net_nuvio_performance_mode),
                subtitle = stringResource(R.string.playback_net_nuvio_performance_mode_sub),
                isChecked = isSupported && playerSettings.nuvioPerformanceModeEnabled,
                enabled = true,
                onCheckedChange = { enabled ->
                    if (isSupported) {
                        onSetNuvioPerformanceModeEnabled(enabled)
                    } else {
                        showWarning = true
                    }
                }
            )

            if (!isSupported && showWarning) {
                Text(
                    text = stringResource(R.string.playback_net_nuvio_performance_mode_not_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336), // Red color
                    modifier = Modifier.padding(start = 52.dp, end = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
                )
            } else if (isSupported && playerSettings.nuvioPerformanceModeEnabled) {
                val context = LocalContext.current
                val ramLabel = NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel(context)
                val safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
                val ramInfoText = stringResource(R.string.playback_net_device_memory_info, ramLabel, safeLimitMb)
                Text(
                    text = ramInfoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(start = 52.dp, end = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
                )
            }
        }
    }

    // ── Master toggle: custom buffer engine ──
    item(key = "buffer_net_custom_buffers") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.playback_buffer_custom),
            subtitle = stringResource(R.string.playback_buffer_custom_sub),
            isChecked = playerSettings.bufferEngineEnabled,
            onCheckedChange = onSetBufferEngineEnabled
        )
    }

    if (playerSettings.bufferEngineEnabled) {
        val isNativeMemory = playerSettings.nuvioPerformanceModeEnabled
        val maxDuration = if (isNativeMemory) 1200 else 120
        val durationStep = if (isNativeMemory) 10 else 5

        item(key = "buffer_net_custom_buffers_header") {
            Text(
                text = stringResource(R.string.playback_buffer_header),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
            )
        }

        item(key = "buffer_net_custom_buffers_warning") {
            Text(
                text = stringResource(R.string.playback_buffer_warning),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )
        }

        item(key = "buffer_net_min_buffer") {
            SliderSettingsItem(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.playback_buffer_min),
                subtitle = stringResource(R.string.playback_buffer_min_sub),
                value = playerSettings.bufferSettings.minBufferMs / 1000,
                valueText = "${playerSettings.bufferSettings.minBufferMs / 1000}s",
                minValue = 5,
                maxValue = maxDuration,
                step = durationStep,
                onValueChange = { onSetBufferMinBufferMs(it * 1000) }
            )
        }

        item(key = "buffer_net_max_buffer") {
            val minBufferSeconds = playerSettings.bufferSettings.minBufferMs / 1000
            val maxBufferSeconds = playerSettings.bufferSettings.maxBufferMs / 1000
            SliderSettingsItem(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.playback_buffer_max),
                subtitle = stringResource(R.string.playback_buffer_max_sub),
                value = maxBufferSeconds,
                valueText = if (maxBufferSeconds == minBufferSeconds) {
                    stringResource(R.string.playback_buffer_value_same_as_min, maxBufferSeconds)
                } else {
                    "${maxBufferSeconds}s"
                },
                minValue = 5,
                maxValue = maxDuration,
                step = durationStep,
                onValueChange = { onSetBufferMaxBufferMs(maxOf(it, minBufferSeconds) * 1000) }
            )
        }

        item(key = "buffer_net_initial_buffer") {
            SliderSettingsItem(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.playback_buffer_initial),
                subtitle = stringResource(R.string.playback_buffer_initial_sub),
                value = playerSettings.bufferSettings.bufferForPlaybackMs / 1000,
                valueText = "${playerSettings.bufferSettings.bufferForPlaybackMs / 1000}s",
                minValue = 1,
                maxValue = 60,
                step = 1,
                onValueChange = { onSetBufferForPlaybackMs(it * 1000) }
            )
        }

        item(key = "buffer_net_rebuffer") {
            SliderSettingsItem(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.playback_buffer_after_rebuffer),
                subtitle = stringResource(R.string.playback_buffer_after_rebuffer_sub),
                value = playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000,
                valueText = "${playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000}s",
                minValue = 1,
                maxValue = 120,
                step = 1,
                onValueChange = { onSetBufferForPlaybackAfterRebufferMs(it * 1000) }
            )
        }

        item(key = "buffer_net_back_buffer") {
            SliderSettingsItem(
                icon = Icons.Default.History,
                title = stringResource(R.string.playback_buffer_back),
                subtitle = stringResource(R.string.playback_buffer_back_sub),
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
                    text = stringResource(R.string.playback_buffer_back_reserve, reserveMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(start = 52.dp, end = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
                )
            }
        }

        item(key = "buffer_net_managed") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.playback_buffer_managed),
                subtitle = stringResource(R.string.playback_buffer_managed_sub),
                isChecked = playerSettings.bufferBudgetManaged,
                onCheckedChange = onSetBufferBudgetManaged
            )
        }

        item(key = "buffer_net_target_size") {
            val budgetManaged = playerSettings.bufferBudgetManaged
            val parallelOverheadMb = if (playerSettings.parallelNetworkEnabled && playerSettings.useParallelConnections)
                MemoryBudget.parallelOverheadMb(playerSettings.parallelConnectionCount, playerSettings.parallelChunkSizeMb) else 0
            val context = LocalContext.current
            val safeMaxMb = if (playerSettings.nuvioPerformanceModeEnabled) {
                NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
            } else {
                MemoryBudget.maxBufferMb(parallelOverheadMb)
            }
            val warningMaxMb = if (playerSettings.nuvioPerformanceModeEnabled) {
                NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
            } else {
                (((MemoryBudget.budgetMb * 1.25f).toInt() - parallelOverheadMb) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
                    .coerceIn(MemoryBudget.MIN_BUFFER_MB, MemoryBudget.MAX_BUFFER_MB)
            }
            val maxBufferSizeMb = if (playerSettings.allowLargeTargetBuffer) {
                PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
            } else {
                warningMaxMb
            }
            val minBufferSizeMb = ((MemoryBudget.defaultBufferSizeMb / 2) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
                .coerceIn(MemoryBudget.MIN_BUFFER_MB, maxBufferSizeMb)
            val bufferSizeMb = if (playerSettings.nuvioPerformanceModeEnabled && budgetManaged) {
                safeMaxMb
            } else {
                MemoryBudget
                    .effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                    .coerceIn(minBufferSizeMb, maxBufferSizeMb)
            }
            SliderSettingsItem(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.playback_buffer_target),
                subtitle = stringResource(R.string.playback_buffer_target_sub),
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
                    text = stringResource(R.string.playback_buffer_target_managed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(start = 52.dp, end = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
                )
            }
            if (!budgetManaged && bufferSizeMb > safeMaxMb) {
                val isDanger = bufferSizeMb > warningMaxMb
                val warningColor = if (isDanger) Color(0xFFF44336) else Color(0xFFFF9800)
                val warningText = if (isDanger) {
                    stringResource(R.string.playback_buffer_target_danger_warning, warningMaxMb)
                } else {
                    stringResource(R.string.playback_buffer_target_warning, safeMaxMb)
                }
                Text(
                    text = warningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                    modifier = Modifier.padding(start = 52.dp, end = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm)
                )
            }
        }

        item(key = "buffer_net_allow_large") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.playback_buffer_allow_large),
                subtitle = stringResource(R.string.playback_buffer_allow_large_sub),
                isChecked = playerSettings.allowLargeTargetBuffer,
                onCheckedChange = onSetAllowLargeTargetBuffer,
                enabled = !playerSettings.bufferBudgetManaged
            )
        }
    }

    if (playerSettings.bufferEngineEnabled || playerSettings.nuvioPerformanceModeEnabled) {
        // ── Disk cache (extends the in-memory back buffer) ──
        item(key = "buffer_net_disk_cache_header") {
            Text(
                text = stringResource(R.string.playback_cache_header),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
            )
        }

        item(key = "buffer_net_vod_cache") {
            ToggleSettingsItem(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.playback_cache_vod),
                subtitle = stringResource(R.string.playback_cache_vod_sub),
                isChecked = playerSettings.vodCacheEnabled,
                onCheckedChange = onSetVodCacheEnabled
            )
        }

        if (playerSettings.vodCacheEnabled) {
            // Sub-option of the master VOD Disk Cache toggle. Indented to make
            // the parent/child relationship visually clear so this doesn't read
            // as a second redundant on/off switch.
            item(key = "buffer_net_auto_cache_size") {
                val autoMode = playerSettings.vodCacheSizeMode == VodCacheSizeMode.AUTO
                Box(modifier = Modifier.padding(start = NuvioTheme.spacing.xxl)) {
                    ToggleSettingsItem(
                        icon = Icons.Default.Tune,
                        title = stringResource(R.string.playback_cache_auto_size),
                        subtitle = stringResource(R.string.playback_cache_auto_size_sub),
                        isChecked = autoMode,
                        onCheckedChange = { enabled ->
                            onSetVodCacheSizeMode(if (enabled) VodCacheSizeMode.AUTO else VodCacheSizeMode.MANUAL)
                        }
                    )
                }
            }

            if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.MANUAL) {
                item(key = "buffer_net_manual_cache_size") {
                    val context = LocalContext.current
                    val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
                    val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
                    val manualCacheMb = playerSettings.vodCacheSizeMb.coerceIn(
                        PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                        maxManualCacheMb
                    )
                    SliderSettingsItem(
                        icon = Icons.Default.Storage,
                        title = stringResource(R.string.playback_cache_vod_size),
                        subtitle = stringResource(R.string.playback_cache_vod_size_sub),
                        value = manualCacheMb,
                        valueText = "${manualCacheMb} MB",
                        minValue = PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                        maxValue = maxManualCacheMb,
                        step = 50,
                        onValueChange = onSetVodCacheSizeMb
                    )
                }
            }

            item(key = "buffer_net_cache_info") {
                val context = LocalContext.current
                val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
                val freeDiskLabel = formatStorageSize(freeDiskBytes)
                val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
                val manualMode = playerSettings.vodCacheSizeMode == VodCacheSizeMode.MANUAL
                val rangeInfo = stringResource(
                    R.string.playback_cache_info_range,
                    PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                    maxManualCacheMb
                )
                val autoInfo = stringResource(R.string.playback_cache_info_auto)
                val headroomInfo = stringResource(
                    R.string.playback_cache_info_manual_headroom,
                    VOD_CACHE_FREE_SPACE_RESERVE_MB.toInt()
                )
                val freeDiskInfo = stringResource(R.string.playback_cache_info_free_disk, freeDiskLabel)
                val restartInfo = stringResource(R.string.playback_cache_info_restart)
                val infoText = buildString {
                    append(rangeInfo)
                    append(" ")
                    append(autoInfo)
                    append(" ")
                    append(headroomInfo)
                    if (manualMode) {
                        append(" ")
                        append(freeDiskInfo)
                        append(" ")
                        append(restartInfo)
                    }
                }
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                )
            }
        }

        item(key = "buffer_net_reset_defaults") {
            Button(
                onClick = onResetToDefaults,
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Background,
                    focusedContainerColor = NuvioTheme.colors.Background
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(10.dp)
                    )
                )
            ) {
                Text(
                    text = stringResource(R.string.playback_reset_to_default),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }

    // ── Master toggle: parallel connections ──
    item(key = "buffer_net_parallel_custom") {
        ToggleSettingsItem(
            icon = Icons.Default.Hub,
            title = stringResource(R.string.playback_net_custom),
            subtitle = stringResource(R.string.playback_net_custom_sub),
            isChecked = playerSettings.parallelNetworkEnabled,
            onCheckedChange = onSetParallelNetworkEnabled
        )
    }

    if (playerSettings.parallelNetworkEnabled) {
        item(key = "buffer_net_http2") {
            ToggleSettingsItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.playback_net_http2),
                subtitle = stringResource(R.string.playback_net_http2_sub),
                isChecked = playerSettings.enableHttp2,
                onCheckedChange = onSetEnableHttp2
            )
        }

        item(key = "buffer_net_parallel_wifi") {
            ToggleSettingsItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.playback_net_parallel),
                subtitle = stringResource(R.string.playback_net_parallel_sub),
                isChecked = playerSettings.useParallelConnections,
                onCheckedChange = onSetUseParallelConnections
            )
        }

        if (playerSettings.useParallelConnections) {
            item(key = "buffer_net_parallel_connection_count") {
                SliderSettingsItem(
                    icon = Icons.Default.Hub,
                    title = stringResource(R.string.playback_net_connection_count),
                    subtitle = stringResource(R.string.playback_net_connection_count_sub),
                    value = playerSettings.parallelConnectionCount,
                    valueText = playerSettings.parallelConnectionCount.toString(),
                    minValue = MemoryBudget.MIN_CONNECTIONS,
                    maxValue = if (playerSettings.nuvioPerformanceModeEnabled) 16 else MemoryBudget.MAX_CONNECTIONS,
                    step = 1,
                    onValueChange = onSetParallelConnectionCount
                )
            }

            item(key = "buffer_net_parallel_chunk_size") {
                val effectiveBufferMb = MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                val maxChunkSizeMb = if (playerSettings.nuvioPerformanceModeEnabled) {
                    MemoryBudget.MAX_CHUNK_MB
                } else {
                    MemoryBudget.maxChunkMb(effectiveBufferMb, playerSettings.parallelConnectionCount)
                }
                val chunkSizeMb = playerSettings.parallelChunkSizeMb.coerceAtMost(maxChunkSizeMb)
                SliderSettingsItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.playback_net_chunk_size),
                    subtitle = stringResource(R.string.playback_net_chunk_size_sub),
                    value = chunkSizeMb,
                    valueText = "$chunkSizeMb MB",
                    minValue = MemoryBudget.MIN_CHUNK_MB,
                    maxValue = maxChunkSizeMb,
                    step = 8,
                    onValueChange = onSetParallelChunkSizeMb
                )
            }
        }

        item(key = "buffer_net_parallel_reset_defaults") {
            Button(
                onClick = onResetNetworkToDefaults,
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Background,
                    focusedContainerColor = NuvioTheme.colors.Background
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(10.dp)
                    )
                )
            ) {
                Text(
                    text = stringResource(R.string.playback_reset_to_default),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun formatStorageSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 10.0) return stringResource(R.string.unit_size_gb, String.format("%.0f", gb))
    if (gb >= 1.0) return stringResource(R.string.unit_size_gb, String.format("%.1f", gb))
    val mb = bytes / (1024.0 * 1024.0)
    return stringResource(R.string.unit_size_mb, String.format("%.0f", mb))
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