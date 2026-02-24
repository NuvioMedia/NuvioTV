@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

private val PANEL_TEXT_COLORS = listOf(
    Color.White,
    Color(0xFFD9D9D9),
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88)
)

private val PANEL_OUTLINE_COLORS = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C)
)

private val StyleCardWidth = 220.dp
private val StyleCardHeight = 102.dp
private val StyleCardGap = 12.dp
private val StyleGridWidth = (StyleCardWidth * 3) + (StyleCardGap * 2)

@Composable
internal fun SubtitleStyleSidePanel(
    subtitleStyle: SubtitleStyleSettings,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            firstItemFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Column(
        modifier = modifier
            .width(760.dp)
            .height(292.dp)
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(Color(0xFF101010))
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Row(
                modifier = Modifier.width(StyleGridWidth),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.subtitle_style_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StyleCardGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleStyleSection(
                    title = "Font Size",
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    SubtitleStyleSettingRow {
                        SubtitleStyleStepperButton(
                            icon = Icons.Default.Remove,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) },
                            modifier = Modifier.focusRequester(firstItemFocusRequester)
                        )
                        SubtitleStyleValueDisplay(text = "${subtitleStyle.size}%")
                        SubtitleStyleStepperButton(
                            icon = Icons.Default.Add,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) }
                        )
                    }
                }
                SubtitleStyleSection(
                    title = "Bold",
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    SubtitleStyleSettingRow(label = "Weight") {
                        SubtitleStyleToggleButton(
                            isEnabled = subtitleStyle.bold,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleStyleSection(
                    title = "Text Color",
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PANEL_TEXT_COLORS.forEach { color ->
                            SubtitleStyleColorChip(
                                color = color,
                                isSelected = subtitleStyle.textColor == color.toArgb(),
                                onClick = { onEvent(PlayerEvent.OnSetSubtitleTextColor(color.toArgb())) }
                            )
                        }
                    }
                }
                SubtitleStyleSection(
                    title = "Outline",
                    centerContent = false,
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SubtitleStyleToggleButton(
                                isEnabled = subtitleStyle.outlineEnabled,
                                onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                            )
                            Text(
                                text = stringResource(R.string.subtitle_style_color),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PANEL_OUTLINE_COLORS.forEach { color ->
                                SubtitleStyleColorChip(
                                    color = color.copy(alpha = if (subtitleStyle.outlineEnabled) 1f else 0.35f),
                                    isSelected = subtitleStyle.outlineColor == color.toArgb(),
                                    onClick = {
                                        if (!subtitleStyle.outlineEnabled) {
                                            onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(true))
                                        }
                                        onEvent(PlayerEvent.OnSetSubtitleOutlineColor(color.toArgb()))
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleStyleSection(
                    title = "Bottom Offset",
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    SubtitleStyleSettingRow {
                        SubtitleStyleStepperButton(
                            icon = Icons.Default.Remove,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) }
                        )
                        SubtitleStyleValueDisplay(text = subtitleStyle.verticalOffset.toString())
                        SubtitleStyleStepperButton(
                            icon = Icons.Default.Add,
                            onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) }
                        )
                    }
                }
                SubtitleStyleSection(
                    title = "Defaults",
                    modifier = Modifier
                        .width(StyleCardWidth)
                        .height(StyleCardHeight)
                ) {
                    Card(
                        onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) },
                        colors = CardDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            focusedContainerColor = Color.White.copy(alpha = 0.2f)
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = stringResource(R.string.subtitle_style_reset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleStyleSection(
    title: String,
    centerContent: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(if (centerContent) Alignment.CenterStart else Alignment.TopStart)
                .padding(top = 14.dp),
            contentAlignment = if (centerContent) Alignment.CenterStart else Alignment.TopStart
        ) {
            content()
        }
    }
}

@Composable
private fun SubtitleStyleSettingRow(
    label: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun SubtitleStyleStepperButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            focusedContainerColor = Color.White.copy(alpha = 0.28f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SubtitleStyleValueDisplay(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White
        )
    }
}

@Composable
private fun SubtitleStyleColorChip(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isLight = (color.red + color.green + color.blue) / 3f > 0.5f
    var isFocused by remember { mutableStateOf(false) }

    val borderModifier = when {
        isFocused -> Modifier.border(2.dp, NuvioColors.FocusRing, CircleShape)
        isSelected -> Modifier.border(2.dp, Color.White, CircleShape)
        else -> Modifier
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(30.dp)
            .then(borderModifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = IconButtonDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
            contentColor = if (isLight) Color.Black else Color.White,
            focusedContentColor = if (isLight) Color.Black else Color.White
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SubtitleStyleToggleButton(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (isEnabled) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.28f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(
            text = if (isEnabled) "On" else "Off",
            style = MaterialTheme.typography.bodySmall,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
