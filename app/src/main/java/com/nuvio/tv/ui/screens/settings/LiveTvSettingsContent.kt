package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvSettingsContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        item(key = "live_tv_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_live_tv_title),
                subtitle = stringResource(R.string.reshaped_group_live_tv_description),
            )
        }
        liveTvSettingsItems()
    }
}
