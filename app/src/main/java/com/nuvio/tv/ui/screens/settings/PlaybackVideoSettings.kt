@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun PlaybackVideoSection(
    settings: PlayerSettings,
    transparentLetterbox: Boolean,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    val enabled = settings.playerPreference != PlayerPreference.EXTERNAL
    val displayCapabilities = rememberDisplayCapabilities()
    val frameRateFocusRequester = remember { FocusRequester() }
    val resolutionSwitchingSupported = !displayCapabilities.apiSupported ||
        displayCapabilities.supportsResolutionSwitching
    val showAfrWarning = settings.frameRateMatchingMode != FrameRateMatchingMode.OFF ||
        (settings.resolutionMatchingEnabled && !resolutionSwitchingSupported)

    SettingsSectionLabel(text = stringResource(R.string.playback_auto_frame_rate))
    SettingsActionRow(
        title = stringResource(R.string.playback_afr_mode),
        subtitle = null,
        value = frameRateMatchingLabel(settings.frameRateMatchingMode),
        titleTrailingIcon = if (showAfrWarning) Icons.Default.Warning else null,
        titleTrailingIconTint = NuvioTheme.colors.Warning,
        enabled = enabled,
        modifier = Modifier.focusRequester(frameRateFocusRequester),
        onClick = { onOpenDialog(PlaybackDialog.FRAME_RATE_MATCHING) }
    )
    SettingsToggleRow(
        title = stringResource(R.string.playback_resolution_matching),
        subtitle = stringResource(
            if (resolutionSwitchingSupported) R.string.playback_resolution_matching_sub
            else R.string.playback_resolution_matching_unsupported_sub
        ),
        checked = settings.resolutionMatchingEnabled,
        onToggle = { onUpdate { setResolutionMatchingEnabled(!settings.resolutionMatchingEnabled) } },
        titleTrailingIcon = if (settings.resolutionMatchingEnabled && !resolutionSwitchingSupported) {
            Icons.Default.Warning
        } else {
            null
        },
        titleTrailingIconTint = NuvioTheme.colors.Warning,
        enabled = enabled
    )
    AfrCapabilityWarningCard(
        snapshot = displayCapabilities,
        afrModeOn = settings.frameRateMatchingMode != FrameRateMatchingMode.OFF,
        resolutionMatchingOn = settings.resolutionMatchingEnabled,
        onDisableAll = { onUpdate { disableAfrAndResolution() } },
        onDisableAfrOnly = { onUpdate { setFrameRateMatchingMode(FrameRateMatchingMode.OFF) } },
        onDisableResolutionOnly = { onUpdate { setResolutionMatchingEnabled(false) } },
        focusAfterDisable = frameRateFocusRequester
    )

    if (settings.usesExoPlayerEngine) {
        val dv81Conversion = settings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
        SettingsSectionLabel(text = stringResource(R.string.playback_dv_hdr_label))
        SettingsActionRow(
            title = stringResource(R.string.dv7_handling_title),
            subtitle = null,
            value = dv7HandlingModeLabel(settings.dv7HandlingMode),
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.DV7_HANDLING_MODE) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_dv7_preserve_mapping_title),
            subtitle = stringResource(R.string.audio_dv7_preserve_mapping_sub),
            checked = settings.dv7ToDv81PreserveMappingEnabled && dv81Conversion,
            onToggle = { onUpdate { setDv7ToDv81PreserveMappingEnabled(!settings.dv7ToDv81PreserveMappingEnabled) } },
            enabled = enabled && dv81Conversion
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_dv5_to_dv81_title),
            subtitle = stringResource(R.string.audio_dv5_to_dv81_sub),
            checked = settings.dv5ToDv81Enabled && dv81Conversion,
            onToggle = { onUpdate { setDv5ToDv81Enabled(!settings.dv5ToDv81Enabled) } },
            enabled = enabled && dv81Conversion
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_strip_hdr10plus_title),
            subtitle = stringResource(R.string.audio_strip_hdr10plus_sub),
            checked = settings.stripHdr10PlusSei,
            onToggle = { onUpdate { setStripHdr10PlusSei(!settings.stripHdr10PlusSei) } },
            enabled = enabled
        )
        SettingsToggleRow(
            title = stringResource(R.string.playback_true_black_letterbox),
            subtitle = stringResource(R.string.playback_true_black_letterbox_sub),
            checked = transparentLetterbox,
            onToggle = { onUpdate { setTransparentLetterbox(!transparentLetterbox) } },
            enabled = enabled
        )
    }

    if (settings.usesMpvEngine) {
        SettingsSectionLabel(text = stringResource(R.string.playback_mpv_decoding_label))
        SettingsActionRow(
            title = stringResource(R.string.audio_mpv_hwdec_title),
            subtitle = null,
            value = mpvHardwareDecodeModeLabel(settings.mpvHardwareDecodeMode),
            enabled = enabled,
            onClick = { onOpenDialog(PlaybackDialog.MPV_HARDWARE_DECODE_MODE) }
        )
        SettingsToggleRow(
            title = stringResource(R.string.audio_mpv_hi10p_gnext_sw_title),
            subtitle = stringResource(R.string.audio_mpv_hi10p_gnext_sw_subtitle),
            checked = settings.mpvHi10pGnextSoftwareFallbackEnabled,
            onToggle = {
                onUpdate { setMpvHi10pGnextSoftwareFallbackEnabled(!settings.mpvHi10pGnextSoftwareFallbackEnabled) }
            },
            enabled = enabled
        )
    }
}

