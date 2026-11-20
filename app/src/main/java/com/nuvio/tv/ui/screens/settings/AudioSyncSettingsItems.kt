package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.audiosync.AudioSamplingMode
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncFallback
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.audiosync.SubtitleSyncStatus
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences

/** Audio fallback settings, beside Auto Sync in Playback → Subtitles. */
@Composable
internal fun audioSyncSettingsItems(enabled: Boolean) {
    AudioSyncFallback.initialize(LocalContext.current)
    val autoSyncOn by AutoSyncPreferences.enabled.collectAsStateWithLifecycle()
    val fallbackOn by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.audio_sync_setting_title),
        subtitle = stringResource(R.string.audio_sync_setting_description),
        checked = fallbackOn,
        onToggle = { AudioSyncSettings.setFallbackEnabled(!fallbackOn) },
        enabled = enabled && autoSyncOn,
        expandSubtitleOnFocus = true,
    )
    if (autoSyncOn && fallbackOn) {
        val model by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()
        val sampling by AudioSyncSettings.samplingMode.collectAsStateWithLifecycle()
        SettingsActionRow(
            title = stringResource(R.string.audio_sync_model_title, model.sizeMb),
            subtitle = when {
                model.downloading -> stringResource(R.string.audio_sync_model_downloading, (model.progress * 100).toInt())
                model.downloaded -> stringResource(R.string.audio_sync_model_ready)
                model.error != null -> stringResource(R.string.audio_sync_model_failed)
                else -> stringResource(R.string.audio_sync_model_description)
            },
            value = stringResource(if (model.downloaded) R.string.audio_sync_model_delete else R.string.audio_sync_model_download),
            enabled = enabled && model.supported && !model.downloading,
            onClick = {
                if (model.downloaded) SubtitleSyncStatus.modelActions?.delete()
                else SubtitleSyncStatus.modelActions?.download()
            },
        )
        var showSamplingDialog by remember { mutableStateOf(false) }
        SettingsActionRow(
            title = stringResource(R.string.audio_sync_sampling_title),
            subtitle = stringResource(R.string.audio_sync_sampling_description),
            value = samplingLabel(sampling),
            enabled = enabled,
            onClick = { showSamplingDialog = true },
        )
        if (showSamplingDialog) {
            SettingsSingleChoiceDialog(
                title = stringResource(R.string.audio_sync_sampling_title),
                subtitle = stringResource(R.string.audio_sync_sampling_description),
                options = AudioSamplingMode.entries.map { mode ->
                    SettingsPickerOption(mode, samplingLabel(mode), samplingDescription(mode))
                },
                selectedValue = sampling,
                onOptionSelected = { mode ->
                    AudioSyncSettings.setSamplingMode(mode)
                    showSamplingDialog = false
                },
                onDismiss = { showSamplingDialog = false },
                width = 460.dp,
                maxHeight = 420.dp,
            )
        }
    }
}

@Composable
private fun samplingLabel(mode: AudioSamplingMode): String = stringResource(
    when (mode) {
        AudioSamplingMode.OFF -> R.string.audio_sync_sampling_off
        AudioSamplingMode.LOW -> R.string.audio_sync_sampling_low
        AudioSamplingMode.HIGH -> R.string.audio_sync_sampling_high
    },
)

@Composable
private fun samplingDescription(mode: AudioSamplingMode): String = stringResource(
    when (mode) {
        AudioSamplingMode.OFF -> R.string.audio_sync_sampling_off_description
        AudioSamplingMode.LOW -> R.string.audio_sync_sampling_low_description
        AudioSamplingMode.HIGH -> R.string.audio_sync_sampling_high_description
    },
)
