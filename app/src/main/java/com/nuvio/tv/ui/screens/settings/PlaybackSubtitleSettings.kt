@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.view.KeyEvent
import com.nuvio.tv.R
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.SubtitleAiProvider
import com.nuvio.tv.data.local.subtitleAiModelForProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors

private val subtitleColors = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color.Yellow,
    Color.Cyan,
    Color.Green,
    Color.Magenta,
    Color(0xFFFF6B6B),
    Color(0xFFFFA500),
    Color(0xFF90EE90)
)

private val subtitleBackgroundColors = listOf(
    Color.Transparent,
    Color.Black,
    Color(0x80000000),
    Color(0xFF1A1A1A),
    Color(0xFF2D2D2D)
)

private val subtitleOutlineColors = listOf(
    Color.Black,
    Color(0xFF1A1A1A),
    Color(0xFF333333),
    Color.White
)

@Composable
private fun subtitleAiProviderLabel(providerValue: String): String {
    return when (SubtitleAiProvider.fromValue(providerValue)) {
        SubtitleAiProvider.GROQ -> "Groq"
        SubtitleAiProvider.GEMINI -> "Gemini"
    }
}

internal fun LazyListScope.subtitleSettingsItems(
    playerSettings: PlayerSettings,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowSubtitleStartupModeDialog: () -> Unit,
    onShowTextColorDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetUseForcedSubtitles: (Boolean) -> Unit,
    onSetSubtitleShowOnlyPreferredLanguages: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetAutoSyncExternalSubtitles: (Boolean) -> Unit,
    onSetSubtitleAiAutoSelect: (Boolean) -> Unit,
    onShowSubtitleAiProviderDialog: () -> Unit,
    onShowSubtitleAiGroqKeyDialog: () -> Unit,
    onShowSubtitleAiGeminiKeyDialog: () -> Unit,
    onShowSubtitleAiConfigDialog: () -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (LibassRenderType) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true,
    languageSelectionEnabled: Boolean = enabled
) {
    item(key = "subtitle_header") {
        Spacer(modifier = androidx.compose.ui.Modifier.height(NuvioTheme.spacing.lg))
        Text(
            text = stringResource(R.string.sub_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = androidx.compose.ui.Modifier.padding(vertical = NuvioTheme.spacing.sm)
        )
    }

    item(key = "subtitle_preferred_language") {
        val languageName = if (playerSettings.subtitleStyle.preferredLanguage == "none") {
            stringResource(R.string.action_none)
        } else {
            AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.subtitleStyle.preferredLanguage
            }?.displayName ?: stringResource(R.string.language_english)
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_preferred_lang),
            subtitle = languageName,
            onClick = onShowLanguageDialog,
            onFocused = onItemFocused,
            enabled = languageSelectionEnabled
        )
    }

    item(key = "subtitle_secondary_language") {
        val secondaryLanguageName = playerSettings.subtitleStyle.secondaryPreferredLanguage?.let { code ->
            AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryLanguageName,
            onClick = onShowSecondaryLanguageDialog,
            onFocused = onItemFocused,
            enabled = languageSelectionEnabled
        )
    }

    item(key = "subtitle_use_forced_subtitles") {
        ToggleSettingsItem(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.sub_use_forced_subtitles),
            subtitle = stringResource(R.string.sub_use_forced_subtitles_desc),
            isChecked = playerSettings.subtitleStyle.useForcedSubtitles,
            onCheckedChange = onSetUseForcedSubtitles,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_show_only_preferred_languages") {
        ToggleSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_show_only_preferred_languages),
            subtitle = stringResource(R.string.sub_show_only_preferred_languages_desc),
            isChecked = playerSettings.subtitleStyle.showOnlyPreferredLanguages,
            onCheckedChange = onSetSubtitleShowOnlyPreferredLanguages,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_startup_mode") {
        NavigationSettingsItem(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.sub_startup_mode_title),
            subtitle = subtitleStartupModeLabel(playerSettings.addonSubtitleStartupMode),
            onClick = onShowSubtitleStartupModeDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_size") {
        SliderSettingsItem(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.sub_size),
            value = playerSettings.subtitleStyle.size,
            valueText = "${playerSettings.subtitleStyle.size}%",
            minValue = 50,
            maxValue = 200,
            step = 10,
            onValueChange = onSetSubtitleSize,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_vertical_offset") {
        SliderSettingsItem(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.sub_vertical_offset),
            value = playerSettings.subtitleStyle.verticalOffset,
            valueText = "${playerSettings.subtitleStyle.verticalOffset}%",
            minValue = -20,
            maxValue = 50,
            step = 1,
            onValueChange = onSetSubtitleVerticalOffset,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_bold") {
        ToggleSettingsItem(
            icon = Icons.Default.FormatBold,
            title = stringResource(R.string.sub_bold),
            subtitle = stringResource(R.string.sub_bold_sub),
            isChecked = playerSettings.subtitleStyle.bold,
            onCheckedChange = onSetSubtitleBold,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_text_color") {
        ColorSettingsItem(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.sub_text_color),
            currentColor = Color(playerSettings.subtitleStyle.textColor),
            onClick = onShowTextColorDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_background_color") {
        ColorSettingsItem(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.sub_bg_color),
            currentColor = Color(playerSettings.subtitleStyle.backgroundColor),
            showTransparent = playerSettings.subtitleStyle.backgroundColor == Color.Transparent.toArgb(),
            onClick = onShowBackgroundColorDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_outline_toggle") {
        ToggleSettingsItem(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.sub_outline),
            subtitle = stringResource(R.string.sub_outline_sub),
            isChecked = playerSettings.subtitleStyle.outlineEnabled,
            onCheckedChange = onSetSubtitleOutlineEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_ai_auto_sync") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = "Auto-Sync Addon Subtitles",
            subtitle = "Use AI to match addon subtitles to the internal track and apply the delay automatically.",
            isChecked = playerSettings.autoSyncExternalSubtitles,
            onCheckedChange = onSetAutoSyncExternalSubtitles,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_ai_auto_select") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = "Auto-Select AI Sync",
            subtitle = "Run subtitle matching as soon as the addon track is selected.",
            isChecked = playerSettings.subtitleAiAutoSelect,
            onCheckedChange = onSetSubtitleAiAutoSelect,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.autoSyncExternalSubtitles
        )
    }

    item(key = "subtitle_ai_provider") {
        NavigationSettingsItem(
            icon = Icons.Default.Storage,
            title = "AI Provider",
            subtitle = subtitleAiProviderLabel(playerSettings.subtitleAiProvider),
            onClick = onShowSubtitleAiProviderDialog,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.autoSyncExternalSubtitles
        )
    }

    item(key = "subtitle_ai_model") {
        NavigationSettingsItem(
            icon = Icons.Default.Subtitles,
            title = "AI Model",
            subtitle = subtitleAiModelForProvider(SubtitleAiProvider.fromValue(playerSettings.subtitleAiProvider)),
            onClick = {},
            onFocused = onItemFocused,
            enabled = false
        )
    }

    item(key = "subtitle_ai_groq_key") {
        NavigationSettingsItem(
            icon = Icons.Default.Key,
            title = "Groq API Key",
            subtitle = if (playerSettings.subtitleAiGroqKey.isBlank()) "Not set" else "Configured",
            onClick = onShowSubtitleAiGroqKeyDialog,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.autoSyncExternalSubtitles
        )
    }

    item(key = "subtitle_ai_gemini_key") {
        NavigationSettingsItem(
            icon = Icons.Default.Key,
            title = "Gemini API Key",
            subtitle = if (playerSettings.subtitleAiGeminiKey.isBlank()) "Not set" else "Configured",
            onClick = onShowSubtitleAiGeminiKeyDialog,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.autoSyncExternalSubtitles
        )
    }

    item(key = "subtitle_ai_keys_qr") {
        NavigationSettingsItem(
            icon = Icons.Default.Key,
            title = "Configure API Keys (Phone)",
            subtitle = "Open the phone web page to save Groq or Gemini credentials.",
            onClick = onShowSubtitleAiConfigDialog,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.autoSyncExternalSubtitles
        )
    }

    if (playerSettings.subtitleStyle.outlineEnabled) {
        item(key = "subtitle_outline_color") {
            ColorSettingsItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.sub_outline_color),
                currentColor = Color(playerSettings.subtitleStyle.outlineColor),
                onClick = onShowOutlineColorDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }

    item(key = "subtitle_advanced_header") {
        Spacer(modifier = androidx.compose.ui.Modifier.height(NuvioTheme.spacing.lg))
        Text(
            text = stringResource(R.string.sub_advanced_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = androidx.compose.ui.Modifier.padding(vertical = NuvioTheme.spacing.sm)
        )
    }

    item(key = "subtitle_libass") {
        ToggleSettingsItem(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.sub_libass),
            subtitle = stringResource(R.string.sub_libass_sub),
            isChecked = playerSettings.useLibass,
            onCheckedChange = onSetUseLibass,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    if (playerSettings.useLibass) {
        item(key = "subtitle_libass_render_header") {
            Text(
                text = stringResource(R.string.sub_libass_mode),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = androidx.compose.ui.Modifier.padding(vertical = NuvioTheme.spacing.sm)
            )
        }

        item(key = "subtitle_libass_overlay_gl") {
            RenderTypeSettingsItem(
                title = stringResource(R.string.sub_mode_overlay_gl),
                subtitle = stringResource(R.string.sub_mode_overlay_gl_sub),
                isSelected = playerSettings.libassRenderType == LibassRenderType.OVERLAY_OPEN_GL,
                onClick = { onSetLibassRenderType(LibassRenderType.OVERLAY_OPEN_GL) },
                onFocused = onItemFocused
            )
        }

        item(key = "subtitle_libass_overlay_canvas") {
            RenderTypeSettingsItem(
                title = stringResource(R.string.sub_mode_overlay_canvas),
                subtitle = stringResource(R.string.sub_mode_overlay_canvas_sub),
                isSelected = playerSettings.libassRenderType == LibassRenderType.OVERLAY_CANVAS,
                onClick = { onSetLibassRenderType(LibassRenderType.OVERLAY_CANVAS) },
                onFocused = onItemFocused
            )
        }

        item(key = "subtitle_libass_effects_gl") {
            RenderTypeSettingsItem(
                title = stringResource(R.string.sub_mode_effects_gl),
                subtitle = stringResource(R.string.sub_mode_effects_gl_sub),
                isSelected = playerSettings.libassRenderType == LibassRenderType.EFFECTS_OPEN_GL,
                onClick = { onSetLibassRenderType(LibassRenderType.EFFECTS_OPEN_GL) },
                onFocused = onItemFocused
            )
        }

        item(key = "subtitle_libass_effects_canvas") {
            RenderTypeSettingsItem(
                title = stringResource(R.string.sub_mode_effects_canvas),
                subtitle = stringResource(R.string.sub_mode_effects_canvas_sub),
                isSelected = playerSettings.libassRenderType == LibassRenderType.EFFECTS_CANVAS,
                onClick = { onSetLibassRenderType(LibassRenderType.EFFECTS_CANVAS) },
                onFocused = onItemFocused
            )
        }

        item(key = "subtitle_libass_cues") {
            RenderTypeSettingsItem(
                title = stringResource(R.string.sub_mode_standard),
                subtitle = stringResource(R.string.sub_mode_standard_sub),
                isSelected = playerSettings.libassRenderType == LibassRenderType.CUES,
                onClick = { onSetLibassRenderType(LibassRenderType.CUES) },
                onFocused = onItemFocused
            )
        }
    }
}

@Composable
internal fun SubtitleSettingsDialogs(
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showSubtitleStartupModeDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    showSubtitleAiProviderDialog: Boolean,
    showSubtitleAiGroqKeyDialog: Boolean,
    showSubtitleAiGeminiKeyDialog: Boolean,
    playerSettings: PlayerSettings,
    onSetPreferredLanguage: (String?) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetAddonSubtitleStartupMode: (AddonSubtitleStartupMode) -> Unit,
    onSetTextColor: (Color) -> Unit,
    onSetBackgroundColor: (Color) -> Unit,
    onSetOutlineColor: (Color) -> Unit,
    onSetSubtitleAiProvider: (SubtitleAiProvider) -> Unit,
    onSetSubtitleAiGroqKey: (String) -> Unit,
    onSetSubtitleAiGeminiKey: (String) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleStartupModeDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit,
    onDismissSubtitleAiProviderDialog: () -> Unit,
    onDismissSubtitleAiGroqKeyDialog: () -> Unit,
    onDismissSubtitleAiGeminiKeyDialog: () -> Unit
) {
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_preferred_lang),
            selectedLanguage = if (playerSettings.subtitleStyle.preferredLanguage == "none") null else playerSettings.subtitleStyle.preferredLanguage,
            showNoneOption = true,
            onLanguageSelected = {
                onSetPreferredLanguage(it)
                onDismissLanguageDialog()
            },
            onDismiss = onDismissLanguageDialog
        )
    }

    if (showSecondaryLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
            showNoneOption = true,
            onLanguageSelected = {
                onSetSecondaryLanguage(it)
                onDismissSecondaryLanguageDialog()
            },
            onDismiss = onDismissSecondaryLanguageDialog
        )
    }

    if (showSubtitleStartupModeDialog) {
        AddonSubtitleStartupModeDialog(
            selectedMode = playerSettings.addonSubtitleStartupMode,
            onModeSelected = {
                onSetAddonSubtitleStartupMode(it)
                onDismissSubtitleStartupModeDialog()
            },
            onDismiss = onDismissSubtitleStartupModeDialog
        )
    }

    if (showTextColorDialog) {
        ColorSelectionDialog(
            title = stringResource(R.string.sub_text_color),
            colors = subtitleColors,
            selectedColor = Color(playerSettings.subtitleStyle.textColor),
            onColorSelected = {
                onSetTextColor(it)
                onDismissTextColorDialog()
            },
            onDismiss = onDismissTextColorDialog
        )
    }

    if (showBackgroundColorDialog) {
        ColorSelectionDialog(
            title = stringResource(R.string.sub_bg_color),
            colors = subtitleBackgroundColors,
            selectedColor = Color(playerSettings.subtitleStyle.backgroundColor),
            showTransparentOption = true,
            onColorSelected = {
                onSetBackgroundColor(it)
                onDismissBackgroundColorDialog()
            },
            onDismiss = onDismissBackgroundColorDialog
        )
    }

    if (showOutlineColorDialog) {
        ColorSelectionDialog(
            title = stringResource(R.string.sub_outline_color),
            colors = subtitleOutlineColors,
            selectedColor = Color(playerSettings.subtitleStyle.outlineColor),
            onColorSelected = {
                onSetOutlineColor(it)
                onDismissOutlineColorDialog()
            },
            onDismiss = onDismissOutlineColorDialog
        )
    }

    if (showSubtitleAiProviderDialog) {
        val providerOptions = listOf(
            SettingsPickerOption(SubtitleAiProvider.GROQ, "Groq", "Use the Groq API"),
            SettingsPickerOption(SubtitleAiProvider.GEMINI, "Gemini", "Use the Gemini API")
        )
        SettingsSingleChoiceDialog(
            title = "AI Provider",
            options = providerOptions,
            selectedValue = SubtitleAiProvider.fromValue(playerSettings.subtitleAiProvider),
            onOptionSelected = {
                onSetSubtitleAiProvider(it)
                onDismissSubtitleAiProviderDialog()
            },
            onDismiss = onDismissSubtitleAiProviderDialog
        )
    }

    if (showSubtitleAiGroqKeyDialog) {
        SubtitleAiTextInputDialog(
            title = "Groq API Key",
            subtitle = "Paste your Groq API key.",
            initialValue = playerSettings.subtitleAiGroqKey,
            onValueSelected = {
                onSetSubtitleAiGroqKey(it)
                onDismissSubtitleAiGroqKeyDialog()
            },
            onDismiss = onDismissSubtitleAiGroqKeyDialog
        )
    }

    if (showSubtitleAiGeminiKeyDialog) {
        SubtitleAiTextInputDialog(
            title = "Gemini API Key",
            subtitle = "Paste your Gemini API key.",
            initialValue = playerSettings.subtitleAiGeminiKey,
            onValueSelected = {
                onSetSubtitleAiGeminiKey(it)
                onDismissSubtitleAiGeminiKeyDialog()
            },
            onDismiss = onDismissSubtitleAiGeminiKeyDialog
        )
    }
}

