@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlin.math.roundToInt

@Composable
internal fun LanguageSelectionDialog(
    title: String,
    selectedLanguage: String?,
    showNoneOption: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val tmdbTitle = stringResource(R.string.tmdb_language_dialog_title)
    val sortedLanguages = remember {
        val baseList = if (title == tmdbTitle) AVAILABLE_TMDB_LANGUAGES else AVAILABLE_SUBTITLE_LANGUAGES
        baseList.sortedBy { it.displayName.lowercase() }
    }
    val originalHint = stringResource(R.string.audio_lang_original_hint)
    val languageOptions: List<SettingsPickerOption<String?>> = buildList {
        if (showNoneOption) {
            add(SettingsPickerOption(null, stringResource(R.string.action_none)))
        }
        extraOptions.forEach { (code, name) ->
            add(SettingsPickerOption(
                code, name,
                description = if (code == AudioLanguageOption.ORIGINAL) originalHint else null,
                trailing = if (code == AudioLanguageOption.ORIGINAL) null else code.uppercase()
            ))
        }
        sortedLanguages.forEach { language ->
            add(SettingsPickerOption(language.code, language.displayName, trailing = language.code.uppercase()))
        }
    }

    SettingsSingleChoiceDialog(
        title = title,
        options = languageOptions,
        selectedValue = selectedLanguage,
        onOptionSelected = onLanguageSelected,
        onDismiss = onDismiss,
        width = 400.dp,
        maxHeight = 320.dp
    )
}

@Composable
internal fun ColorSelectionDialog(
    title: String,
    colors: List<Color>,
    selectedColor: Color,
    showTransparentOption: Boolean = false,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    val initialChip = colors.find { it.toArgb() == selectedColor.toArgb() }
        ?: colors.find { it.copy(alpha = 1f).toArgb() == selectedColor.copy(alpha = 1f).toArgb() }
        ?: colors.firstOrNull()
        ?: selectedColor
    val focusedColorIndex = colors.indexOfFirst { it.toArgb() == initialChip.toArgb() }
        .let { if (it >= 0) it else 0 }
    val colorListState = rememberLazyListState(initialFirstVisibleItemIndex = focusedColorIndex)
    var currentChipColor by remember { mutableStateOf(initialChip) }
    var alphaPercent by remember { mutableIntStateOf((selectedColor.alpha * 100f).roundToInt().coerceIn(0, 100)) }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
        ) {
            val firstColorFocusRequester = remember { FocusRequester() }
            LazyRow(
                state = colorListState,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsOptionRow(firstColorFocusRequester)
            ) {
                items(
                    count = colors.size,
                    key = { index -> colors[index].toArgb() }
                ) { index ->
                    val color = colors[index]
                    ColorOption(
                        color = color,
                        isSelected = color.toArgb() == currentChipColor.toArgb(),
                        isTransparent = color.alpha == 0f,
                        onClick = {
                            currentChipColor = color
                            if (color.alpha < 1f) {
                                alphaPercent = (color.alpha * 100f).roundToInt().coerceIn(0, 100)
                            }
                        },
                        modifier = if (index == focusedColorIndex) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }.then(
                            if (index == 0) {
                                Modifier.focusRequester(firstColorFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.sub_opacity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.width(70.dp)
                )
                Card(
                    onClick = { alphaPercent = (alphaPercent - 10).coerceAtLeast(0) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm))
                ) {
                    Text(
                        text = "−",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(NuvioTheme.spacing.sm)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(NuvioTheme.colors.BackgroundElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(alphaPercent / 100f)
                            .height(NuvioTheme.spacing.sm)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(NuvioTheme.colors.Primary)
                    )
                }
                Text(
                    text = "$alphaPercent%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Card(
                    onClick = { alphaPercent = (alphaPercent + 10).coerceAtMost(100) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm))
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                Card(
                    onClick = onDismiss,
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier
                            .padding(NuvioTheme.spacing.md)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                Card(
                    onClick = { onColorSelected(currentChipColor.copy(alpha = alphaPercent / 100f)) },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                        )
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.action_apply),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier
                            .padding(NuvioTheme.spacing.md)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    LaunchedEffect(focusedColorIndex) {
        focusRequester.requestFocusAfterFrames()
    }
}

@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    isTransparent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .size(NuvioTheme.spacing.xxxl)
            .then(modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(3.dp),
                shape = CircleShape
            ),
            border = if (isSelected) Border(
                border = BorderStroke(3.dp, NuvioTheme.colors.Primary),
                shape = CircleShape
            ) else Border.None
        ),
        shape = CardDefaults.shape(shape = CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.15f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isTransparent) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = if (color == Color.White || color == Color.Yellow) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
