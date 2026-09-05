@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SurroundFormatMode
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog

internal fun LazyListScope.trailerAndAudioSettingsItems(
    playerSettings: PlayerSettings,
    onShowAudioLanguageDialog: () -> Unit,
    onShowSecondaryAudioLanguageDialog: () -> Unit,
    onShowAudioOutputChannelsDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowMpvHardwareDecodeModeDialog: () -> Unit,
    onShowDv7HandlingModeDialog: () -> Unit,
    onShowSurroundFormatModeDialog: () -> Unit,
    onSetAllowAc3Passthrough: (Boolean) -> Unit,
    onSetAllowEac3Passthrough: (Boolean) -> Unit,
    onSetAllowTruehdPassthrough: (Boolean) -> Unit,
    onSetAllowDtsPassthrough: (Boolean) -> Unit,
    onSetAllowDtshdPassthrough: (Boolean) -> Unit,
    onSetTranscodeDeniedToAc3: (Boolean) -> Unit,
    onShowSurroundChannelTargetDialog: () -> Unit,
    onSetDownmixEnabled: (Boolean) -> Unit,
    onSetMaintainOriginalAudioOnDownmix: (Boolean) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetRememberAudioDelayPerDevice: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetForceOpticalPassthrough: (Boolean) -> Unit,
    onSetDv5ToDv81Enabled: (Boolean) -> Unit,
    onSetDv7ToDv81PreserveMappingEnabled: (Boolean) -> Unit,
    onSetStripHdr10PlusSei: (Boolean) -> Unit,
    onSetMpvHi10pGnextSoftwareFallbackEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true,
    videoExtraItems: (LazyListScope.() -> Unit)? = null
) {
    val isExoEngine = playerSettings.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
            playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO
    val isMpvEngine = playerSettings.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER ||
            playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO

    // ── Audio Section ──
    item(key = "audio_header") {
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Text(
            text = stringResource(R.string.audio_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
        )
    }

    item(key = "audio_passthrough_info") {
        Text(
            text = stringResource(R.string.audio_passthrough_info),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
        )
    }

    item(key = "audio_preferred_language") {
        val audioLangName = when (playerSettings.preferredAudioLanguage) {
            AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
            AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
            AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_lang_original)
            else -> AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.preferredAudioLanguage
            }?.displayName ?: playerSettings.preferredAudioLanguage
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.audio_preferred_lang),
            subtitle = audioLangName,
            onClick = onShowAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_secondary_preferred_language") {
        val secondaryAudioLangName = playerSettings.secondaryPreferredAudioLanguage?.let { code ->
            when {
                code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) -> stringResource(R.string.audio_lang_original)
                else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
            }
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryAudioLangName,
            onClick = onShowSecondaryAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    if (isExoEngine) {
        item(key = "audio_skip_silence") {
            ToggleSettingsItem(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.audio_skip_silence),
                subtitle = stringResource(R.string.audio_skip_silence_sub),
                isChecked = playerSettings.skipSilence,
                onCheckedChange = onSetSkipSilence,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }

    item(key = "audio_remember_delay_per_device") {
        ToggleSettingsItem(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.audio_remember_delay_per_device),
            subtitle = stringResource(R.string.audio_remember_delay_per_device_sub),
            isChecked = playerSettings.rememberAudioDelayPerDevice,
            onCheckedChange = onSetRememberAudioDelayPerDevice,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    if (isExoEngine) {
        item(key = "audio_advanced_header") {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            Text(
                text = stringResource(R.string.audio_advanced_section),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
            )
        }

        item(key = "audio_advanced_warning") {
            Text(
                text = stringResource(R.string.audio_advanced_warning),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )
        }

        item(key = "audio_decoder_priority") {
            val decoderName = when (playerSettings.decoderPriority) {
                0 -> stringResource(R.string.audio_decoder_device_only)
                1 -> stringResource(R.string.audio_decoder_prefer_device)
                2 -> stringResource(R.string.audio_decoder_prefer_app)
                else -> stringResource(R.string.audio_decoder_prefer_device)
            }

            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_decoder_priority),
                subtitle = decoderName,
                onClick = onShowDecoderPriorityDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        item(key = "audio_enable_downmix") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_enable_downmix_title),
                subtitle = stringResource(R.string.audio_enable_downmix_subtitle),
                // Show off outside Prefer app decoders so a persisted value doesn't
                // read as active (same pattern as optical passthrough / DV8.1-only toggles).
                isChecked = playerSettings.effectiveDownmixEnabled,
                onCheckedChange = onSetDownmixEnabled,
                onFocused = onItemFocused,
                enabled = enabled && playerSettings.isPreferAppDecoder
            )
        }

        if (playerSettings.effectiveDownmixEnabled) {
            item(key = "audio_number_of_channels") {
                NavigationSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_number_of_channels),
                    subtitle = playerSettings.audioOutputChannels.displayLabel,
                    onClick = onShowAudioOutputChannelsDialog,
                    onFocused = onItemFocused,
                    enabled = enabled
                )
            }

            item(key = "audio_downmix_normalization") {
                ToggleSettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.audio_maintain_original_audio_on_downmix_title),
                    subtitle = stringResource(R.string.audio_maintain_original_audio_on_downmix_subtitle),
                    isChecked = playerSettings.maintainOriginalAudioOnDownmix,
                    onCheckedChange = onSetMaintainOriginalAudioOnDownmix,
                    onFocused = onItemFocused,
                    enabled = enabled
                )
            }
        }

        item(key = "audio_tunneled_playback") {
            ToggleSettingsItem(
                icon = Icons.Default.VolumeUp,
                title = stringResource(R.string.audio_tunneled),
                subtitle = stringResource(R.string.audio_tunneled_sub),
                // Show off when prefer-app decoder is active so a persisted value
                // doesn't read as active (same pattern as optical passthrough /
                // DV8.1-only toggles). Downmix also requires prefer-app, so this
                // covers that path too.
                isChecked = playerSettings.effectiveTunnelingEnabled,
                onCheckedChange = onSetTunnelingEnabled,
                onFocused = onItemFocused,
                enabled = enabled && playerSettings.isTunnelingCompatible
            )
        }

        // ── Surround Sound (#3287): mode row + the force-optical row, which moves here
        // because both decide what leaves the box as a bitstream. Gating unchanged.
        item(key = "audio_surround_header") {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            Text(
                text = stringResource(R.string.audio_surround_header),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
            )
        }

        item(key = "audio_surround_format_mode") {
            val surroundModeName = when (playerSettings.surroundFormatMode) {
                SurroundFormatMode.AUTO -> stringResource(R.string.audio_surround_mode_auto)
                SurroundFormatMode.MANUAL -> stringResource(R.string.audio_surround_mode_manual)
            }
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_surround_format_mode),
                subtitle = surroundModeName,
                onClick = onShowSurroundFormatModeDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        // Manual: one switch per deniable format. On = passthrough allowed (delegates to the
        // platform report); off = decode to PCM in the app. Inert under Device-only decoding
        // (no FFmpeg renderer, so nothing is denied) - shown allowed and disabled, mirroring
        // the Force AC-3 row's gating.
        if (playerSettings.surroundFormatMode == SurroundFormatMode.MANUAL) {
            val switchesEnabled = enabled && playerSettings.decoderPriority != 0
            val deviceOnly = playerSettings.decoderPriority == 0
            item(key = "audio_surround_allow_ac3") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_allow_ac3),
                    subtitle = stringResource(R.string.audio_surround_allow_ac3_sub),
                    isChecked = playerSettings.allowAc3Passthrough || deviceOnly,
                    onCheckedChange = onSetAllowAc3Passthrough,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
            item(key = "audio_surround_allow_eac3") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_allow_eac3),
                    subtitle = stringResource(R.string.audio_surround_allow_eac3_sub),
                    isChecked = playerSettings.allowEac3Passthrough || deviceOnly,
                    onCheckedChange = onSetAllowEac3Passthrough,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
            item(key = "audio_surround_allow_truehd") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_allow_truehd),
                    subtitle = stringResource(R.string.audio_surround_allow_truehd_sub),
                    isChecked = playerSettings.allowTruehdPassthrough || deviceOnly,
                    onCheckedChange = onSetAllowTruehdPassthrough,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
            item(key = "audio_surround_allow_dts") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_allow_dts),
                    subtitle = stringResource(R.string.audio_surround_allow_dts_sub),
                    isChecked = playerSettings.allowDtsPassthrough || deviceOnly,
                    onCheckedChange = onSetAllowDtsPassthrough,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
            item(key = "audio_surround_allow_dtshd") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_allow_dtshd),
                    subtitle = stringResource(R.string.audio_surround_allow_dtshd_sub),
                    isChecked = playerSettings.allowDtshdPassthrough || deviceOnly,
                    onCheckedChange = onSetAllowDtshdPassthrough,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
            // How a denied format leaves the box: PCM at the resolved channel target (default,
            // lossless) or re-encoded to AC-3 5.1 where the receiver accepts AC-3 (keeps surround
            // on a 2-channel-PCM chain). Manual only; Auto decides this from the chain's shape.
            item(key = "audio_surround_transcode_denied") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_surround_transcode_denied),
                    subtitle = stringResource(R.string.audio_surround_transcode_denied_sub),
                    isChecked = playerSettings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3 &&
                        !deviceOnly,
                    onCheckedChange = onSetTranscodeDeniedToAc3,
                    onFocused = onItemFocused,
                    enabled = switchesEnabled
                )
            }
        }

        // Channel target for app-decoded audio. Visible in both modes because the resolver
        // honours an explicit target in both (it is the user's answer to what the popcount
        // probe cannot prove - a subwoofer, or a chain whose PCM profile is unreadable).
        // Auto follows the sink's reported width, so for most chains this row does nothing.
        item(key = "audio_surround_channel_target") {
            val targetName = when (playerSettings.surroundChannelTarget) {
                SurroundChannelTarget.AUTO -> stringResource(R.string.audio_surround_channel_auto)
                SurroundChannelTarget.CH_2_0 -> stringResource(R.string.audio_surround_channel_2_0)
                SurroundChannelTarget.CH_5_1 -> stringResource(R.string.audio_surround_channel_5_1)
                SurroundChannelTarget.CH_7_1 -> stringResource(R.string.audio_surround_channel_7_1)
            }
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_surround_channel_target),
                subtitle = targetName,
                onClick = onShowSurroundChannelTargetDialog,
                onFocused = onItemFocused,
                enabled = enabled && playerSettings.decoderPriority != 0
            )
        }

        if (isExoEngine || isMpvEngine) {
            item(key = "audio_force_optical_passthrough") {
                ToggleSettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.audio_force_optical_passthrough),
                    subtitle = stringResource(R.string.audio_force_optical_passthrough_sub),
                    isChecked = playerSettings.forceOpticalPassthrough && playerSettings.decoderPriority != 0,
                    onCheckedChange = onSetForceOpticalPassthrough,
                    onFocused = onItemFocused,
                    enabled = enabled && playerSettings.decoderPriority != 0
                )
            }
        }
    }

    // ── Video & DV Settings ──
    item(key = "video_header") {
        Text(
            text = stringResource(R.string.video_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
        )
    }

    videoExtraItems?.invoke(this)

    if (isExoEngine) {
        item(key = "audio_dv7_handling_mode") {
            val modeName = when (playerSettings.dv7HandlingMode) {
                Dv7HandlingMode.AUTO -> stringResource(R.string.dv7_mode_auto)
                Dv7HandlingMode.HDR10_BASE_LAYER -> stringResource(R.string.dv7_mode_hdr10_base_layer)
                Dv7HandlingMode.DV81_LIBDOVI -> stringResource(R.string.dv7_mode_dv81_libdovi)
                Dv7HandlingMode.STRIP_DV -> stringResource(R.string.dv7_mode_strip_dv)
                Dv7HandlingMode.OFF -> stringResource(R.string.dv7_mode_off)
            }
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.dv7_handling_title),
                subtitle = modeName,
                onClick = onShowDv7HandlingModeDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
        item(key = "audio_dv7_preserve_mapping") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_dv7_preserve_mapping_title),
                subtitle = stringResource(R.string.audio_dv7_preserve_mapping_sub),
                // Show off outside Convert to DV8.1 so a persisted value doesn't read as active.
                isChecked = playerSettings.dv7ToDv81PreserveMappingEnabled &&
                        playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI,
                onCheckedChange = onSetDv7ToDv81PreserveMappingEnabled,
                onFocused = onItemFocused,
                enabled = enabled && playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
            )
        }

        item(key = "audio_dv5_to_dv81") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_dv5_to_dv81_title),
                subtitle = stringResource(R.string.audio_dv5_to_dv81_sub),
                // Show off outside Convert to DV8.1 so a persisted value doesn't read as active.
                isChecked = playerSettings.dv5ToDv81Enabled &&
                        playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI,
                onCheckedChange = onSetDv5ToDv81Enabled,
                onFocused = onItemFocused,
                enabled = enabled && playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
            )
        }

        item(key = "audio_strip_hdr10plus") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_strip_hdr10plus_title),
                subtitle = stringResource(R.string.audio_strip_hdr10plus_sub),
                isChecked = playerSettings.stripHdr10PlusSei,
                onCheckedChange = onSetStripHdr10PlusSei,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }

    if (isMpvEngine) {
        item(key = "audio_mpv_hardware_decode_mode") {
            val hwDecodeModeName = when (playerSettings.mpvHardwareDecodeMode) {
                MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy)
                MpvHardwareDecodeMode.AUTO_SAFE -> stringResource(R.string.audio_mpv_hwdec_auto_safe)
                MpvHardwareDecodeMode.HARDWARE_COPY -> stringResource(R.string.audio_mpv_hwdec_hardware_copy)
                MpvHardwareDecodeMode.HARDWARE_DIRECT -> stringResource(R.string.audio_mpv_hwdec_hardware_direct)
                MpvHardwareDecodeMode.DISABLED -> stringResource(R.string.audio_mpv_hwdec_disabled)
            }

            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_mpv_hwdec_title),
                subtitle = hwDecodeModeName,
                onClick = onShowMpvHardwareDecodeModeDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        item(key = "audio_mpv_hi10p_gnext_software_fallback") {
            ToggleSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.audio_mpv_hi10p_gnext_sw_title),
                subtitle = stringResource(R.string.audio_mpv_hi10p_gnext_sw_subtitle),
                isChecked = playerSettings.mpvHi10pGnextSoftwareFallbackEnabled,
                onCheckedChange = onSetMpvHi10pGnextSoftwareFallbackEnabled,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }
}