@Composable
private fun rememberDisplayCapabilities(): DisplayCapabilities.Snapshot {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var snapshot by remember { mutableStateOf(DisplayCapabilities.Snapshot.Unknown) }
    LaunchedEffect(activity) {
        if (activity != null) {
            snapshot = DisplayCapabilities.detect(activity)
            DisplayCapabilities.logSummary(snapshot)
        } else {
            android.util.Log.w("DisplayCapabilities", "Settings: could not resolve host Activity from LocalContext")
        }
    }
    return snapshot
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun frameRateMatchingLabel(mode: FrameRateMatchingMode): String = when (mode) {
    FrameRateMatchingMode.OFF -> stringResource(R.string.playback_afr_off)
    FrameRateMatchingMode.START -> stringResource(R.string.playback_afr_on_start)
    FrameRateMatchingMode.START_STOP -> stringResource(R.string.playback_afr_on_start_stop)
}

@Composable
private fun dv7HandlingModeLabel(mode: Dv7HandlingMode): String = when (mode) {
    Dv7HandlingMode.AUTO -> stringResource(R.string.dv7_mode_auto)
    Dv7HandlingMode.HDR10_BASE_LAYER -> stringResource(R.string.dv7_mode_hdr10_base_layer)
    Dv7HandlingMode.DV81_LIBDOVI -> stringResource(R.string.dv7_mode_dv81_libdovi)
    Dv7HandlingMode.STRIP_DV -> stringResource(R.string.dv7_mode_strip_dv)
    Dv7HandlingMode.OFF -> stringResource(R.string.dv7_mode_off)
}

@Composable
private fun mpvHardwareDecodeModeLabel(mode: MpvHardwareDecodeMode): String = when (mode) {
    MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> stringResource(R.string.audio_mpv_hwdec_legacy_direct_copy)
    MpvHardwareDecodeMode.AUTO_SAFE -> stringResource(R.string.audio_mpv_hwdec_auto_safe)
    MpvHardwareDecodeMode.HARDWARE_COPY -> stringResource(R.string.audio_mpv_hwdec_hardware_copy)
    MpvHardwareDecodeMode.HARDWARE_DIRECT -> stringResource(R.string.audio_mpv_hwdec_hardware_direct)
    MpvHardwareDecodeMode.DISABLED -> stringResource(R.string.audio_mpv_hwdec_disabled)
}

@Composable
private fun AfrCapabilityWarningCard(
    snapshot: DisplayCapabilities.Snapshot,
    afrModeOn: Boolean,
    resolutionMatchingOn: Boolean,
    onDisableAll: () -> Unit,
    onDisableAfrOnly: () -> Unit,
    onDisableResolutionOnly: () -> Unit,
    focusAfterDisable: FocusRequester
) {
    if (!snapshot.apiSupported) return

    val afrProblem = afrModeOn && !snapshot.supportsFrameRateSwitching
    val resProblem = resolutionMatchingOn && !snapshot.supportsResolutionSwitching
    if (!afrProblem && !resProblem) return

    val bodyRes = when {
        afrProblem && resProblem -> R.string.playback_afr_capability_both_problem_body
        afrProblem -> R.string.playback_afr_capability_only_afr_unsupported_body
        else -> R.string.playback_afr_capability_only_res_unsupported_body
    }
    val buttonRes = when {
        afrProblem && resProblem -> R.string.playback_afr_capability_disable_both_button
        afrProblem -> R.string.playback_afr_capability_disable_button
        else -> R.string.playback_afr_capability_disable_resolution_button
    }
    val onDisable: () -> Unit = when {
        afrProblem && resProblem -> onDisableAll
        afrProblem -> onDisableAfrOnly
        else -> onDisableResolutionOnly
    }
    val warningTone = NuvioTheme.colors.Warning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
            .background(NuvioTheme.colors.BackgroundCard)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = warningTone.copy(alpha = 0.55f),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = warningTone,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.playback_afr_capability_unsupported_title),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        SettingsActionRow(
            title = stringResource(buttonRes),
            subtitle = null,
            onClick = {
                runCatching { focusAfterDisable.requestFocus() }
                onDisable()
            }
        )
    }
}

