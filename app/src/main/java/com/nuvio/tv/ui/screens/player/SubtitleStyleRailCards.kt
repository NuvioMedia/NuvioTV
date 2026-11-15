package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

@Composable
internal fun SubtitleFontStyleCard(
    subtitleStyle: SubtitleStyleSettings,
    focusRequesters: Map<String, FocusRequester>,
    onMoveLeft: () -> Unit,
    onFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val fonts = rememberSubtitleFonts()
    val fontIds = subtitleFontIds(fonts)
    val index = fontIds.indexOf(subtitleStyle.fontId).coerceAtLeast(0)
    OverlaySectionCard(title = stringResource(R.string.sub_font), modifier = modifier) {
        StepperRow(
            value = subtitleFontLabel(subtitleStyle.fontId, fonts),
            valueWidth = 160.dp,
            valueFontFamily = rememberSubtitleFontFamily(subtitleStyle.fontId),
            onDecrease = { onEvent(PlayerEvent.OnSetSubtitleFont(fontIds[(index - 1 + fontIds.size) % fontIds.size])) },
            onIncrease = { onEvent(PlayerEvent.OnSetSubtitleFont(fontIds[(index + 1) % fontIds.size])) },
            onMoveLeft = onMoveLeft,
            decrementFocusRequester = focusRequesters[StyleFocusKey.FontDecrease],
            incrementFocusRequester = focusRequesters[StyleFocusKey.FontIncrease],
            decrementFocusKey = StyleFocusKey.FontDecrease,
            incrementFocusKey = StyleFocusKey.FontIncrease,
            onFocusChanged = onFocused
        )
    }
}

@Composable
internal fun SubtitleBackgroundStyleCard(
    subtitleStyle: SubtitleStyleSettings,
    focusRequesters: Map<String, FocusRequester>,
    onMoveLeft: () -> Unit,
    onFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val background = Color(subtitleStyle.backgroundColor)
    val opacityPercent = (background.alpha * 100f).roundToInt()
    OverlaySectionCard(title = stringResource(R.string.sub_background), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            ColorChipRow(
                colors = SubtitleBackgroundColorPresets,
                selectedColor = selectedSubtitleBackgroundPreset(subtitleStyle.backgroundColor),
                onMoveLeft = onMoveLeft,
                focusRequesters = focusRequesters,
                focusKeyPrefix = StyleFocusKey.BackgroundColorPrefix,
                onFocused = onFocused,
                onColorSelected = { color ->
                    onEvent(PlayerEvent.OnSetSubtitleBackgroundColor(subtitleBackgroundColorFor(Color(color), subtitleStyle.backgroundColor)))
                }
            )
            RailStepperLabel(stringResource(R.string.sub_opacity))
            StepperRow(
                value = "$opacityPercent%",
                onDecrease = {
                    val alpha = (opacityPercent - 10).coerceAtLeast(0) / 100f
                    onEvent(PlayerEvent.OnSetSubtitleBackgroundColor(background.copy(alpha = alpha).toArgb()))
                },
                onIncrease = {
                    val alpha = (opacityPercent + 10).coerceAtMost(100) / 100f
                    onEvent(PlayerEvent.OnSetSubtitleBackgroundColor(background.copy(alpha = alpha).toArgb()))
                },
                onMoveLeft = onMoveLeft,
                decrementFocusRequester = focusRequesters[StyleFocusKey.BackgroundOpacityDecrease],
                incrementFocusRequester = focusRequesters[StyleFocusKey.BackgroundOpacityIncrease],
                decrementFocusKey = StyleFocusKey.BackgroundOpacityDecrease,
                incrementFocusKey = StyleFocusKey.BackgroundOpacityIncrease,
                onFocusChanged = onFocused
            )
            Column(
                modifier = Modifier.alpha(if (opacityPercent > 0) 1f else 0.35f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                RailStepperLabel(stringResource(R.string.sub_bg_roundness))
                StepperRow(
                    value = subtitleStyle.backgroundRadius.toString(),
                    onDecrease = { onEvent(PlayerEvent.OnSetSubtitleBackgroundRadius(subtitleStyle.backgroundRadius - 2)) },
                    onIncrease = { onEvent(PlayerEvent.OnSetSubtitleBackgroundRadius(subtitleStyle.backgroundRadius + 2)) },
                    onMoveLeft = onMoveLeft,
                    decrementFocusRequester = focusRequesters[StyleFocusKey.RoundnessDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.RoundnessIncrease],
                    decrementFocusKey = StyleFocusKey.RoundnessDecrease,
                    incrementFocusKey = StyleFocusKey.RoundnessIncrease,
                    onFocusChanged = onFocused
                )
            }
        }
    }
}

