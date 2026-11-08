package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences

/** AutoSync-owned settings rows; keeps AutoSync state out of NuvioTV PlayerSettingsDataStore. */
@Composable
internal fun autoSyncSettingsItems(enabled: Boolean) {
    val context = LocalContext.current
    AutoSyncPreferences.ensureLoaded(context)
    val checked by AutoSyncPreferences.enabled.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.autosync_setting_title),
        subtitle = stringResource(R.string.autosync_setting_description),
        checked = checked,
        onToggle = { AutoSyncPreferences.setEnabled(context, !checked) },
        enabled = enabled,
    )

    // Tolerance and search depth only matter while AutoSync is on.
    if (checked) {
        val toleranceMs by AutoSyncPreferences.syncToleranceMs.collectAsStateWithLifecycle()
        val thorough by AutoSyncPreferences.aggressiveMode.collectAsStateWithLifecycle()

        SliderSettingsItem(
            title = stringResource(R.string.autosync_tolerance_title),
            subtitle = stringResource(R.string.autosync_tolerance_description),
            values = AutoSyncPreferences.syncToleranceOptionsMs,
            selected = toleranceMs,
            valueText = if (toleranceMs > 0) {
                stringResource(R.string.autosync_tolerance_value, toleranceMs)
            } else {
                stringResource(R.string.autosync_tolerance_off)
            },
            onValueChange = { AutoSyncPreferences.setSyncToleranceMs(context, it) },
            enabled = enabled,
        )

        SettingsToggleRow(
            title = stringResource(R.string.autosync_thorough_title),
            subtitle = stringResource(R.string.autosync_thorough_description),
            checked = thorough,
            onToggle = { AutoSyncPreferences.setAggressiveMode(context, !thorough) },
            enabled = enabled,
        )
    }
}
