package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.player.SUBTITLE_BASE_TEXT_SP
import com.nuvio.tv.ui.screens.player.StyledSubtitleText
import com.nuvio.tv.ui.screens.player.SubtitleBackgroundColorPresets
import com.nuvio.tv.ui.screens.player.rememberSubtitleFontFamily
import com.nuvio.tv.ui.screens.player.rememberSubtitleFonts
import com.nuvio.tv.ui.screens.player.selectedSubtitleBackgroundPreset
import com.nuvio.tv.ui.screens.player.subtitleBackgroundColorFor
import com.nuvio.tv.ui.screens.player.subtitleFontIds
import com.nuvio.tv.ui.screens.player.subtitleFontLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

private val PreviewBackdrop = Brush.linearGradient(listOf(Color(0xFF182A35), Color(0xFF53646B), Color(0xFF1C2934)))

@Composable
internal fun SubtitleAppearanceDialog(
    style: SubtitleStyleSettings,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    var showFontPicker by remember { mutableStateOf(false) }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sub_appearance),
        subtitle = stringResource(R.string.sub_appearance_hint),
        width = 900.dp,
        usePlatformDefaultWidth = false,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            SubtitleAppearanceControls(
                style = style,
                onUpdate = onUpdate,
                onOpenFontPicker = { showFontPicker = true },
                firstFocusRequester = firstFocusRequester,
                modifier = Modifier.weight(1f)
            )
            SubtitleAppearancePreview(
                style = style,
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight()
            )
        }
    }

    if (showFontPicker) {
        SubtitleFontPickerDialog(
            selectedFontId = style.fontId,
            onFontSelected = { fontId -> onUpdate { setSubtitleFontId(fontId) } },
            onDismiss = { showFontPicker = false }
        )
    }
}

