package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.SurroundFormatMode
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.screens.player.PlayerTunnelAvSyncPolicy

internal fun rememberedTunnelStallClasses(settings: PlayerSettings): Set<String> =
    settings.tunnelDeadAudioClasses + PlayerTunnelAvSyncPolicy.deadAudioClasses

internal fun tunnelStallSubtitle(
    classes: Set<String>,
    remembered: (String) -> String,
    nothingRemembered: String
): String {
    val labels = PlayerTunnelAvSyncPolicy.memoLabels(classes)
    return if (labels.isEmpty()) nothingRemembered else remembered(labels.joinToString(", "))
}

@Composable
internal fun PlaybackAudioSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val enabled = settings.playerPreference != PlayerPreference.EXTERNAL
    val isExoEngine = settings.usesExoPlayerEngine

    SettingsNote(text = stringResource(R.string.audio_passthrough_info))

    SettingsActionRow(
        title = stringResource(R.string.audio_preferred_lang),
        subtitle = null,
        value = audioLanguageLabel(settings.preferredAudioLanguage),
        enabled = enabled,
        onClick = { onOpenDialog(PlaybackDialog.AUDIO_LANGUAGE) }
    )
    SettingsActionRow(
        title = stringResource(R.string.sub_secondary_lang),
        subtitle = null,
        value = secondaryAudioLanguageLabel(settings.secondaryPreferredAudioLanguage),
        enabled = enabled,
        onClick = { onOpenDialog(PlaybackDialog.SECONDARY_AUDIO_LANGUAGE) }
    )
    if (isExoEngine) {
        SettingsToggleRow(
            title = stringResource(R.string.audio_skip_silence),
            subtitle = stringResource(R.string.audio_skip_silence_sub),
            checked = settings.skipSilence,
            onToggle = { onUpdate { setSkipSilence(!settings.skipSilence) } },
            enabled = enabled
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.audio_remember_delay_per_device),
        subtitle = stringResource(R.string.audio_remember_delay_per_device_sub),
        checked = settings.rememberAudioDelayPerDevice,
        onToggle = { onUpdate { setRememberAudioDelayPerDevice(!settings.rememberAudioDelayPerDevice) } },
        enabled = enabled
    )

    if (isExoEngine) {
        SettingsSectionLabel(text = stringResource(R.string.audio_advanced_section))
        SettingsNote(text = stringResource(R.string.audio_advanced_warning), tone = SettingsNoteTone.Warning)

        SettingsActionRow(
            title = stringResource(R.string.audio_decoder_priority),
            subtitle = null,
            value = decoderPriorityLabel(settings.decoderPriority),
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.DECODER_PRIORITY) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_enable_downmix_title),
            subtitle = stringResource(R.string.audio_enable_downmix_subtitle),
            checked = settings.effectiveDownmixEnabled,
            onToggle = { onUpdate { setDownmixEnabled(!settings.effectiveDownmixEnabled) } },
            enabled = enabled && settings.isPreferAppDecoder
        )
        if (settings.effectiveDownmixEnabled) {
            SettingsActionRow(
                title = stringResource(R.string.audio_number_of_channels),
                subtitle = null,
                value = settings.audioOutputChannels.displayLabel,
                enabled = enabled,
                onClick = { onOpenDialog(PlaybackDialog.AUDIO_OUTPUT_CHANNELS) }
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_maintain_original_audio_on_downmix_title),
                subtitle = stringResource(R.string.audio_maintain_original_audio_on_downmix_subtitle),
                checked = settings.maintainOriginalAudioOnDownmix,
                onToggle = { onUpdate { setMaintainOriginalAudioOnDownmix(!settings.maintainOriginalAudioOnDownmix) } },
                enabled = enabled
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.audio_tunneled),
            subtitle = stringResource(R.string.audio_tunneled_sub),
            checked = settings.effectiveTunnelingEnabled,
            onToggle = { onUpdate { setTunnelingEnabled(!settings.effectiveTunnelingEnabled) } },
            enabled = enabled && settings.isTunnelingCompatible
        )

        var stallMemoCleared by remember { mutableStateOf(false) }
        val stallClasses = if (stallMemoCleared) {
            settings.tunnelDeadAudioClasses
        } else {
            rememberedTunnelStallClasses(settings)
        }
        val context = LocalContext.current
        val tunnelStallsForgotten = stringResource(R.string.audio_forget_tunnel_stalls_done)
        SettingsActionRow(
            title = stringResource(R.string.audio_forget_tunnel_stalls_title),
            subtitle = tunnelStallSubtitle(
                stallClasses,
                remembered = { formats -> context.getString(R.string.audio_forget_tunnel_stalls_sub, formats) },
                nothingRemembered = stringResource(R.string.audio_forget_tunnel_stalls_none)
            ),
            value = null,
            enabled = enabled && stallClasses.isNotEmpty(),
            onClick = {
                stallMemoCleared = true
                onUpdate {
                    forgetTunnelStalls()
                    android.widget.Toast.makeText(
                        context,
                        tunnelStallsForgotten,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        SettingsSectionLabel(text = stringResource(R.string.audio_surround_header))

        SettingsActionRow(
            title = stringResource(R.string.audio_surround_format_mode),
            subtitle = null,
            value = surroundFormatModeLabel(settings.surroundFormatMode),
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.SURROUND_FORMAT_MODE) }
        )

        if (settings.surroundFormatMode == SurroundFormatMode.MANUAL) {
            val switchesEnabled = enabled && settings.decoderPriority != 0
            val deviceOnly = settings.decoderPriority == 0
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_allow_ac3),
                subtitle = stringResource(R.string.audio_surround_allow_ac3_sub),
                checked = settings.allowAc3Passthrough || deviceOnly,
                onToggle = { onUpdate { setAllowAc3Passthrough(!settings.allowAc3Passthrough) } },
                enabled = switchesEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_allow_eac3),
                subtitle = stringResource(R.string.audio_surround_allow_eac3_sub),
                checked = settings.allowEac3Passthrough || deviceOnly,
                onToggle = { onUpdate { setAllowEac3Passthrough(!settings.allowEac3Passthrough) } },
                enabled = switchesEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_allow_truehd),
                subtitle = stringResource(R.string.audio_surround_allow_truehd_sub),
                checked = settings.allowTruehdPassthrough || deviceOnly,
                onToggle = { onUpdate { setAllowTruehdPassthrough(!settings.allowTruehdPassthrough) } },
                enabled = switchesEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_allow_dts),
                subtitle = stringResource(R.string.audio_surround_allow_dts_sub),
                checked = settings.allowDtsPassthrough || deviceOnly,
                onToggle = { onUpdate { setAllowDtsPassthrough(!settings.allowDtsPassthrough) } },
                enabled = switchesEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_allow_dtshd),
                subtitle = stringResource(R.string.audio_surround_allow_dtshd_sub),
                checked = settings.allowDtshdPassthrough || deviceOnly,
                onToggle = { onUpdate { setAllowDtshdPassthrough(!settings.allowDtshdPassthrough) } },
                enabled = switchesEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.audio_surround_transcode_denied),
                subtitle = stringResource(R.string.audio_surround_transcode_denied_sub),
                checked = settings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3 && !deviceOnly,
                onToggle = {
                    val next = settings.deniedCodecHandling != DeniedCodecHandling.TRANSCODE_AC3
                    onUpdate { setTranscodeDeniedToAc3(next) }
                },
                enabled = switchesEnabled
            )
        }

        SettingsActionRow(
            title = stringResource(R.string.audio_surround_channel_target),
            subtitle = null,
            value = surroundChannelTargetLabel(settings.surroundChannelTarget),
            enabled = enabled && settings.decoderPriority != 0,
            onClick = { onOpenDialog(PlaybackDialog.SURROUND_CHANNEL_TARGET) }
        )

        SettingsToggleRow(
            title = stringResource(R.string.audio_use_system_passthrough),
            subtitle = stringResource(R.string.audio_use_system_passthrough_sub),
            checked = settings.useSystemPassthrough,
            onToggle = { onUpdate { setUseSystemPassthrough(!settings.useSystemPassthrough) } },
            enabled = enabled
        )

        SettingsActionRow(
            title = stringResource(R.string.audio_surround_reset_iec_probe),
            subtitle = stringResource(R.string.audio_surround_reset_iec_probe_sub),
            value = null,
            enabled = enabled && !settings.useSystemPassthrough,
            onClick = { onUpdate { resetIecPassthroughProbe() } }
        )

        SettingsToggleRow(
            title = stringResource(R.string.audio_force_optical_passthrough),
            subtitle = stringResource(R.string.audio_force_optical_passthrough_sub),
            checked = settings.forceOpticalPassthrough && settings.decoderPriority != 0,
            onToggle = { onUpdate { setForceOpticalPassthrough(!settings.forceOpticalPassthrough) } },
            enabled = enabled && settings.decoderPriority != 0
        )
    }
}

