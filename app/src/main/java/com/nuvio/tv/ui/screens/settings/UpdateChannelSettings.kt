package com.nuvio.tv.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.updater.UpdateChannel
import com.nuvio.tv.updater.UpdateViewModel

@Composable
internal fun UpdateChannelSettings(
    initialFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: UpdateViewModel = hiltViewModel(context as ComponentActivity)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showChannelDialog by rememberSaveable { mutableStateOf(false) }
    val channelName = when (state.updateChannel) {
        UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
        UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
    }

    SettingsActionRow(
        title = stringResource(R.string.about_update_channel_title),
        subtitle = stringResource(R.string.about_update_channel_subtitle),
        value = channelName,
        onClick = { showChannelDialog = true },
        modifier = modifier.then(
            if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester)
            else Modifier
        )
    )

    SettingsToggleRow(
        title = stringResource(R.string.about_update_banner_title),
        subtitle = stringResource(R.string.about_update_banner_subtitle),
        checked = state.updateBannerEnabled,
        onToggle = {
            viewModel.setUpdateBannerEnabled(!state.updateBannerEnabled)
        }
    )

    SettingsActionRow(
        title = stringResource(R.string.about_check_updates),
        subtitle = stringResource(R.string.about_check_updates_subtitle),
        trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = {
            viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
        }
    )

    if (showChannelDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.about_update_channel_title),
            subtitle = stringResource(R.string.update_channel_dialog_subtitle),
            options = listOf(
                SettingsPickerOption(
                    value = UpdateChannel.STABLE,
                    title = stringResource(R.string.update_channel_stable),
                    description = stringResource(R.string.update_channel_stable_description)
                ),
                SettingsPickerOption(
                    value = UpdateChannel.BETA,
                    title = stringResource(R.string.update_channel_beta),
                    description = stringResource(R.string.update_channel_beta_description)
                )
            ),
            selectedValue = state.updateChannel,
            onOptionSelected = { channel ->
                viewModel.setUpdateChannel(channel)
                showChannelDialog = false
            },
            onDismiss = { showChannelDialog = false },
            width = 500.dp,
            maxHeight = 320.dp
        )
    }
}
