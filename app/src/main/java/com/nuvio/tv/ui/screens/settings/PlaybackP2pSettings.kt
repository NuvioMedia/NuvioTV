package com.nuvio.tv.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentCacheClearResult
import com.nuvio.tv.core.torrent.TorrentCacheSize
import com.nuvio.tv.core.torrent.TorrentCacheState
import com.nuvio.tv.core.torrent.TorrentProfile

internal data class P2pSettingsUi(
    val enabled: Boolean = false,
    val hideStats: Boolean = false,
    val profile: TorrentProfile = TorrentProfile.BALANCED,
    val cacheSize: TorrentCacheSize = TorrentCacheSize.GB_2,
    val cacheSummary: String = "",
    val cacheClearEnabled: Boolean = false
)

@Composable
internal fun PlaybackP2pSection(
    p2p: P2pSettingsUi,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit,
    onClearTorrentCache: () -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.p2p_consent_enable),
        subtitle = null,
        checked = p2p.enabled,
        onToggle = {
            if (p2p.enabled) {
                onUpdate { setP2pEnabled(false) }
            } else {
                onOpenDialog(PlaybackDialog.P2P_CONSENT)
            }
        }
    )
    SettingsToggleRow(
        title = stringResource(R.string.settings_p2p_hide_stats_title),
        subtitle = stringResource(R.string.settings_p2p_hide_stats_subtitle),
        checked = p2p.hideStats,
        onToggle = { onUpdate { setHideTorrentStats(!p2p.hideStats) } }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_p2p_profile_title),
        subtitle = stringResource(profileDescription(p2p.profile)),
        value = stringResource(profileLabel(p2p.profile)),
        onClick = { onOpenDialog(PlaybackDialog.TORRENT_PROFILE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_p2p_cache_size_title),
        subtitle = stringResource(R.string.settings_p2p_cache_size_description),
        value = stringResource(cacheSizeLabel(p2p.cacheSize)),
        onClick = { onOpenDialog(PlaybackDialog.TORRENT_CACHE_SIZE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.settings_p2p_clear_cache_title),
        subtitle = p2p.cacheSummary,
        onClick = onClearTorrentCache,
        enabled = p2p.cacheClearEnabled
    )
}

@Composable
internal fun P2pSettingsDialogs(
    dialog: PlaybackDialog?,
    p2p: P2pSettingsUi,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        PlaybackDialog.TORRENT_PROFILE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_p2p_profile_title),
            options = TorrentProfile.entries.map { profile ->
                SettingsPickerOption(
                    value = profile,
                    title = stringResource(profileLabel(profile)),
                    description = stringResource(profileDescription(profile))
                )
            },
            selectedValue = p2p.profile,
            onOptionSelected = { profile ->
                onUpdate { setTorrentProfile(profile) }
                onDismiss()
            },
            onDismiss = onDismiss,
            width = 440.dp,
            maxHeight = 360.dp
        )
        PlaybackDialog.TORRENT_CACHE_SIZE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.settings_p2p_cache_size_title),
            subtitle = stringResource(R.string.settings_p2p_cache_size_description),
            options = TorrentCacheSize.entries.map { size ->
                SettingsPickerOption(value = size, title = stringResource(cacheSizeLabel(size)))
            },
            selectedValue = p2p.cacheSize,
            onOptionSelected = { size ->
                onUpdate { setTorrentCacheSize(size) }
                onDismiss()
            },
            onDismiss = onDismiss,
            width = 420.dp,
            maxHeight = 360.dp
        )
        else -> Unit
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
