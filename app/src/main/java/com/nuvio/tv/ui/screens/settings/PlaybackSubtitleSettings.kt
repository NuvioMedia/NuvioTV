@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignBottom
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.ui.components.NuvioDialog
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
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (LibassRenderType) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item(key = "subtitle_header") {
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sub_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = androidx.compose.ui.Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "subtitle_preferred_language") {
        val languageName = if (playerSettings.subtitleStyle.preferredLanguage == "none") {
            stringResource(R.string.action_none)
        } else if (playerSettings.subtitleStyle.preferredLanguage == SUBTITLE_LANGUAGE_FORCED) {
            stringResource(R.string.sub_forced_lang)
        } else {
            AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.subtitleStyle.preferredLanguage
            }?.displayName ?: "English"
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_preferred_lang),
            subtitle = languageName,
            onClick = onShowLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_secondary_language") {
        val secondaryLanguageName = playerSettings.subtitleStyle.secondaryPreferredLanguage?.let { code ->
            if (code == SUBTITLE_LANGUAGE_FORCED) stringResource(R.string.sub_forced_lang)
            else AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryLanguageName,
            onClick = onShowSecondaryLanguageDialog,
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
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sub_advanced_section),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = androidx.compose.ui.Modifier.padding(vertical = 8.dp)
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
                color = NuvioColors.TextSecondary,
                modifier = androidx.compose.ui.Modifier.padding(vertical = 8.dp)
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

