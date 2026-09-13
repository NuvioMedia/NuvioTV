package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.UsenetConfiguration

@Composable
internal fun UsenetSettingsCard(configuration: UsenetConfiguration, update: (UsenetConfiguration) -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    val automatic = stringResource(R.string.usenet_automatic)
    val profiles = listOf(
        SettingsPickerOption("low-memory", stringResource(R.string.usenet_low_memory)),
        SettingsPickerOption("balanced", stringResource(R.string.usenet_balanced)),
        SettingsPickerOption("throughput", stringResource(R.string.usenet_throughput))
    )
    SettingsGroupCard(title = stringResource(R.string.usenet_title)) {
        SettingsToggleRow(title = stringResource(R.string.usenet_prefetch_results),
            subtitle = stringResource(R.string.usenet_prefetch_results_description),
            checked = configuration.prefetchResults,
            onToggle = { update(configuration.copy(prefetchResults = !configuration.prefetchResults)) })
        SettingsToggleRow(title = stringResource(R.string.usenet_cache_nzb),
            subtitle = stringResource(R.string.usenet_cache_nzb_description),
            checked = configuration.cacheNzb,
            onToggle = { update(configuration.copy(cacheNzb = !configuration.cacheNzb)) })
        SettingsToggleRow(title = stringResource(R.string.usenet_fast_mkv),
            subtitle = stringResource(R.string.usenet_fast_mkv_description),
            checked = configuration.fastMkvStartup,
            onToggle = { update(configuration.copy(fastMkvStartup = !configuration.fastMkvStartup)) })
        SettingsToggleRow(title = stringResource(R.string.usenet_fast_nzb),
            subtitle = stringResource(R.string.usenet_fast_nzb_description),
            checked = configuration.fastNzbFetch,
            onToggle = { update(configuration.copy(fastNzbFetch = !configuration.fastNzbFetch)) })
        SettingsToggleRow(title = stringResource(R.string.usenet_prewarm),
            subtitle = stringResource(R.string.usenet_prewarm_description),
            checked = configuration.prewarmOnLaunch,
            onToggle = { update(configuration.copy(prewarmOnLaunch = !configuration.prewarmOnLaunch)) })
        SettingsActionRow(title = stringResource(R.string.usenet_profile),
            subtitle = stringResource(R.string.usenet_profile_description),
            value = profiles.first { it.value == configuration.profile }.title, onClick = { picker = "profile" })
        SettingsActionRow(title = stringResource(R.string.usenet_read_ahead),
            subtitle = stringResource(R.string.usenet_read_ahead_description),
            value = configuration.readAhead.takeIf { it > 0 }?.toString() ?: automatic, onClick = { picker = "ahead" })
        SettingsActionRow(title = stringResource(R.string.usenet_connections),
            subtitle = stringResource(R.string.usenet_connections_description),
            value = configuration.maxConnections.takeIf { it > 0 }?.toString() ?: automatic, onClick = { picker = "connections" })
    }
    when (picker) {
        "profile" -> SettingsSingleChoiceDialog(title = stringResource(R.string.usenet_profile), options = profiles,
            selectedValue = configuration.profile, onOptionSelected = { update(configuration.copy(profile = it)); picker = null }, onDismiss = { picker = null })
        "ahead" -> SettingsSingleChoiceDialog(title = stringResource(R.string.usenet_read_ahead),
            options = listOf(0, 2, 4, 8, 16, 24, 32, 48, 64, 96, 128, 256, 512).map { SettingsPickerOption(it, if (it == 0) automatic else it.toString()) },
            selectedValue = configuration.readAhead, onOptionSelected = { update(configuration.copy(readAhead = it)); picker = null }, onDismiss = { picker = null })
        "connections" -> SettingsSingleChoiceDialog(title = stringResource(R.string.usenet_connections),
            options = listOf(0, 4, 8, 12, 16, 20, 24, 32, 40, 50, 60, 80, 100, 150, 200, 300, 500).map { SettingsPickerOption(it, if (it == 0) automatic else it.toString()) },
            selectedValue = configuration.maxConnections, onOptionSelected = { update(configuration.copy(maxConnections = it)); picker = null }, onDismiss = { picker = null })
    }
}
