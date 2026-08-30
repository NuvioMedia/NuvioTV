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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Check
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
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.ui.components.NuvioDialog

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
    onShowTextColorDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetUseForcedSubtitles: (Boolean) -> Unit,
    onSetSubtitleShowOnlyPreferredLanguages: (Boolean) -> Unit,
    onSetSubtitleStripSdh: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (LibassRenderType) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true,
    languageSelectionEnabled: Boolean = enabled
) {


    item(key = "subtitle_preferred_language") {
        val languageName = when {
            playerSettings.subtitleStyle.preferredLanguage == "none" -> stringResource(R.string.action_none)
            playerSettings.subtitleStyle.isPreferredLanguageSystemDefault -> stringResource(R.string.appearance_language_system)
            else -> AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.subtitleStyle.preferredLanguage
            }?.displayName ?: stringResource(R.string.appearance_language_system)
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

    item(key = "subtitle_strip_sdh") {
        ToggleSettingsItem(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.sub_strip_sdh),
            subtitle = stringResource(R.string.sub_strip_sdh_desc),
            isChecked = playerSettings.subtitleStyle.stripSdh,
            onCheckedChange = onSetSubtitleStripSdh,
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
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    playerSettings: PlayerSettings,
    onSetPreferredLanguage: (String?) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetTextColor: (Color) -> Unit,
    onSetBackgroundColor: (Color) -> Unit,
    onSetOutlineColor: (Color) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit
) {
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_preferred_lang),
            selectedLanguage = when {
                playerSettings.subtitleStyle.preferredLanguage == "none" -> null
                playerSettings.subtitleStyle.isPreferredLanguageSystemDefault -> SubtitleLanguageOption.DEVICE
                else -> playerSettings.subtitleStyle.preferredLanguage
            },
            showNoneOption = true,
            extraOptions = listOf(SubtitleLanguageOption.DEVICE to stringResource(R.string.appearance_language_system)),
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