internal fun LazyListScope.subtitleAiSettingsItems(
    playerSettings: PlayerSettings,
    onSetSubtitleAiEnabled: (Boolean) -> Unit,
    onSetSubtitleAiAutoSelect: (Boolean) -> Unit,
    onSetSubtitleRemoveHearingImpaired: (Boolean) -> Unit,
    onShowAiKeyDialog: () -> Unit,
    onStartAiKeyServer: () -> Unit,
    onSetSubtitleAiModel: (com.nuvio.tv.data.local.SubtitleAiModel) -> Unit = {},
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true,
    firstItemModifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    item(key = "subtitle_ai_enabled") {
        ToggleSettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = stringResource(R.string.sub_ai_enabled),
            subtitle = stringResource(R.string.sub_ai_enabled_sub),
            isChecked = playerSettings.subtitleAiEnabled,
            onCheckedChange = onSetSubtitleAiEnabled,
            onFocused = onItemFocused,
            enabled = enabled,
            modifier = firstItemModifier
        )
    }

    if (playerSettings.subtitleAiEnabled) {
        item(key = "subtitle_ai_model") {
            val modelLabel = when (playerSettings.subtitleAiModel) {
                com.nuvio.tv.data.local.SubtitleAiModel.GROQ_LLAMA_70B -> "Groq – Llama 3.3 70B"
                com.nuvio.tv.data.local.SubtitleAiModel.GEMINI_FLASH_25 -> "Google – Gemini 2.5 Flash"
            }
            var showModelDialog by remember { mutableStateOf(false) }
            SettingsActionRow(
                title = stringResource(R.string.sub_ai_model),
                subtitle = stringResource(R.string.sub_ai_model_sub),
                value = modelLabel,
                onClick = { showModelDialog = true },
                enabled = enabled
            )
            if (showModelDialog) {
                AiModelDialog(
                    currentModel = playerSettings.subtitleAiModel,
                    onModelSelected = { onSetSubtitleAiModel(it); showModelDialog = false },
                    onDismiss = { showModelDialog = false }
                )
            }
        }

        item(key = "subtitle_ai_auto_select") {
            ToggleSettingsItem(
                icon = Icons.Default.AutoAwesome,
                title = stringResource(R.string.sub_ai_auto_select),
                subtitle = stringResource(R.string.sub_ai_auto_select_sub),
                isChecked = playerSettings.subtitleAiAutoSelect,
                onCheckedChange = onSetSubtitleAiAutoSelect,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        item(key = "subtitle_remove_hearing_impaired") {
            ToggleSettingsItem(
                icon = Icons.Default.ClosedCaption,
                title = stringResource(R.string.sub_remove_hearing_impaired),
                subtitle = stringResource(R.string.sub_remove_hearing_impaired_sub),
                isChecked = playerSettings.subtitleRemoveHearingImpaired,
                onCheckedChange = onSetSubtitleRemoveHearingImpaired,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }

        item(key = "subtitle_ai_api_key") {
            SettingsActionRow(
                title = stringResource(R.string.sub_ai_api_key_title),
                subtitle = stringResource(R.string.sub_ai_api_key_sub),
                value = maskAiApiKey(playerSettings.subtitleAiApiKey),
                onClick = onShowAiKeyDialog,
                enabled = enabled
            )
        }

        item(key = "subtitle_ai_api_key_qr") {
            SettingsActionRow(
                title = stringResource(R.string.sub_ai_api_key_qr),
                subtitle = stringResource(R.string.sub_ai_api_key_qr_sub2),
                value = "",
                onClick = onStartAiKeyServer,
                enabled = enabled
            )
        }

        item(key = "subtitle_ai_disclaimer") {
            Text(
                text = when (playerSettings.subtitleAiModel) {
                    com.nuvio.tv.data.local.SubtitleAiModel.GROQ_LLAMA_70B ->
                        stringResource(R.string.sub_ai_groq_disclaimer)
                    com.nuvio.tv.data.local.SubtitleAiModel.GEMINI_FLASH_25 ->
                        stringResource(R.string.sub_ai_free_tier_disclaimer)
                },
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

private fun maskAiApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return "Not set"
    val provider = when {
        trimmed.startsWith("gsk_") -> "Groq · "
        trimmed.startsWith("AIzaSy") -> "Gemini · "
        else -> ""
    }
    val masked = if (trimmed.length <= 4) "••••" else "••••${trimmed.takeLast(4)}"
    return "$provider$masked"
}

@Composable
internal fun AiModelDialog(
    currentModel: com.nuvio.tv.data.local.SubtitleAiModel,
    onModelSelected: (com.nuvio.tv.data.local.SubtitleAiModel) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Triple(com.nuvio.tv.data.local.SubtitleAiModel.GROQ_LLAMA_70B, "Groq – Llama 3.3 70B", stringResource(R.string.sub_ai_model_free)),
        Triple(com.nuvio.tv.data.local.SubtitleAiModel.GEMINI_FLASH_25, "Google – Gemini 2.5 Flash", stringResource(R.string.sub_ai_model_gemini_note))
    )
    BackHandler { onDismiss() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .width(500.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.sub_ai_model),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sub_ai_model_dialog_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = options,
                        key = { it.first.name }
                    ) { (model, label, note) ->
                        RenderTypeSettingsItem(
                            title = label,
                            subtitle = note,
                            isSelected = model == currentModel,
                            onClick = { onModelSelected(model) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AiApiKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    model: com.nuvio.tv.data.local.SubtitleAiModel = com.nuvio.tv.data.local.SubtitleAiModel.GROQ_LLAMA_70B
) {
    var value by remember(currentKey) { mutableStateOf(currentKey) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val placeholder = when (model) {
        com.nuvio.tv.data.local.SubtitleAiModel.GROQ_LLAMA_70B -> "gsk_..."
        com.nuvio.tv.data.local.SubtitleAiModel.GEMINI_FLASH_25 -> "AIzaSy..."
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "API Key",
        subtitle = stringResource(R.string.sub_ai_api_key_sub),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioColors.Primary else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value.trim()) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) { Text("Save") }
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
    playerSettings: PlayerSettings,
    onSetPreferredLanguage: (String?) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetAddonSubtitleStartupMode: (AddonSubtitleStartupMode) -> Unit,
    onSetTextColor: (Color) -> Unit,
    onSetBackgroundColor: (Color) -> Unit,
    onSetOutlineColor: (Color) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleStartupModeDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit
) {
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_preferred_lang),
            selectedLanguage = if (playerSettings.subtitleStyle.preferredLanguage == "none") null else playerSettings.subtitleStyle.preferredLanguage,
            showNoneOption = true,
            extraOptions = listOf(SUBTITLE_LANGUAGE_FORCED to stringResource(R.string.sub_forced_lang)),
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
            extraOptions = listOf(SUBTITLE_LANGUAGE_FORCED to stringResource(R.string.sub_forced_lang)),
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
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .width(460.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.sub_startup_mode_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
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