@Composable
internal fun AudioSettingsDialogs(
    showAudioLanguageDialog: Boolean,
    showSecondaryAudioLanguageDialog: Boolean,
    showAudioOutputChannelsDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showMpvHardwareDecodeModeDialog: Boolean,
    showDv7HandlingModeDialog: Boolean,
    showSurroundFormatModeDialog: Boolean,
    showSurroundChannelTargetDialog: Boolean,
    selectedLanguage: String,
    selectedSecondaryLanguage: String?,
    selectedAudioOutputChannels: AudioOutputChannels,
    selectedPriority: Int,
    selectedMpvHardwareDecodeMode: MpvHardwareDecodeMode,
    selectedDv7HandlingMode: Dv7HandlingMode,
    selectedSurroundFormatMode: SurroundFormatMode,
    selectedSurroundChannelTarget: SurroundChannelTarget,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetAudioOutputChannels: (AudioOutputChannels) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetMpvHardwareDecodeMode: (MpvHardwareDecodeMode) -> Unit,
    onSetDv7HandlingMode: (Dv7HandlingMode) -> Unit,
    onSetSurroundFormatMode: (SurroundFormatMode) -> Unit,
    onSetSurroundChannelTarget: (SurroundChannelTarget) -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissSecondaryAudioLanguageDialog: () -> Unit,
    onDismissAudioOutputChannelsDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissMpvHardwareDecodeModeDialog: () -> Unit,
    onDismissDv7HandlingModeDialog: () -> Unit,
    onDismissSurroundFormatModeDialog: () -> Unit,
    onDismissSurroundChannelTargetDialog: () -> Unit
) {
    if (showAudioLanguageDialog) {
        AudioLanguageSelectionDialog(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = {
                onSetPreferredAudioLanguage(it)
                onDismissAudioLanguageDialog()
            },
            onDismiss = onDismissAudioLanguageDialog
        )
    }

    if (showSecondaryAudioLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = selectedSecondaryLanguage,
            showNoneOption = true,
            extraOptions = listOf(
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = {
                onSetSecondaryPreferredAudioLanguage(it)
                onDismissSecondaryAudioLanguageDialog()
            },
            onDismiss = onDismissSecondaryAudioLanguageDialog
        )
    }

    if (showAudioOutputChannelsDialog) {
        AudioOutputChannelsDialog(
            selectedChannels = selectedAudioOutputChannels,
            onChannelsSelected = {
                onSetAudioOutputChannels(it)
                onDismissAudioOutputChannelsDialog()
            },
            onDismiss = onDismissAudioOutputChannelsDialog
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = selectedPriority,
            onPrioritySelected = {
                onSetDecoderPriority(it)
                onDismissDecoderPriorityDialog()
            },
            onDismiss = onDismissDecoderPriorityDialog
        )
    }

    if (showMpvHardwareDecodeModeDialog) {
        MpvHardwareDecodeModeDialog(
            selectedMode = selectedMpvHardwareDecodeMode,
            onModeSelected = {
                onSetMpvHardwareDecodeMode(it)
                onDismissMpvHardwareDecodeModeDialog()
            },
            onDismiss = onDismissMpvHardwareDecodeModeDialog
        )
    }

    if (showDv7HandlingModeDialog) {
        Dv7HandlingModeDialog(
            selectedMode = selectedDv7HandlingMode,
            onModeSelected = {
                onSetDv7HandlingMode(it)
                onDismissDv7HandlingModeDialog()
            },
            onDismiss = onDismissDv7HandlingModeDialog
        )
    }

    if (showSurroundFormatModeDialog) {
        SurroundFormatModeDialog(
            selectedMode = selectedSurroundFormatMode,
            onModeSelected = {
                onSetSurroundFormatMode(it)
                onDismissSurroundFormatModeDialog()
            },
            onDismiss = onDismissSurroundFormatModeDialog
        )
    }

    if (showSurroundChannelTargetDialog) {
        SurroundChannelTargetDialog(
            selectedTarget = selectedSurroundChannelTarget,
            onTargetSelected = {
                onSetSurroundChannelTarget(it)
                onDismissSurroundChannelTargetDialog()
            },
            onDismiss = onDismissSurroundChannelTargetDialog
        )
    }
}

