@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioRadii

internal val SettingsContainerRadius = NuvioComponents.tokens.settings.containerRadius
internal val SettingsPillRadius = NuvioRadii.tokens.full
internal val SettingsSecondaryCardRadius = NuvioComponents.tokens.settings.secondaryCardRadius
internal val SettingsRailItemHeight = NuvioComponents.tokens.settings.railItemHeight

internal data class SettingsPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val trailing: String? = null,
    val titleFontFamily: FontFamily? = null
)

@Composable
internal fun SettingsStandaloneScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl)
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsBrandPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    showBuiltInHeader: Boolean = true
) {
    val titleColor = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
    val subtitleColor = if (showBuiltInHeader) NuvioTheme.colors.TextSecondary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = NuvioTheme.colors.Border,
                shape = RoundedCornerShape(SettingsContainerRadius)
            )
            .padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = titleColor
                )
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        Image(
            painter = painterResource(id = R.drawable.app_logo_wordmark),
            contentDescription = stringResource(R.string.cd_nuvio_logo),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(72.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.settings_rounded_ui),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp,
            color = subtitleColor
        )
    }
}

@Composable
internal fun SettingsWorkspaceSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = NuvioTheme.colors.Border,
                shape = RoundedCornerShape(SettingsContainerRadius)
            )
            .padding(20.dp),
        content = content
    )
}

@Composable
internal fun SettingsRailButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    icon: ImageVector? = null,
    rawIconRes: Int? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val appliedModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }

    Card(
        onClick = onClick,
        modifier = appliedModifier
            .padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
            .fillMaxWidth()
            .heightIn(min = SettingsRailItemHeight)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRailItemHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rawIconRes != null) {
                        Image(
                            painter = rememberRawSvgPainter(rawIconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(
                                if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                            )
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (rawIconRes != null || icon != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    FocusMarqueeText(
                        text = title,
                        focused = isFocused,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (isSelected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun SettingsDetailHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
            .background(NuvioTheme.colors.BackgroundCard)
            .border(
                width = NuvioTheme.spacing.hairline,
                color = NuvioTheme.colors.Border,
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        content()
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (enabled) onToggle()
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            SettingsTogglePill(
                checked = checked,
                enabled = enabled
            )
        }
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String?,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    trailingIcon: ImageVector = Icons.Default.ChevronRight,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = NuvioTheme.colors.TextPrimary,
    leadingIcon: ImageVector? = null
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(NuvioTheme.spacing.xl)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (titleTrailingIcon != null) {
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Icon(
                            imageVector = titleTrailingIcon,
                            contentDescription = null,
                            tint = titleTrailingIconTint.copy(alpha = contentAlpha),
                            modifier = Modifier.size(NuvioTheme.spacing.lg)
                        )
                    }
                }
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = NuvioTheme.colors.TextTertiary.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun <T> SettingsSingleChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 420.dp,
    maxHeight: Dp = 320.dp
) {
    val focusRequester = remember { FocusRequester() }
    val focusedIndex = options.indexOfFirst { it.value == selectedValue }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = focusedIndex)

    LaunchedEffect(focusedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                itemsIndexed(
                    items = options,
                    key = { index, option -> "$index-${option.value}" }
                ) { index, option ->
                    val isSelected = option.value == selectedValue
                    Card(
                        onClick = { onOptionSelected(option.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == focusedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
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
                                    text = option.title,
                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = option.titleFontFamily
                                )
                                if (!option.description.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                    Text(
                                        text = option.description,
                                        color = NuvioTheme.colors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (!option.trailing.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                                Text(
                                    text = option.trailing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextSecondary
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
internal fun <T> SettingsMultiChoiceDialog(
    title: String,
    options: List<SettingsPickerOption<T>>,
    selectedValues: List<T>,
    onValuesSelected: (List<T>) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 520.dp,
    maxHeight: Dp = 420.dp
) {
    val focusRequester = remember { FocusRequester() }
    val selected = remember(selectedValues) { mutableStateListOf<T>().also { it.addAll(selectedValues) } }
    val firstSelectedIndex = options.indexOfFirst { option -> selectedValues.contains(option.value) }
        .let { if (it >= 0) it else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstSelectedIndex)

    LaunchedEffect(firstSelectedIndex) {
        focusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
                ) {
                    itemsIndexed(
                        items = options,
                        key = { index, option -> "$index-${option.value}" }
                    ) { index, option ->
                        val isSelected = selected.contains(option.value)
                        Card(
                            onClick = {
                                if (isSelected) {
                                    selected.remove(option.value)
                                } else {
                                    selected.add(option.value)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == firstSelectedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
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
                                        text = option.title,
                                        color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = option.titleFontFamily
                                    )
                                    if (!option.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                        Text(
                                            text = option.description,
                                            color = NuvioTheme.colors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
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
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_clear),
                    onClick = { selected.clear() }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_save),
                    onClick = { onValuesSelected(options.map { it.value }.filter { selected.contains(it) }) },
                    primary = true
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogActionRow(
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SettingsDialogActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusRing.copy(alpha = 0.2f) else NuvioTheme.colors.Background,
            focusedContainerColor = if (selected) NuvioTheme.colors.FocusRing.copy(alpha = 0.2f) else NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 10.dp)
        )
    }
}

@Composable
private fun SettingsTogglePill(
    checked: Boolean,
    enabled: Boolean
) {
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(NuvioTheme.spacing.xl)
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(
                if (checked) {
                    NuvioTheme.colors.Secondary.copy(alpha = 0.35f * alpha)
                } else {
                    NuvioTheme.colors.Border.copy(alpha = alpha)
                }
            )
            .padding(NuvioTheme.spacing.xxs),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = alpha))
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    val request = remember(rawIconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .size(sizePx)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}
