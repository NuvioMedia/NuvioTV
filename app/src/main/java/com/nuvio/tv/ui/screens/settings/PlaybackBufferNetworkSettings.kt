package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackBufferNetworkSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onMemorySettingChanged: () -> Unit
) {
    val updateMemory: PlaybackSettingsUpdate = { block ->
        onUpdate(block)
        onMemorySettingChanged()
    }
    val context = LocalContext.current
    val isSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
    var showPerformanceModeWarning by remember { mutableStateOf(false) }

    SettingsToggleRow(
        title = stringResource(R.string.playback_net_nuvio_performance_mode),
        subtitle = stringResource(R.string.playback_net_nuvio_performance_mode_sub),
        checked = isSupported && settings.nuvioPerformanceModeEnabled,
        onToggle = {
            if (isSupported) {
                updateMemory { setNuvioPerformanceModeEnabled(!settings.nuvioPerformanceModeEnabled) }
            } else {
                showPerformanceModeWarning = true
            }
        }
    )
    if (!isSupported && showPerformanceModeWarning) {
        SettingsNote(
            text = stringResource(R.string.playback_net_nuvio_performance_mode_not_supported),
            tone = SettingsNoteTone.Danger
        )
    } else if (isSupported && settings.nuvioPerformanceModeEnabled) {
        SettingsNote(
            text = stringResource(
                R.string.playback_net_device_memory_info,
                NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel(context),
                NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
            )
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.playback_buffer_custom),
        subtitle = stringResource(R.string.playback_buffer_custom_sub),
        checked = settings.bufferEngineEnabled,
        onToggle = {
            val enable = !settings.bufferEngineEnabled
            if (enable) {
                updateMemory { setBufferEngineEnabled(true) }
            } else {
                onUpdate { setBufferEngineEnabled(false) }
            }
        }
    )

    if (settings.bufferEngineEnabled) {
        CustomBufferControls(settings = settings, onUpdate = onUpdate, updateMemory = updateMemory)
    }

    SettingsSectionLabel(text = stringResource(R.string.playback_cache_header))
    DiskCacheControls(settings = settings, onUpdate = onUpdate)

    SettingsSectionLabel(text = stringResource(R.string.playback_network_label))
    SettingsToggleRow(
        title = stringResource(R.string.playback_net_custom),
        subtitle = stringResource(R.string.playback_net_custom_sub),
        checked = settings.parallelNetworkEnabled,
        onToggle = { onUpdate { setParallelNetworkEnabled(!settings.parallelNetworkEnabled) } }
    )
    if (settings.parallelNetworkEnabled) {
        ParallelNetworkControls(settings = settings, updateMemory = updateMemory)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun CustomBufferControls(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    updateMemory: PlaybackSettingsUpdate
) {
    val context = LocalContext.current
    val buffer = settings.bufferSettings
    val isNativeMemory = settings.nuvioPerformanceModeEnabled
    val maxDuration = if (isNativeMemory) 1200 else 120
    val durationStep = if (isNativeMemory) 10 else 5

    SettingsSectionLabel(text = stringResource(R.string.playback_buffer_header))
    SettingsNote(text = stringResource(R.string.playback_buffer_warning), tone = SettingsNoteTone.Warning)

    SliderSettingsItem(
        title = stringResource(R.string.playback_buffer_min),
        subtitle = stringResource(R.string.playback_buffer_min_sub),
        value = buffer.minBufferMs / 1000,
        valueText = "${buffer.minBufferMs / 1000}s",
        minValue = 5,
        maxValue = maxDuration,
        step = durationStep,
        onValueChange = { seconds -> onUpdate { setBufferMinBufferMs(seconds * 1000) } }
    )

    val minBufferSeconds = buffer.minBufferMs / 1000
    val maxBufferSeconds = buffer.maxBufferMs / 1000
    SliderSettingsItem(
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
        onValueChange = { seconds -> onUpdate { setBufferMaxBufferMs(maxOf(seconds, minBufferSeconds) * 1000) } }
    )

    SliderSettingsItem(
        title = stringResource(R.string.playback_buffer_initial),
        subtitle = stringResource(R.string.playback_buffer_initial_sub),
        value = buffer.bufferForPlaybackMs / 1000,
        valueText = "${buffer.bufferForPlaybackMs / 1000}s",
        minValue = 1,
        maxValue = 60,
        step = 1,
        onValueChange = { seconds -> onUpdate { setBufferForPlaybackMs(seconds * 1000) } }
    )

    SliderSettingsItem(
        title = stringResource(R.string.playback_buffer_after_rebuffer),
        subtitle = stringResource(R.string.playback_buffer_after_rebuffer_sub),
        value = buffer.bufferForPlaybackAfterRebufferMs / 1000,
        valueText = "${buffer.bufferForPlaybackAfterRebufferMs / 1000}s",
        minValue = 1,
        maxValue = 120,
        step = 1,
        onValueChange = { seconds -> onUpdate { setBufferForPlaybackAfterRebufferMs(seconds * 1000) } }
    )

    SliderSettingsItem(
        title = stringResource(R.string.playback_buffer_back),
        subtitle = stringResource(R.string.playback_buffer_back_sub),
        value = buffer.backBufferDurationMs / 1000,
        valueText = "${buffer.backBufferDurationMs / 1000}s",
        minValue = 0,
        maxValue = 120,
        step = 5,
        onValueChange = { seconds -> onUpdate { setBufferBackBufferDurationMs(seconds * 1000) } }
    )
    if (buffer.backBufferDurationMs > 0 && buffer.maxBufferMs > 0) {
        val targetMb = MemoryBudget.effectiveBufferMb(buffer.targetBufferSizeMb)
        val reserveMb = (targetMb.toLong() * buffer.backBufferDurationMs / buffer.maxBufferMs).toInt()
        SettingsNote(
            text = stringResource(R.string.playback_buffer_back_reserve, reserveMb)
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.playback_buffer_managed),
        subtitle = stringResource(R.string.playback_buffer_managed_sub),
        checked = settings.bufferBudgetManaged,
        onToggle = { updateMemory { setBufferBudgetManaged(!settings.bufferBudgetManaged) } }
    )

    val budgetManaged = settings.bufferBudgetManaged
    val parallelActive = settings.parallelNetworkEnabled && settings.useParallelConnections
    val chunkMb = Math.ceil(settings.parallelChunkSizeKb / 1024.0).toInt().coerceAtMost(MemoryBudget.tierMaxChunkMb)
    val parallelOverheadMb = if (parallelActive) {
        MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, chunkMb)
    } else {
        0
    }
    val safeMaxMb = if (settings.nuvioPerformanceModeEnabled) {
        NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
    } else {
        MemoryBudget.maxBufferMb(parallelOverheadMb)
    }
    val warningMaxMb = if (settings.nuvioPerformanceModeEnabled) {
        NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
    } else {
        (((MemoryBudget.budgetMb * 1.25f).toInt() - parallelOverheadMb) / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
            .coerceIn(MemoryBudget.MIN_BUFFER_MB, MemoryBudget.MAX_BUFFER_MB)
    }
    val maxBufferSizeMb = if (settings.allowLargeTargetBuffer) {
        PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
    } else {
        warningMaxMb
    }
    val minBufferSizeMb = MemoryBudget.MIN_TARGET_BUFFER_MB.coerceAtMost(maxBufferSizeMb)
    val bufferSizeMb = if (settings.nuvioPerformanceModeEnabled && budgetManaged) {
        safeMaxMb
    } else {
        MemoryBudget
            .effectiveBufferMb(buffer.targetBufferSizeMb)
            .coerceIn(minBufferSizeMb, maxBufferSizeMb)
    }
    val effectiveExoMb = (bufferSizeMb - parallelOverheadMb).coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
    SliderSettingsItem(
        title = stringResource(R.string.playback_buffer_target),
        subtitle = stringResource(R.string.playback_buffer_target_sub),
        value = bufferSizeMb,
        valueText = if (settings.nuvioPerformanceModeEnabled && parallelActive && parallelOverheadMb > 0) {
            "$effectiveExoMb+$parallelOverheadMb MB"
        } else {
            "$bufferSizeMb MB"
        },
        minValue = minBufferSizeMb,
        maxValue = maxBufferSizeMb,
        step = MemoryBudget.BUFFER_STEP_MB,
        onValueChange = { mb -> updateMemory { setBufferTargetSizeMb(mb) } },
        enabled = !budgetManaged
    )
    if (budgetManaged) {
        SettingsNote(
            text = stringResource(R.string.playback_buffer_target_managed_hint)
        )
    }
    if (!budgetManaged && bufferSizeMb > safeMaxMb) {
        val isDanger = bufferSizeMb > warningMaxMb
        SettingsNote(
            text = if (isDanger) {
                stringResource(R.string.playback_buffer_target_danger_warning, warningMaxMb)
            } else {
                stringResource(R.string.playback_buffer_target_warning, safeMaxMb)
            },
            tone = if (isDanger) SettingsNoteTone.Danger else SettingsNoteTone.Warning
        )
    }

    SettingsToggleRow(
        title = stringResource(R.string.playback_buffer_allow_large),
        subtitle = stringResource(R.string.playback_buffer_allow_large_sub),
        checked = settings.allowLargeTargetBuffer,
        onToggle = { updateMemory { setAllowLargeTargetBuffer(!settings.allowLargeTargetBuffer) } },
        enabled = !settings.bufferBudgetManaged
    )

    SettingsResetButton(
        text = stringResource(R.string.playback_reset_to_default),
        onClick = { updateMemory { resetBufferSettingsToDefaults() } }
    )
}

@Composable
private fun DiskCacheControls(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate
) {
    val context = LocalContext.current

    SettingsToggleRow(
        title = stringResource(R.string.playback_cache_vod),
        subtitle = stringResource(R.string.playback_cache_vod_sub),
        checked = settings.vodCacheEnabled,
        onToggle = { onUpdate { setVodCacheEnabled(!settings.vodCacheEnabled) } },
        expandSubtitleOnFocus = true
    )

    if (!settings.vodCacheEnabled) return

    val autoMode = settings.vodCacheSizeMode == VodCacheSizeMode.AUTO
    SettingsToggleRow(
        title = stringResource(R.string.playback_cache_auto_size),
        subtitle = stringResource(R.string.playback_cache_auto_size_sub),
        checked = autoMode,
        onToggle = {
            onUpdate { setVodCacheSizeMode(if (autoMode) VodCacheSizeMode.MANUAL else VodCacheSizeMode.AUTO) }
        },
        modifier = Modifier.padding(start = NuvioTheme.spacing.xxl)
    )

    val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
    val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
    if (!autoMode) {
        val manualCacheMb = settings.vodCacheSizeMb.coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, maxManualCacheMb)
        SliderSettingsItem(
            title = stringResource(R.string.playback_cache_vod_size),
            subtitle = stringResource(R.string.playback_cache_vod_size_sub),
            value = manualCacheMb,
            valueText = "$manualCacheMb MB",
            minValue = PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
            maxValue = maxManualCacheMb,
            step = 50,
            onValueChange = { mb -> onUpdate { setVodCacheSizeMb(mb) } }
        )
    }

    val rangeInfo = stringResource(R.string.playback_cache_info_range, PlayerSettings.MIN_VOD_CACHE_SIZE_MB, maxManualCacheMb)
    val autoInfo = stringResource(R.string.playback_cache_info_auto)
    val headroomInfo = stringResource(R.string.playback_cache_info_manual_headroom, VOD_CACHE_FREE_SPACE_RESERVE_MB.toInt())
    val freeDiskInfo = stringResource(R.string.playback_cache_info_free_disk, formatStorageSize(freeDiskBytes))
    val restartInfo = stringResource(R.string.playback_cache_info_restart)
    SettingsNote(
        text = buildString {
            append(rangeInfo)
            append(" ")
            append(autoInfo)
            append(" ")
            append(headroomInfo)
            if (!autoMode) {
                append(" ")
                append(freeDiskInfo)
                append(" ")
                append(restartInfo)
            }
        }
    )
    SettingsNote(text = stringResource(R.string.playback_cache_write_warning), tone = SettingsNoteTone.Warning)
}

@Composable
private fun ParallelNetworkControls(
    settings: PlayerSettings,
    updateMemory: PlaybackSettingsUpdate
) {
    SettingsToggleRow(
        title = stringResource(R.string.playback_net_http2),
        subtitle = stringResource(R.string.playback_net_http2_sub),
        checked = settings.enableHttp2,
        onToggle = { updateMemory { setEnableHttp2(!settings.enableHttp2) } }
    )
    SettingsToggleRow(
        title = stringResource(R.string.playback_net_parallel),
        subtitle = stringResource(R.string.playback_net_parallel_sub),
        checked = settings.useParallelConnections,
        onToggle = { updateMemory { setUseParallelConnections(!settings.useParallelConnections) } }
    )

    if (settings.useParallelConnections) {
        SliderSettingsItem(
            title = stringResource(R.string.playback_net_connection_count),
            subtitle = stringResource(R.string.playback_net_connection_count_sub),
            value = settings.parallelConnectionCount,
            valueText = settings.parallelConnectionCount.toString(),
            minValue = MemoryBudget.MIN_CONNECTIONS,
            maxValue = if (settings.nuvioPerformanceModeEnabled) 16 else MemoryBudget.MAX_CONNECTIONS,
            step = 1,
            onValueChange = { count -> updateMemory { setParallelConnectionCount(count) } }
        )

        val maxChunkSizeMb = if (settings.nuvioPerformanceModeEnabled) {
            MemoryBudget.tierMaxChunkMb
        } else {
            MemoryBudget.maxChunkMb(
                MemoryBudget.effectiveBufferMb(settings.bufferSettings.targetBufferSizeMb),
                settings.parallelConnectionCount
            )
        }
        val chunkSizes = PARALLEL_CHUNK_SIZES.filter { it.first <= maxChunkSizeMb * 1024 }
        val currentKb = settings.parallelChunkSizeKb
        val currentIndex = chunkSizes.indexOfFirst { it.first == currentKb }.coerceAtLeast(0)
        SliderSettingsItem(
            title = stringResource(R.string.playback_net_chunk_size),
            subtitle = stringResource(R.string.playback_net_chunk_size_sub),
            value = currentIndex,
            valueText = chunkSizes.getOrNull(currentIndex)?.second ?: "${currentKb / 1024} MB",
            minValue = 0,
            maxValue = (chunkSizes.size - 1).coerceAtLeast(0),
            step = 1,
            onValueChange = { index ->
                chunkSizes.getOrNull(index)?.let { (kb, _) -> updateMemory { setParallelChunkSizeKb(kb) } }
            }
        )
    }

    SettingsResetButton(
        text = stringResource(R.string.playback_reset_to_default),
        onClick = { updateMemory { resetNetworkSettingsToDefaults() } }
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackMemoryUsageCard(settings: PlayerSettings) {
    val context = LocalContext.current
    val effectiveBufferMb = when {
        settings.nuvioPerformanceModeEnabled -> {
            if (settings.bufferEngineEnabled && !settings.bufferBudgetManaged) {
                MemoryBudget.effectiveBufferMb(settings.bufferSettings.targetBufferSizeMb)
            } else {
                NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
            }
        }
        settings.bufferEngineEnabled -> {
            if (settings.bufferBudgetManaged) MemoryBudget.budgetMb
            else MemoryBudget.effectiveBufferMb(settings.bufferSettings.targetBufferSizeMb)
        }
        else -> MemoryBudget.defaultBufferSizeMb
    }
    val parallelActive = settings.parallelNetworkEnabled && settings.useParallelConnections
    val chunkMb = Math.ceil(settings.parallelChunkSizeKb / 1024.0).toInt().coerceAtMost(MemoryBudget.tierMaxChunkMb)
    val parallelOverheadMb = if (parallelActive) {
        MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, chunkMb)
    } else {
        0
    }
    val totalUsageMb = if (settings.nuvioPerformanceModeEnabled) {
        effectiveBufferMb
    } else {
        MemoryBudget.totalUsageMb(effectiveBufferMb, settings.parallelConnectionCount, chunkMb, parallelActive)
    }
    val safeLimitMb = if (settings.nuvioPerformanceModeEnabled) {
        NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
    } else {
        MemoryBudget.budgetMb
    }
    val warningLimitMb = if (settings.nuvioPerformanceModeEnabled) {
        NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
    } else {
        (MemoryBudget.budgetMb * 1.25f).toInt()
    }
    val usageColor = when (MemoryBudget.getUsageStatus(totalUsageMb, safeLimitMb, warningLimitMb)) {
        MemoryUsageStatus.DANGER -> Color(0xFFF44336)
        MemoryUsageStatus.WARNING -> Color(0xFFFF9800)
        MemoryUsageStatus.SAFE -> Color(0xFF4CAF50)
    }
    val effectiveExoMb = (effectiveBufferMb - parallelOverheadMb).coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
    val baseText = stringResource(R.string.playback_estimated_memory_usage, totalUsageMb, warningLimitMb)
    val usageText = if (settings.nuvioPerformanceModeEnabled && parallelActive && parallelOverheadMb > 0) {
        baseText
            .replace("$totalUsageMb / $warningLimitMb", "$totalUsageMb($effectiveExoMb+$parallelOverheadMb)/$warningLimitMb")
            .replace("$totalUsageMb /", "$totalUsageMb($effectiveExoMb+$parallelOverheadMb)/")
    } else {
        baseText
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = NuvioTheme.colors.BackgroundCard, shape = RoundedCornerShape(10.dp))
            .border(NuvioTheme.spacing.hairline, usageColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = usageText,
            style = MaterialTheme.typography.bodySmall,
            color = usageColor
        )
    }
}

private val PARALLEL_CHUNK_SIZES = listOf(
    256 to "256 KB",
    512 to "512 KB",
    1024 to "1 MB",
    2048 to "2 MB",
    4096 to "4 MB",
    8192 to "8 MB",
    16384 to "16 MB",
    24576 to "24 MB",
    32768 to "32 MB",
    49152 to "48 MB",
    65536 to "64 MB",
    98304 to "96 MB",
    131072 to "128 MB"
)

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
