@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentCacheClearResult
import com.nuvio.tv.core.torrent.TorrentCacheSize
import com.nuvio.tv.core.torrent.TorrentCacheState
import com.nuvio.tv.core.torrent.TorrentProfile
import com.nuvio.tv.ui.theme.NuvioTheme

internal data class P2pSettingsUi(
    val enabled: Boolean = false,
    val hideStats: Boolean = false,
    val profile: TorrentProfile = TorrentProfile.BALANCED,
    val cacheSize: TorrentCacheSize = TorrentCacheSize.GB_2,
    val cacheSummary: String = "",
    val cacheClearEnabled: Boolean = false
)

internal fun LazyListScope.p2pSettingsItems(
    ui: P2pSettingsUi,
    onSetP2pEnabled: (Boolean) -> Unit,
    onSetHideTorrentStats: (Boolean) -> Unit,
    onSetTorrentProfile: (TorrentProfile) -> Unit,
    onSetTorrentCacheSize: (TorrentCacheSize) -> Unit,
    onClearTorrentCache: () -> Unit,
    onFocused: () -> Unit
) {
    item(key = "p2p_enabled") {
        ToggleSettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_p2p_title),
            subtitle = stringResource(R.string.settings_p2p_subtitle),
            isChecked = ui.enabled,
            onCheckedChange = onSetP2pEnabled,
            onFocused = onFocused
        )
    }
    item(key = "p2p_hide_stats") {
        ToggleSettingsItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_p2p_hide_stats_title),
            subtitle = stringResource(R.string.settings_p2p_hide_stats_subtitle),
            isChecked = ui.hideStats,
            onCheckedChange = onSetHideTorrentStats,
            onFocused = onFocused
        )
    }
    item(key = "p2p_profile") {
        ChoiceRow(
            title = stringResource(R.string.settings_p2p_profile_title),
            subtitle = stringResource(profileDescription(ui.profile)),
            options = TorrentProfile.entries,
            selected = ui.profile,
            label = { stringResource(profileLabel(it)) },
            onSelect = onSetTorrentProfile,
            onFocused = onFocused
        )
    }
    item(key = "p2p_cache_size") {
        ChoiceRow(
            title = stringResource(R.string.settings_p2p_cache_size_title),
            subtitle = stringResource(R.string.settings_p2p_cache_size_description),
            options = TorrentCacheSize.entries,
            selected = ui.cacheSize,
            label = { stringResource(cacheSizeLabel(it)) },
            onSelect = onSetTorrentCacheSize,
            onFocused = onFocused
        )
    }
    item(key = "p2p_clear_cache") {
        NavigationSettingsItem(
            icon = Icons.Default.Delete,
            title = stringResource(R.string.settings_p2p_clear_cache_title),
            subtitle = ui.cacheSummary,
            onClick = onClearTorrentCache,
            onFocused = onFocused,
            enabled = ui.cacheClearEnabled
        )
    }
}

@Composable
internal fun torrentCacheSummary(
    cacheState: TorrentCacheState,
    clearAvailable: Boolean,
    clearResult: TorrentCacheClearResult?,
    clearFailed: Boolean
): String {
    val context = LocalContext.current
    return when {
        cacheState.isClearing -> stringResource(R.string.settings_p2p_clear_cache_clearing)
        !clearAvailable -> stringResource(R.string.settings_p2p_clear_cache_playback_active)
        clearFailed -> stringResource(R.string.settings_p2p_clear_cache_failed)
        clearResult != null -> stringResource(
            R.string.settings_p2p_clear_cache_done,
            Formatter.formatShortFileSize(context, clearResult.reclaimedBytes)
        )
        !cacheState.hasMeasurement -> stringResource(R.string.settings_p2p_clear_cache_usage_pending)
        else -> stringResource(
            R.string.settings_p2p_clear_cache_usage,
            Formatter.formatShortFileSize(context, cacheState.usedBytes)
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onFocused: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                SettingsChoiceChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    onFocused = onFocused
                )
            }
        }
    }
}

private fun profileLabel(profile: TorrentProfile): Int = when (profile) {
    TorrentProfile.SOFT -> R.string.settings_p2p_profile_soft
    TorrentProfile.BALANCED -> R.string.settings_p2p_profile_balanced
    TorrentProfile.FAST -> R.string.settings_p2p_profile_fast
}

private fun profileDescription(profile: TorrentProfile): Int = when (profile) {
    TorrentProfile.SOFT -> R.string.settings_p2p_profile_soft_description
    TorrentProfile.BALANCED -> R.string.settings_p2p_profile_balanced_description
    TorrentProfile.FAST -> R.string.settings_p2p_profile_fast_description
}

private fun cacheSizeLabel(size: TorrentCacheSize): Int = when (size) {
    TorrentCacheSize.NONE -> R.string.settings_p2p_cache_none
    TorrentCacheSize.GB_2 -> R.string.settings_p2p_cache_2_gb
    TorrentCacheSize.GB_5 -> R.string.settings_p2p_cache_5_gb
    TorrentCacheSize.GB_10 -> R.string.settings_p2p_cache_10_gb
}