@Composable
internal fun audioLanguageLabel(code: String): String = when (code) {
    AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
    AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
    AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_lang_original)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
}

@Composable
private fun secondaryAudioLanguageLabel(code: String?): String = when {
    code == null -> stringResource(R.string.sub_not_set)
    code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) -> stringResource(R.string.audio_lang_original)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
}

@Composable
internal fun decoderPriorityLabel(priority: Int): String = when (priority) {
    0 -> stringResource(R.string.audio_decoder_device_only)
    2 -> stringResource(R.string.audio_decoder_prefer_app)
    else -> stringResource(R.string.audio_decoder_prefer_device)
}

@Composable
private fun surroundFormatModeLabel(mode: SurroundFormatMode): String = when (mode) {
    SurroundFormatMode.AUTO -> stringResource(R.string.audio_surround_mode_auto)
    SurroundFormatMode.MANUAL -> stringResource(R.string.audio_surround_mode_manual)
}

@Composable
private fun surroundChannelTargetLabel(target: SurroundChannelTarget): String = when (target) {
    SurroundChannelTarget.AUTO -> stringResource(R.string.audio_surround_channel_auto)
    SurroundChannelTarget.CH_2_0 -> stringResource(R.string.audio_surround_channel_2_0)
    SurroundChannelTarget.CH_5_1 -> stringResource(R.string.audio_surround_channel_5_1)
    SurroundChannelTarget.CH_7_1 -> stringResource(R.string.audio_surround_channel_7_1)
}

