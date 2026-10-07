package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings

/**
 * "Generate previews on device" setting row in PlaybackVideoSettings.
 */
@Composable
internal fun SeekPreviewSettingsRow() {
    val context = LocalContext.current
    val enabled by LocalSeekPreviewSettings.enabled(context).collectAsStateWithLifecycle(initialValue = true)
    SettingsToggleRow(
        title = stringResource(R.string.settings_seek_preview_local),
        subtitle = stringResource(R.string.settings_seek_preview_local_description),
        checked = enabled,
        onToggle = { LocalSeekPreviewSettings.setEnabled(context, !enabled) }
    )
}
