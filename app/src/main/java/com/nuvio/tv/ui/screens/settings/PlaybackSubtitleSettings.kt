@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SubtitleOrganizationMode
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
    onShowSubtitleOrganizationDialog: () -> Unit,
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
        } else {
            AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.subtitleStyle.preferredLanguage
            }?.name ?: "English"
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
            AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.name
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

    item(key = "subtitle_organization") {
        NavigationSettingsItem(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.sub_organization),
            subtitle = subtitleOrganizationModeLabel(playerSettings.subtitleOrganizationMode),
            onClick = onShowSubtitleOrganizationDialog,
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

    item(key = "subtitle_libass_disabled") {
        ToggleSettingsItem(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.sub_libass),
            subtitle = stringResource(R.string.sub_libass_sub),
            isChecked = false,
            onCheckedChange = {},
            onFocused = onItemFocused,
            enabled = false
        )
    }

    if (false) { // Libass temporarily disabled for maintenance
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
                subtitle = "HDR support with canvas rendering. May block UI thread.",
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
    showSubtitleOrganizationDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    playerSettings: PlayerSettings,
    onSetPreferredLanguage: (String?) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetSubtitleOrganizationMode: (SubtitleOrganizationMode) -> Unit,
    onSetTextColor: (Color) -> Unit,
    onSetBackgroundColor: (Color) -> Unit,
    onSetOutlineColor: (Color) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleOrganizationDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit
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

    if (showSubtitleOrganizationDialog) {
        SubtitleOrganizationModeDialog(
            selectedMode = playerSettings.subtitleOrganizationMode,
            onModeSelected = {
                onSetSubtitleOrganizationMode(it)
                onDismissSubtitleOrganizationDialog()
            },
            onDismiss = onDismissSubtitleOrganizationDialog
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
private fun subtitleOrganizationModeLabel(mode: SubtitleOrganizationMode): String {
    return when (mode) {
        SubtitleOrganizationMode.NONE -> stringResource(R.string.sub_org_none)
        SubtitleOrganizationMode.BY_LANGUAGE -> stringResource(R.string.sub_org_by_lang)
        SubtitleOrganizationMode.BY_ADDON -> stringResource(R.string.sub_org_by_addon)
    }
}

@Composable
private fun SubtitleOrganizationModeDialog(
    selectedMode: SubtitleOrganizationMode,
    onModeSelected: (SubtitleOrganizationMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Triple(SubtitleOrganizationMode.NONE, stringResource(R.string.sub_org_none), stringResource(R.string.sub_org_none_desc)),
        Triple(SubtitleOrganizationMode.BY_LANGUAGE, stringResource(R.string.sub_org_by_lang), stringResource(R.string.sub_org_by_lang_desc)),
        Triple(SubtitleOrganizationMode.BY_ADDON, stringResource(R.string.sub_org_by_addon), stringResource(R.string.sub_org_by_addon_desc))
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
                    text = stringResource(R.string.sub_organization),
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
                        val isSelected = mode == selectedMode

                        RenderTypeSettingsItem(
                            title = title,
                            subtitle = description,
                            isSelected = isSelected,
                            onClick = { onModeSelected(mode) },
                            onFocused = {}
                        )
                    }
                }
            }
        }
    }
}