@Composable
internal fun AudioSettingsDialogs(
    dialog: PlaybackDialog?,
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        PlaybackDialog.AUDIO_LANGUAGE -> AudioLanguageSelectionDialog(
            selectedLanguage = settings.preferredAudioLanguage,
            onLanguageSelected = { language ->
                onUpdate { setPreferredAudioLanguage(language) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.SECONDARY_AUDIO_LANGUAGE -> LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = settings.secondaryPreferredAudioLanguage,
            showNoneOption = true,
            extraOptions = listOf(
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = { language ->
                onUpdate { setSecondaryPreferredAudioLanguage(language) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.AUDIO_OUTPUT_CHANNELS -> AudioOutputChannelsDialog(
            selectedChannels = settings.audioOutputChannels,
            onChannelsSelected = { channels ->
                onUpdate { setAudioOutputChannels(channels) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.DECODER_PRIORITY -> DecoderPriorityDialog(
            selectedPriority = settings.decoderPriority,
            onPrioritySelected = { priority ->
                onUpdate { setDecoderPriority(priority) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.SURROUND_FORMAT_MODE -> SurroundFormatModeDialog(
            selectedMode = settings.surroundFormatMode,
            onModeSelected = { mode ->
                onUpdate { setSurroundFormatMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.SURROUND_CHANNEL_TARGET -> SurroundChannelTargetDialog(
            selectedTarget = settings.surroundChannelTarget,
            onTargetSelected = { target ->
                onUpdate { setSurroundChannelTarget(target) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}

@Composable
private fun AudioOutputChannelsDialog(
    selectedChannels: AudioOutputChannels,
    onChannelsSelected: (AudioOutputChannels) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_number_of_channels),
        subtitle = stringResource(R.string.audio_number_of_channels_desc),
        options = AudioOutputChannels.entries.map { SettingsPickerOption(it, it.displayLabel) },
        selectedValue = selectedChannels,
        onOptionSelected = onChannelsSelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 420.dp
    )
}

@Composable
private fun AudioLanguageSelectionDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val specialOptions = listOf(
        AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_default),
        AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
        AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
    )
    val originalHint = stringResource(R.string.audio_lang_original_hint)
    val allOptions = specialOptions.map { (code, name) ->
        SettingsPickerOption(
            value = code,
            title = name,
            description = if (code == AudioLanguageOption.ORIGINAL) originalHint else null
        )
    } + AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }.map {
        SettingsPickerOption(
            value = it.code,
            title = it.displayName,
            trailing = it.code.uppercase()
        )
    }

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_preferred_lang),
        options = allOptions,
        selectedValue = selectedLanguage,
        onOptionSelected = onLanguageSelected,
        onDismiss = onDismiss,
        width = 400.dp,
        maxHeight = 320.dp
    )
}

@Composable
internal fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(0, stringResource(R.string.audio_decoder_device_only), stringResource(R.string.audio_decoder_device_only_desc)),
        SettingsPickerOption(1, stringResource(R.string.audio_decoder_prefer_device), stringResource(R.string.audio_decoder_prefer_device_desc)),
        SettingsPickerOption(2, stringResource(R.string.audio_decoder_prefer_app), stringResource(R.string.audio_decoder_prefer_app_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = stringResource(R.string.audio_decoder_controls),
        options = options,
        selectedValue = selectedPriority,
        onOptionSelected = onPrioritySelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun SurroundFormatModeDialog(
    selectedMode: SurroundFormatMode,
    onModeSelected: (SurroundFormatMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            SurroundFormatMode.AUTO,
            stringResource(R.string.audio_surround_mode_auto),
            stringResource(R.string.audio_surround_mode_auto_desc)
        ),
        SettingsPickerOption(
            SurroundFormatMode.MANUAL,
            stringResource(R.string.audio_surround_mode_manual),
            stringResource(R.string.audio_surround_mode_manual_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_surround_format_mode),
        subtitle = stringResource(R.string.audio_surround_format_dialog_subtitle),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun SurroundChannelTargetDialog(
    selectedTarget: SurroundChannelTarget,
    onTargetSelected: (SurroundChannelTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            SurroundChannelTarget.AUTO,
            stringResource(R.string.audio_surround_channel_auto),
            stringResource(R.string.audio_surround_channel_auto_desc)
        ),
        SettingsPickerOption(
            SurroundChannelTarget.CH_2_0,
            stringResource(R.string.audio_surround_channel_2_0),
            stringResource(R.string.audio_surround_channel_2_0_desc)
        ),
        SettingsPickerOption(
            SurroundChannelTarget.CH_5_1,
            stringResource(R.string.audio_surround_channel_5_1),
            stringResource(R.string.audio_surround_channel_5_1_desc)
        ),
        SettingsPickerOption(
            SurroundChannelTarget.CH_7_1,
            stringResource(R.string.audio_surround_channel_7_1),
            stringResource(R.string.audio_surround_channel_7_1_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_surround_channel_target),
        subtitle = stringResource(R.string.audio_surround_channel_target_dialog_subtitle),
        options = options,
        selectedValue = selectedTarget,
        onOptionSelected = onTargetSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 420.dp
    )
}
