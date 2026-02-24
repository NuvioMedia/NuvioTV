@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

internal val SettingsContainerRadius = 28.dp
internal val SettingsPillRadius = 999.dp
internal val SettingsSecondaryCardRadius = 18.dp
internal val SettingsRailItemHeight = 56.dp

@Composable
internal fun SettingsStandaloneScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
    val titleColor = if (showBuiltInHeader) NuvioColors.TextPrimary else Color.Transparent
    val subtitleColor = if (showBuiltInHeader) NuvioColors.TextSecondary else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SettingsContainerRadius))
            .background(NuvioColors.BackgroundElevated)
            .border(
                width = 1.dp,
                color = NuvioColors.Border,
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
                    .background(NuvioColors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = titleColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        Image(
            painter = painterResource(id = R.drawable.app_logo_wordmark),
            contentDescription = "NuvioTV",
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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = subtitleColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Rounded UI",
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
            .background(NuvioColors.BackgroundElevated)
            .border(
                width = 1.dp,
                color = NuvioColors.Border,
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
            containerColor = if (isSelected) NuvioColors.BackgroundCard else NuvioColors.Background,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
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
                                if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary
                            )
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (rawIconRes != null || icon != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = NuvioColors.TextTertiary,
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
            color = NuvioColors.TextPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
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
            .background(NuvioColors.BackgroundCard)
            .border(
                width = 1.dp,
                color = NuvioColors.Border,
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
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
            .height(62.dp)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
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
    trailingIcon: ImageVector = Icons.Default.ChevronRight
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = { if (enabled) onClick() },
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
            containerColor = NuvioColors.Background,
            focusedContainerColor = NuvioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioColors.TextSecondary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = NuvioColors.TextTertiary.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp)
            )
        }
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
            containerColor = if (selected) NuvioColors.FocusRing.copy(alpha = 0.2f) else NuvioColors.Background,
            focusedContainerColor = if (selected) NuvioColors.FocusRing.copy(alpha = 0.2f) else NuvioColors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(1.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
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
            .height(24.dp)
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(
                if (checked) {
                    NuvioColors.Secondary.copy(alpha = 0.35f * alpha)
                } else {
                    NuvioColors.Border.copy(alpha = alpha)
                }
            )
            .padding(2.dp),
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
    val request = remember(rawIconRes, context) {
        ImageRequest.Builder(context)
            .data(rawIconRes)
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}