@Composable
internal fun SubtitleShadowStyleCard(
    subtitleStyle: SubtitleStyleSettings,
    focusRequesters: Map<String, FocusRequester>,
    onMoveLeft: () -> Unit,
    onFocused: (String) -> Unit,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val enableShadow = { if (!subtitleStyle.shadowEnabled) onEvent(PlayerEvent.OnSetSubtitleShadowEnabled(true)) }
    OverlaySectionCard(title = stringResource(R.string.sub_shadow), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            ToggleChip(
                label = stringResource(if (subtitleStyle.shadowEnabled) R.string.subtitle_style_on else R.string.subtitle_style_off),
                isEnabled = subtitleStyle.shadowEnabled,
                onMoveLeft = onMoveLeft,
                focusRequester = focusRequesters[StyleFocusKey.ShadowToggle],
                focusKey = StyleFocusKey.ShadowToggle,
                onFocused = onFocused,
                onClick = { onEvent(PlayerEvent.OnSetSubtitleShadowEnabled(!subtitleStyle.shadowEnabled)) }
            )
            Column(
                modifier = Modifier.alpha(if (subtitleStyle.shadowEnabled) 1f else 0.35f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                RailStepperLabel(stringResource(R.string.sub_opacity))
                StepperRow(
                    value = "${subtitleStyle.shadowOpacity}%",
                    onDecrease = {
                        enableShadow()
                        onEvent(PlayerEvent.OnSetSubtitleShadowOpacity(subtitleStyle.shadowOpacity - 10))
                    },
                    onIncrease = {
                        enableShadow()
                        onEvent(PlayerEvent.OnSetSubtitleShadowOpacity(subtitleStyle.shadowOpacity + 10))
                    },
                    onMoveLeft = onMoveLeft,
                    decrementFocusRequester = focusRequesters[StyleFocusKey.ShadowOpacityDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.ShadowOpacityIncrease],
                    decrementFocusKey = StyleFocusKey.ShadowOpacityDecrease,
                    incrementFocusKey = StyleFocusKey.ShadowOpacityIncrease,
                    onFocusChanged = onFocused
                )
                RailStepperLabel(stringResource(R.string.sub_size))
                StepperRow(
                    value = subtitleStyle.shadowSize.toString(),
                    onDecrease = {
                        enableShadow()
                        onEvent(PlayerEvent.OnSetSubtitleShadowSize(subtitleStyle.shadowSize - 1))
                    },
                    onIncrease = {
                        enableShadow()
                        onEvent(PlayerEvent.OnSetSubtitleShadowSize(subtitleStyle.shadowSize + 1))
                    },
                    onMoveLeft = onMoveLeft,
                    decrementFocusRequester = focusRequesters[StyleFocusKey.ShadowSizeDecrease],
                    incrementFocusRequester = focusRequesters[StyleFocusKey.ShadowSizeIncrease],
                    decrementFocusKey = StyleFocusKey.ShadowSizeDecrease,
                    incrementFocusKey = StyleFocusKey.ShadowSizeIncrease,
                    onFocusChanged = onFocused
                )
            }
        }
    }
}

@Composable
private fun RailStepperLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.7f)
    )
}
