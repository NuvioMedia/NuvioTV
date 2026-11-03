package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.tv.ui.theme.NuvioTheme

// Keep Reshaped's settings contracts while using upstream's current row design.
// Upstream's toggle and slider rows intentionally omit leading icons.
// Disabled Reshaped rows remain focusable, including the category's initial focus target.
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ToggleSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    titleTrailingIcon: ImageVector? = null,
    titleTrailingIconTint: Color = NuvioTheme.colors.TextPrimary,
    expandSubtitleOnFocus: Boolean = false
) {
    SettingsToggleRow(
        title = title,
        subtitle = subtitle,
        checked = isChecked,
        onToggle = { onCheckedChange(!isChecked) },
        onFocused = onFocused,
        modifier = Modifier.focusProperties { canFocus = true },
        enabled = enabled,
        titleTrailingIcon = titleTrailingIcon,
        titleTrailingIconTint = titleTrailingIconTint,
        expandSubtitleOnFocus = expandSubtitleOnFocus
    )
}

@Composable
internal fun NavigationSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        onFocused = onFocused,
        modifier = Modifier.focusProperties { canFocus = true },
        enabled = enabled,
        leadingIcon = icon
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SliderSettingsItem(
    icon: ImageVector?,
    title: String,
    value: Int,
    valueText: String,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    SliderSettingsItem(
        title = title,
        value = value,
        valueText = valueText,
        minValue = minValue,
        maxValue = maxValue,
        step = step,
        onValueChange = onValueChange,
        subtitle = subtitle,
        onFocused = onFocused,
        enabled = enabled,
        modifier = modifier
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SliderSettingsItem(
    icon: ImageVector?,
    title: String,
    values: List<Int>,
    selected: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    SliderSettingsItem(
        title = title,
        values = values,
        selected = selected,
        valueText = valueText,
        onValueChange = onValueChange,
        subtitle = subtitle,
        onFocused = onFocused,
        enabled = enabled,
        modifier = modifier
    )
}
