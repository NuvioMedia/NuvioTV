@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.theme.getFontFamily
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.appearance_title),
        subtitle = stringResource(R.string.appearance_subtitle)
    ) {
        ThemeSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appIconState by viewModel.appIconState.collectAsStateWithLifecycle()
    var showFontDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAppIconDialog by remember { mutableStateOf(false) }
    var showCustomThemeDialog by remember { mutableStateOf(false) }
    var restoreCustomThemeFocus by remember { mutableStateOf(false) }
    var appIconConfirmation by remember { mutableStateOf<AppIconOption?>(null) }
    var pendingLanguageRestart by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val strLanguageSystem = stringResource(R.string.appearance_language_system)
    val supportedLocales = remember(strLanguageSystem) {
        val tags = listOf(
            "en", "ru", "ar", "bg", "bs", "da", "de", "el", "es", "es-419", "hu", "fr", "in", "it",
            "no", "pl", "pt-PT", "pt-BR", "tr", "uk", "cs", "sk", "sl", "sq", "sr-Latn", "sv", "ta", "ro", "ja",
            "nl", "vi", "hi", "lt", "he", "zh-CN", "zh-TW"
        )
        listOf(null to strLanguageSystem) + tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            tag to locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
        }.sortedBy { it.second }
    }
    var selectedTag by remember {
        mutableStateOf(
            context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
                .getString("locale_tag", null)?.takeIf { it.isNotEmpty() }
        )
    }
    val currentLocaleName = supportedLocales.firstOrNull { it.first == selectedTag }?.second ?: stringResource(R.string.appearance_language_system)
    val strRestartHint = stringResource(R.string.appearance_language_restart_hint)

    LaunchedEffect(pendingLanguageRestart, showLanguageDialog) {
        if (pendingLanguageRestart && !showLanguageDialog) {
            // Let the dialog window detach before recreating the Activity to avoid focus/window ANRs.
            delay(150)
            context.findActivity()?.recreate()
                ?: Toast.makeText(context, strRestartHint, Toast.LENGTH_LONG).show()
            pendingLanguageRestart = false
        }
    }

    val styleFocusRequesters = remember { SettingsUiStyle.entries.associateWith { FocusRequester() } }
    val firstThemeFocusRequester = remember { FocusRequester() }
    val customThemeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showCustomThemeDialog, uiState.availableThemes) {
        if (AppTheme.CUSTOM !in uiState.availableThemes) {
            val wasEditing = showCustomThemeDialog
            showCustomThemeDialog = false
            restoreCustomThemeFocus = false
            if (wasEditing) firstThemeFocusRequester.requestFocusAfterFrames()
        } else if (!showCustomThemeDialog && restoreCustomThemeFocus) {
            restoreCustomThemeFocus = false
            customThemeFocusRequester.requestFocusAfterFrames()
        }
    }
    val appliedSettingsUiStyle = NuvioTheme.settingsUiStyle
    LaunchedEffect(Unit) {
        if (viewModel.consumeStyleFocusRestore()) {
            styleFocusRequesters[appliedSettingsUiStyle]?.requestFocusAfterFrames()
        }
    }

    val themeScrollState = rememberScrollState()
    val themeRowState = rememberLazyListState()
    var initialTheme by remember { mutableStateOf<AppTheme?>(null) }
    LaunchedEffect(uiState.themesLoaded) {
        if (!uiState.themesLoaded || initialTheme != null) return@LaunchedEffect
        initialTheme = uiState.selectedTheme
        val selectedIndex = uiState.availableThemes.indexOf(initialTheme)
        if (selectedIndex < 0) return@LaunchedEffect
        themeRowState.scrollToItem(selectedIndex)
        initialFocusRequester?.requestFocusAfterFrames()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(themeScrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsDetailHeader(
                title = stringResource(R.string.appearance_title),
                subtitle = stringResource(R.string.appearance_subtitle)
            )

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.appearance_color_theme),
                subtitle = stringResource(R.string.appearance_color_theme_subtitle)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyRow(
                        state = themeRowState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .settingsOptionRow(firstThemeFocusRequester),
                        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.availableThemes,
                            key = { _, theme -> theme.name }
                        ) { themeIndex, theme ->
                            ThemeSwatchChip(
                                theme = theme,
                                customColors = uiState.customThemeColors,
                                isSelected = theme == uiState.selectedTheme,
                                onClick = {
                                    if (theme == AppTheme.CUSTOM) {
                                        restoreCustomThemeFocus = true
                                        showCustomThemeDialog = true
                                    } else {
                                        viewModel.onEvent(ThemeSettingsEvent.SelectTheme(theme))
                                    }
                                },
                                modifier = if (
                                    uiState.themesLoaded &&
                                    theme == initialTheme &&
                                    initialFocusRequester != null
                                ) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                }.then(
                                    if (themeIndex == 0) {
                                        Modifier.focusRequester(firstThemeFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ).then(
                                    if (theme == AppTheme.CUSTOM) Modifier.focusRequester(customThemeFocusRequester)
                                    else Modifier
                                )
                            )
                        }
                    }
                    SettingsHorizontalScrollIndicators(state = themeRowState)
                }
                SettingsToggleRow(
                    title = stringResource(R.string.appearance_amoled_mode),
                    subtitle = stringResource(R.string.appearance_amoled_mode_subtitle),
                    checked = uiState.amoledMode,
                    onToggle = {
                        viewModel.onEvent(ThemeSettingsEvent.ToggleAmoledMode(!uiState.amoledMode))
                    }
                )
                if (uiState.amoledMode) {
                    SettingsToggleRow(
                        title = stringResource(R.string.appearance_amoled_surfaces_mode),
                        subtitle = stringResource(R.string.appearance_amoled_surfaces_mode_subtitle),
                        checked = uiState.amoledSurfacesMode,
                        onToggle = {
                            viewModel.onEvent(
                                ThemeSettingsEvent.ToggleAmoledSurfacesMode(!uiState.amoledSurfacesMode)
                            )
                        }
                    )
                }
            }

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.appearance_settings_style),
                subtitle = stringResource(R.string.appearance_settings_style_subtitle)
            ) {
                val firstAvailableStyle = uiState.availableSettingsUiStyles.firstOrNull()
                    ?: SettingsUiStyle.entries.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xs)
                        .settingsOptionRow(styleFocusRequesters.getValue(firstAvailableStyle)),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    uiState.availableSettingsUiStyles.forEach { style ->
                        SettingsStyleOptionCard(
                            style = style,
                            isSelected = style == uiState.settingsUiStyle,
                            onClick = { viewModel.onEvent(ThemeSettingsEvent.SelectSettingsUiStyle(style)) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusRequester(styleFocusRequesters.getValue(style))
                        )
                    }
                }
            }

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.appearance_launcher_artwork),
                subtitle = stringResource(R.string.appearance_launcher_artwork_subtitle)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.appearance_app_icon_and_banner),
                    subtitle = stringResource(R.string.appearance_app_icon_and_banner_subtitle),
                    value = appIconState.selected.localizedName(),
                    enabled = appIconState.pending == null,
                    onClick = {
                        viewModel.onEvent(ThemeSettingsEvent.DismissAppIconFailure)
                        showAppIconDialog = true
                    }
                )
            }

            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.appearance_font_and_language),
                subtitle = stringResource(R.string.appearance_font_and_language_subtitle)
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.appearance_font),
                    subtitle = stringResource(R.string.appearance_font_subtitle),
                    value = uiState.selectedFont.displayName,
                    onClick = { showFontDialog = true }
                )
                SettingsActionRow(
                    title = stringResource(R.string.appearance_language),
                    subtitle = stringResource(R.string.appearance_language_subtitle),
                    value = currentLocaleName,
                    onClick = { showLanguageDialog = true }
                )
            }
        }
        SettingsVerticalScrollIndicators(state = themeScrollState)
    }

    if (showCustomThemeDialog && AppTheme.CUSTOM in uiState.availableThemes) {
        CustomThemeDialog(
            initialColors = uiState.customThemeColors,
            onSave = { colors ->
                viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(colors))
                showCustomThemeDialog = false
            },
            onDismiss = { showCustomThemeDialog = false }
        )
    }

    if (showFontDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.appearance_font_dialog_title),
            options = uiState.availableFonts.map { font ->
                SettingsPickerOption(font, font.displayName, titleFontFamily = getFontFamily(font))
            },
            selectedValue = uiState.selectedFont,
            onOptionSelected = { font ->
                viewModel.onEvent(ThemeSettingsEvent.SelectFont(font))
                showFontDialog = false
            },
            onDismiss = { showFontDialog = false },
            width = 400.dp,
            maxHeight = 280.dp
        )
    }

    if (showLanguageDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.appearance_language_dialog_title),
            options = supportedLocales.map { (tag, name) ->
                SettingsPickerOption(tag, name)
            },
            selectedValue = selectedTag,
            onOptionSelected = { tag ->
                val previousTag = selectedTag
                val newTag = tag ?: ""
                context.getSharedPreferences("app_locale", android.content.Context.MODE_PRIVATE)
                    .edit().putString("locale_tag", newTag).apply()
                LocaleCache.localeTag = newTag
                selectedTag = tag
                showLanguageDialog = false
                if (previousTag != tag) {
                    pendingLanguageRestart = true
                }
            },
            onDismiss = { showLanguageDialog = false },
            width = 400.dp,
            maxHeight = 280.dp
        )
    }

    if (showAppIconDialog && appIconConfirmation == null) {
        AppIconPickerDialog(
            state = appIconState,
            onSelected = { option ->
                viewModel.onEvent(ThemeSettingsEvent.DismissAppIconFailure)
                if (option != appIconState.selected) {
                    appIconConfirmation = option
                }
            },
            onDismiss = {
                viewModel.onEvent(ThemeSettingsEvent.DismissAppIconFailure)
                showAppIconDialog = false
            }
        )
    }

    appIconConfirmation?.let { option ->
        AppIconChangeConfirmationDialog(
            option = option,
            onConfirm = {
                appIconConfirmation = null
                if (viewModel.selectAppIcon(option)) {
                    context.findActivity()?.finishAffinity()
                    Process.killProcess(Process.myPid())
                }
            },
            onDismiss = { appIconConfirmation = null }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ThemeSwatchChip(
    theme: AppTheme,
    customColors: CustomThemeColors,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val palette = remember(theme, customColors) { ThemeColors.getColorPalette(theme, customColors) }
    val chipShape = RoundedCornerShape(18.dp)

    Card(
        onClick = onClick,
        modifier = modifier
            .width(96.dp)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = chipShape
            )
        ),
        shape = CardDefaults.shape(chipShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.accentBrush()),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            Text(
                text = theme.localizedName(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused || isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SettingsStyleOptionCard(
    style: SettingsUiStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(18.dp)

    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                shape = cardShape
            ) else Border.None,
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = cardShape
            )
        ),
        shape = CardDefaults.shape(cardShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.md)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = style.localizedName(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused || isSelected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioTheme.colors.Secondary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.CenterEnd)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))

            Text(
                text = style.localizedDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun SettingsUiStyle.localizedName(): String = when (this) {
    SettingsUiStyle.CLASSIC -> stringResource(R.string.settings_style_classic)
    SettingsUiStyle.ZEN -> stringResource(R.string.settings_style_zen)
    SettingsUiStyle.HORIZON -> stringResource(R.string.settings_style_horizon)
}

@Composable
private fun SettingsUiStyle.localizedDescription(): String = when (this) {
    SettingsUiStyle.CLASSIC -> stringResource(R.string.settings_style_classic_desc)
    SettingsUiStyle.ZEN -> stringResource(R.string.settings_style_zen_desc)
    SettingsUiStyle.HORIZON -> stringResource(R.string.settings_style_horizon_desc)
}

@Composable
private fun AppTheme.localizedName(): String = when (this) {
    AppTheme.CUSTOM -> stringResource(R.string.theme_color_custom)
    AppTheme.GOLD -> stringResource(R.string.theme_color_gold)
    AppTheme.JADE -> stringResource(R.string.theme_color_jade)
    AppTheme.ROSE_GOLD -> stringResource(R.string.theme_color_rose_gold)
    AppTheme.ARCTIC_BLUE -> stringResource(R.string.theme_color_arctic_blue)
    AppTheme.GRAPHITE -> stringResource(R.string.theme_color_graphite)
    AppTheme.CRIMSON -> stringResource(R.string.theme_color_crimson)
    AppTheme.OCEAN -> stringResource(R.string.theme_color_ocean)
    AppTheme.VIOLET -> stringResource(R.string.theme_color_violet)
    AppTheme.EMERALD -> stringResource(R.string.theme_color_emerald)
    AppTheme.AMBER -> stringResource(R.string.theme_color_amber)
    AppTheme.ROSE -> stringResource(R.string.theme_color_rose)
    AppTheme.WHITE -> stringResource(R.string.theme_color_white)
}
