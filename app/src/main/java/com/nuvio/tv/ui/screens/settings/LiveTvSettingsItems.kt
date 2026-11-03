@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "Live TV" row: shows Live TV (IPTV channel lists) in the menu. Off by default. Under it, while
 * Live TV is on, "Channel previews" and their sound (both on by default).
 */
internal fun LazyListScope.liveTvSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "live_tv_enabled") {
        val context = LocalContext.current
        LiveTvPreferences.ensureLoaded(context)
        val checked by LiveTvPreferences.enabled.collectAsStateWithLifecycle()
        val previews by LiveTvPreferences.previews.collectAsStateWithLifecycle()
        val previewSound by LiveTvPreferences.previewSound.collectAsStateWithLifecycle()

        // One item, so the previews row adds no list spacing while it is hidden.
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            ToggleSettingsItem(
                icon = Icons.Default.LiveTv,
                title = stringResource(R.string.settings_live_tv_title),
                subtitle = stringResource(R.string.settings_live_tv_description),
                isChecked = checked,
                onCheckedChange = { LiveTvPreferences.setEnabled(context, it) },
                onFocused = onItemFocused,
            )
            if (checked) {
                ToggleSettingsItem(
                    icon = Icons.Default.PictureInPicture,
                    title = stringResource(R.string.settings_live_tv_previews_title),
                    subtitle = stringResource(R.string.settings_live_tv_previews_description),
                    isChecked = previews,
                    onCheckedChange = { LiveTvPreferences.setPreviews(context, it) },
                    onFocused = onItemFocused,
                )
                if (previews) {
                    ToggleSettingsItem(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        title = stringResource(R.string.settings_live_tv_preview_sound_title),
                        subtitle = stringResource(R.string.settings_live_tv_preview_sound_description),
                        isChecked = previewSound,
                        onCheckedChange = { LiveTvPreferences.setPreviewSound(context, it) },
                        onFocused = onItemFocused,
                    )
                }
            }
        }
    }
}