@Composable
private fun AudioOutputChannelsDialog(
    selectedChannels: AudioOutputChannels,
    onChannelsSelected: (AudioOutputChannels) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = AudioOutputChannels.entries

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.audio_number_of_channels),
        subtitle = stringResource(R.string.audio_number_of_channels_desc),
        width = 420.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].settingValue }
                ) { index ->
                    val option = options[index]
                    val isSelected = option == selectedChannels

                    Card(
                        onClick = { onChannelsSelected(option) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(NuvioTheme.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = option.displayLabel,
                                color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioTheme.colors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
private fun MpvHardwareDecodeModeDialog(
    selectedMode: MpvHardwareDecodeMode,
    onModeSelected: (MpvHardwareDecodeMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            MpvHardwareDecodeMode.AUTO_SAFE,
            stringResource(R.string.audio_mpv_hwdec_auto_safe),
            stringResource(R.string.audio_mpv_hwdec_auto_safe_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.HARDWARE_COPY,
            stringResource(R.string.audio_mpv_hwdec_hardware_copy),
            stringResource(R.string.audio_mpv_hwdec_hardware_copy_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.HARDWARE_DIRECT,
            stringResource(R.string.audio_mpv_hwdec_hardware_direct),
            stringResource(R.string.audio_mpv_hwdec_hardware_direct_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.DISABLED,
            stringResource(R.string.audio_mpv_hwdec_disabled),
            stringResource(R.string.audio_mpv_hwdec_disabled_desc)
        ),
        SettingsPickerOption(
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY,
            stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy),
            stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_mpv_hwdec_title),
        subtitle = stringResource(R.string.audio_mpv_hwdec_dialog_subtitle),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 360.dp
    )
}
@Composable
private fun Dv7HandlingModeDialog(
    selectedMode: Dv7HandlingMode,
    onModeSelected: (Dv7HandlingMode) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(
            Dv7HandlingMode.AUTO,
            stringResource(R.string.dv7_mode_auto),
            stringResource(R.string.dv7_mode_auto_desc)
        ),
        Triple(
            Dv7HandlingMode.HDR10_BASE_LAYER,
            stringResource(R.string.dv7_mode_hdr10_base_layer),
            stringResource(R.string.dv7_mode_hdr10_base_layer_desc)
        ),
        Triple(
            Dv7HandlingMode.DV81_LIBDOVI,
            stringResource(R.string.dv7_mode_dv81_libdovi),
            stringResource(R.string.dv7_mode_dv81_libdovi_desc)
        ),
        Triple(
            Dv7HandlingMode.STRIP_DV,
            stringResource(R.string.dv7_mode_strip_dv),
            stringResource(R.string.dv7_mode_strip_dv_desc)
        ),
        Triple(
            Dv7HandlingMode.OFF,
            stringResource(R.string.dv7_mode_off),
            stringResource(R.string.dv7_mode_off_desc)
        )
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.dv7_handling_title),
        subtitle = stringResource(R.string.dv7_handling_dialog_subtitle),
        width = 460.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (mode, title, description) = options[index]
                    val isSelected = mode == selectedMode

                    Card(
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(NuvioTheme.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                Text(
                                    text = description,
                                    color = NuvioTheme.colors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NuvioTheme.colors.Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