@Composable
internal fun VideoSettingsDialogs(
    dialog: PlaybackDialog?,
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    when (dialog) {
        PlaybackDialog.FRAME_RATE_MATCHING -> FrameRateMatchingDialog(
            selectedMode = settings.frameRateMatchingMode,
            onModeSelected = { mode ->
                onUpdate { setFrameRateMatchingMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.DV7_HANDLING_MODE -> Dv7HandlingModeDialog(
            selectedMode = settings.dv7HandlingMode,
            onModeSelected = { mode ->
                onUpdate { setDv7HandlingMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        PlaybackDialog.MPV_HARDWARE_DECODE_MODE -> MpvHardwareDecodeModeDialog(
            selectedMode = settings.mpvHardwareDecodeMode,
            onModeSelected = { mode ->
                onUpdate { setMpvHardwareDecodeMode(mode) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}

@Composable
private fun FrameRateMatchingDialog(
    selectedMode: FrameRateMatchingMode,
    onModeSelected: (FrameRateMatchingMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            FrameRateMatchingMode.OFF,
            stringResource(R.string.playback_afr_off),
            stringResource(R.string.playback_afr_off_sub)
        ),
        SettingsPickerOption(
            FrameRateMatchingMode.START,
            stringResource(R.string.playback_afr_on_start),
            stringResource(R.string.playback_afr_on_start_sub)
        ),
        SettingsPickerOption(
            FrameRateMatchingMode.START_STOP,
            stringResource(R.string.playback_afr_on_start_stop),
            stringResource(R.string.playback_afr_on_start_stop_sub)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.playback_afr_mode),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 320.dp
    )
}

@Composable
private fun Dv7HandlingModeDialog(
    selectedMode: Dv7HandlingMode,
    onModeSelected: (Dv7HandlingMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(
            Dv7HandlingMode.AUTO,
            stringResource(R.string.dv7_mode_auto),
            stringResource(R.string.dv7_mode_auto_desc)
        ),
        SettingsPickerOption(
            Dv7HandlingMode.HDR10_BASE_LAYER,
            stringResource(R.string.dv7_mode_hdr10_base_layer),
            stringResource(R.string.dv7_mode_hdr10_base_layer_desc)
        ),
        SettingsPickerOption(
            Dv7HandlingMode.DV81_LIBDOVI,
            stringResource(R.string.dv7_mode_dv81_libdovi),
            stringResource(R.string.dv7_mode_dv81_libdovi_desc)
        ),
        SettingsPickerOption(
            Dv7HandlingMode.STRIP_DV,
            stringResource(R.string.dv7_mode_strip_dv),
            stringResource(R.string.dv7_mode_strip_dv_desc)
        ),
        SettingsPickerOption(
            Dv7HandlingMode.OFF,
            stringResource(R.string.dv7_mode_off),
            stringResource(R.string.dv7_mode_off_desc)
        )
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.dv7_handling_title),
        subtitle = stringResource(R.string.dv7_handling_dialog_subtitle),
        options = options,
        selectedValue = selectedMode,
        onOptionSelected = onModeSelected,
        onDismiss = onDismiss,
        width = 460.dp,
        maxHeight = 360.dp
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
