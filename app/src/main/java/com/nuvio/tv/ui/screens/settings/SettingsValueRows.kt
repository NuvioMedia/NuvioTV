@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R

@Composable
internal fun SliderSettingsItem(
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
    val span = (maxValue - minValue).toFloat()
    val progress = if (span > 0f) (value - minValue).toFloat() / span else 0f

    SliderSettingsItemLayout(
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        onDecrease = {
            val newValue = (value - step).coerceAtLeast(minValue)
            if (newValue != value) onValueChange(newValue)
        },
        onIncrease = {
            val newValue = (value + step).coerceAtMost(maxValue)
            if (newValue != value) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier
    )
}

@Composable
internal fun SliderSettingsItem(
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
    require(values.isNotEmpty()) { "SliderSettingsItem.values must not be empty" }

    val index = values.indexOf(selected).coerceAtLeast(0)
    val lastIndex = values.lastIndex
    val progress = if (lastIndex > 0) index.toFloat() / lastIndex.toFloat() else 0f

    SliderSettingsItemLayout(
        title = title,
        valueText = valueText,
        subtitle = subtitle,
        enabled = enabled,
        progressFraction = progress,
        onDecrease = {
            val newValue = values[(index - 1).coerceAtLeast(0)]
            if (newValue != selected) onValueChange(newValue)
        },
        onIncrease = {
            val newValue = values[(index + 1).coerceAtMost(lastIndex)]
            if (newValue != selected) onValueChange(newValue)
        },
        onFocused = onFocused,
        modifier = modifier
    )
}

@Composable
private fun SliderSettingsItemLayout(
    title: String,
    valueText: String,
    subtitle: String?,
    enabled: Boolean,
    progressFraction: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = settingsTallRowShape()

    Card(
        onClick = { },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isRtl) onIncrease() else onDecrease()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isRtl) onDecrease() else onIncrease()
                        true
                    }
                    else -> false
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha = if (enabled) 1f else 0.3f, shape = shape),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsRowContentInset, vertical = NuvioTheme.spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
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

                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.Primary.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                SliderStepButton(
                    icon = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.cd_decrease),
                    enabled = enabled,
                    onClick = onDecrease,
                    onFocused = onFocused
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(NuvioTheme.spacing.sm)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(NuvioTheme.colors.BackgroundElevated)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .height(NuvioTheme.spacing.sm)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(NuvioTheme.colors.Primary.copy(alpha = contentAlpha))
                    )
                }

                SliderStepButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_increase),
                    enabled = enabled,
                    onClick = onIncrease,
                    onFocused = onFocused
                )
            }
        }
    }
}

@Composable
private fun SliderStepButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f
    val flat = isFlatSettingsStyle()

    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.onFocusChanged { state ->
            val nowFocused = state.isFocused
            if (isFocused != nowFocused) {
                isFocused = nowFocused
                if (nowFocused) onFocused()
            }
        },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = if (flat) settingsFocusFillColor() else NuvioTheme.colors.Background
        ),
        border = if (flat) {
            CardDefaults.border(border = Border.None, focusedBorder = Border.None)
        } else {
            CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = CircleShape
                )
            )
        },
        shape = CardDefaults.shape(shape = CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.1f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = (if (isFocused) NuvioTheme.colors.OnPrimary else NuvioTheme.colors.TextPrimary)
                    .copy(alpha = contentAlpha),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun ColorSettingsItem(
    title: String,
    currentColor: Color,
    showTransparent: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = settingsRowColors(),
        border = settingsRowBorder(contentAlpha = if (enabled) 1f else 0.3f),
        shape = CardDefaults.shape(shape = settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = SettingsRowContentInset, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

            if (showTransparent || currentColor.alpha == 0f) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(NuvioTheme.spacing.xxs, NuvioTheme.colors.Border, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brush = Brush.linearGradient(colors = listOf(Color.White, Color.Gray, Color.White)))
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(NuvioTheme.spacing.xxs, NuvioTheme.colors.Border, CircleShape)
                )
            }
        }
    }
}