@Composable
private fun SubtitleAppearanceControls(
    style: SubtitleStyleSettings,
    onUpdate: PlaybackSettingsUpdate,
    onOpenFontPicker: () -> Unit,
    firstFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val fonts = rememberSubtitleFonts()
    val listState = rememberLazyListState()
    val textColor = Color(style.textColor)
    val backgroundColor = Color(style.backgroundColor)
    val textOpacity = (textColor.alpha * 100f).roundToInt()
    val backgroundOpacity = (backgroundColor.alpha * 100f).roundToInt()
    val fontMissing = style.fontId !in subtitleFontIds(fonts)

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
        ) {
            item(key = "font") {
                SettingsActionRow(
                    title = stringResource(R.string.sub_font),
                    subtitle = if (fontMissing) stringResource(R.string.sub_font_missing) else null,
                    value = subtitleFontLabel(style.fontId, fonts),
                    onClick = onOpenFontPicker,
                    modifier = Modifier.focusRequester(firstFocusRequester)
                )
            }
            item(key = "size") {
                SliderSettingsItem(
                    title = stringResource(R.string.sub_size),
                    value = style.size,
                    valueText = "${style.size}%",
                    minValue = 50,
                    maxValue = 200,
                    step = 10,
                    onValueChange = { size -> onUpdate { setSubtitleSize(size) } }
                )
            }
            item(key = "bold") {
                SettingsToggleRow(
                    title = stringResource(R.string.sub_bold),
                    subtitle = stringResource(R.string.sub_bold_sub),
                    checked = style.bold,
                    onToggle = { onUpdate { setSubtitleBold(!style.bold) } }
                )
            }
            item(key = "text_color") {
                SubtitleColorSwatchRow(
                    title = stringResource(R.string.sub_text_color),
                    colors = SubtitleSettingsTextColors,
                    selectedColor = style.textColor or 0xFF000000.toInt(),
                    onColorSelected = { color ->
                        onUpdate { setSubtitleTextColor(color.copy(alpha = textColor.alpha).toArgb()) }
                    }
                )
            }
            item(key = "text_opacity") {
                SliderSettingsItem(
                    title = stringResource(R.string.subtitle_style_text_opacity),
                    value = textOpacity,
                    valueText = "$textOpacity%",
                    minValue = 0,
                    maxValue = 100,
                    step = 5,
                    onValueChange = { opacity ->
                        onUpdate { setSubtitleTextColor(textColor.copy(alpha = opacity / 100f).toArgb()) }
                    }
                )
            }
            item(key = "background_color") {
                SubtitleColorSwatchRow(
                    title = stringResource(R.string.sub_bg_color),
                    colors = SubtitleBackgroundColorPresets,
                    selectedColor = selectedSubtitleBackgroundPreset(style.backgroundColor),
                    onColorSelected = { color ->
                        onUpdate { setSubtitleBackgroundColor(subtitleBackgroundColorFor(color, style.backgroundColor)) }
                    }
                )
            }
            item(key = "background_opacity") {
                SliderSettingsItem(
                    title = stringResource(R.string.sub_bg_opacity),
                    value = backgroundOpacity,
                    valueText = "$backgroundOpacity%",
                    minValue = 0,
                    maxValue = 100,
                    step = 5,
                    onValueChange = { opacity ->
                        onUpdate { setSubtitleBackgroundColor(backgroundColor.copy(alpha = opacity / 100f).toArgb()) }
                    }
                )
            }
            item(key = "background_radius") {
                SliderSettingsItem(
                    title = stringResource(R.string.sub_bg_roundness),
                    value = style.backgroundRadius,
                    valueText = style.backgroundRadius.toString(),
                    minValue = 0,
                    maxValue = SubtitleStyleSettings.BACKGROUND_RADIUS_MAX,
                    step = 2,
                    enabled = backgroundOpacity > 0,
                    onValueChange = { radius -> onUpdate { setSubtitleBackgroundRadius(radius) } }
                )
            }
            item(key = "outline") {
                SettingsToggleRow(
                    title = stringResource(R.string.sub_outline),
                    subtitle = stringResource(R.string.sub_outline_sub),
                    checked = style.outlineEnabled,
                    onToggle = { onUpdate { setSubtitleOutlineEnabled(!style.outlineEnabled) } }
                )
            }
            if (style.outlineEnabled) {
                item(key = "outline_color") {
                    SubtitleColorSwatchRow(
                        title = stringResource(R.string.sub_outline_color),
                        colors = SubtitleSettingsOutlineColors,
                        selectedColor = style.outlineColor,
                        onColorSelected = { color -> onUpdate { setSubtitleOutlineColor(color.toArgb()) } }
                    )
                }
            }
            item(key = "shadow") {
                SettingsToggleRow(
                    title = stringResource(R.string.sub_shadow),
                    subtitle = stringResource(R.string.sub_shadow_desc),
                    checked = style.shadowEnabled,
                    onToggle = { onUpdate { setSubtitleShadowEnabled(!style.shadowEnabled) } }
                )
            }
            if (style.shadowEnabled) {
                item(key = "shadow_opacity") {
                    SliderSettingsItem(
                        title = stringResource(R.string.sub_shadow_opacity),
                        value = style.shadowOpacity,
                        valueText = "${style.shadowOpacity}%",
                        minValue = 0,
                        maxValue = 100,
                        step = 5,
                        onValueChange = { opacity -> onUpdate { setSubtitleShadowOpacity(opacity) } }
                    )
                }
                item(key = "shadow_size") {
                    SliderSettingsItem(
                        title = stringResource(R.string.sub_shadow_size),
                        value = style.shadowSize,
                        valueText = style.shadowSize.toString(),
                        minValue = SubtitleStyleSettings.SHADOW_SIZE_MIN,
                        maxValue = SubtitleStyleSettings.SHADOW_SIZE_MAX,
                        step = 1,
                        onValueChange = { size -> onUpdate { setSubtitleShadowSize(size) } }
                    )
                }
            }
            item(key = "vertical_offset") {
                SliderSettingsItem(
                    title = stringResource(R.string.sub_vertical_offset),
                    value = style.verticalOffset,
                    valueText = "${style.verticalOffset}%",
                    minValue = -20,
                    maxValue = 50,
                    step = 1,
                    onValueChange = { offset -> onUpdate { setSubtitleVerticalOffset(offset) } }
                )
            }
            item(key = "reset") {
                SettingsResetButton(
                    text = stringResource(R.string.sub_reset_appearance),
                    onClick = { onUpdate { resetSubtitleAppearance() } },
                    modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
                )
            }
        }
        SettingsVerticalScrollIndicators(state = listState)
    }
}

@Composable
private fun SubtitleColorSwatchRow(
    title: String,
    colors: List<Color>,
    selectedColor: Int,
    onColorSelected: (Color) -> Unit
) {
    val firstSwatchFocusRequester = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = SettingsRowContentInset)
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(firstSwatchFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(horizontal = SettingsRowContentInset, vertical = NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(colors, key = { _, color -> color.toArgb() }) { index, color ->
                ColorOption(
                    color = color,
                    isSelected = color.toArgb() == selectedColor,
                    onClick = { onColorSelected(color) },
                    modifier = if (index == 0) Modifier.focusRequester(firstSwatchFocusRequester) else Modifier
                )
            }
        }
    }
}

@Composable
private fun SubtitleAppearancePreview(
    style: SubtitleStyleSettings,
    modifier: Modifier = Modifier
) {
    val textSizePx = with(LocalDensity.current) { (SUBTITLE_BASE_TEXT_SP * style.size / 100f).sp.toPx() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(PreviewBackdrop)
    ) {
        Text(
            text = stringResource(R.string.sub_preview),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(NuvioTheme.spacing.lg)
        )
        StyledSubtitleText(
            text = AnnotatedString(stringResource(R.string.sub_preview_text)),
            style = style,
            textSizePx = textSizePx,
            fontFamily = rememberSubtitleFontFamily(style.fontId),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xl)
        )
    }
}