@Composable
private fun SubtitleAiTextInputDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    onValueSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val inputFocusRequester = remember { FocusRequester() }

    fun submit() {
        focusManager.clearFocus()
        keyboardController?.hide()
        onValueSelected(value.trim())
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NuvioColors.BackgroundElevated)
                .border(1.dp, NuvioColors.Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if ((native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                            native.action == KeyEvent.ACTION_DOWN
                        ) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NuvioColors.TextPrimary),
                cursorBrush = SolidColor(NuvioColors.Primary)
            )
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                onClick = { submit() },
                primary = true
            )
        }
    }
}

@Composable
private fun subtitleStartupModeLabel(mode: AddonSubtitleStartupMode): String {
    return when (mode) {
        AddonSubtitleStartupMode.FAST_STARTUP -> stringResource(R.string.sub_startup_mode_fast)
        AddonSubtitleStartupMode.PREFERRED_ONLY -> stringResource(R.string.sub_startup_mode_preferred)
        AddonSubtitleStartupMode.ALL_SUBTITLES -> stringResource(R.string.sub_startup_mode_all)
    }
}

@Composable
private fun AddonSubtitleStartupModeDialog(
    selectedMode: AddonSubtitleStartupMode,
    onModeSelected: (AddonSubtitleStartupMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Triple(
            AddonSubtitleStartupMode.FAST_STARTUP,
            stringResource(R.string.sub_startup_mode_fast),
            stringResource(R.string.sub_startup_mode_fast_desc)
        ),
        Triple(
            AddonSubtitleStartupMode.PREFERRED_ONLY,
            stringResource(R.string.sub_startup_mode_preferred),
            stringResource(R.string.sub_startup_mode_preferred_desc)
        ),
        Triple(
            AddonSubtitleStartupMode.ALL_SUBTITLES,
            stringResource(R.string.sub_startup_mode_all),
            stringResource(R.string.sub_startup_mode_all_desc)
        )
    )

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundCard)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .width(460.dp)
                    .padding(NuvioTheme.spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.sub_startup_mode_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(NuvioTheme.spacing.lg))

                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    items(
                        items = options,
                        key = { it.first.name }
                    ) { (mode, title, description) ->
                        RenderTypeSettingsItem(
                            title = title,
                            subtitle = description,
                            isSelected = mode == selectedMode,
                            onClick = { onModeSelected(mode) },
                            onFocused = {}
                        )
                    }
                }
            }
        }
    }
}
