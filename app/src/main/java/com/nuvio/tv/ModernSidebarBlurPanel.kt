package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

private val SidebarLeadingVisualSize = NuvioComponents.tokens.sidebar.leadingVisual
private val SidebarContentGap = NuvioComponents.tokens.sidebar.contentGap
private val SidebarProfileContentGap = NuvioComponents.tokens.sidebar.contentGap + NuvioTheme.spacing.xs

@Composable
internal fun ModernSidebarBlurPanel(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    keepSidebarFocusDuringCollapse: Boolean,
    sidebarLabelAlpha: Float,
    sidebarIconScale: Float,
    sidebarExpandProgress: Float,
    isSidebarExpanded: Boolean,
    sidebarCollapsePending: Boolean,
    blurEnabled: Boolean,
    sidebarHazeState: HazeState,
    panelShape: RoundedCornerShape,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit
) {
    val delayedBlurProgress =
        ((sidebarExpandProgress - 0.34f) / 0.66f).coerceIn(0f, 1f)
    val showPanelBlur = blurEnabled &&
        isSidebarExpanded &&
        !sidebarCollapsePending &&
        delayedBlurProgress > 0f
    val expandedPanelBlurModifier = if (showPanelBlur) {
        Modifier.hazeChild(
            state = sidebarHazeState,
            shape = panelShape,
            tint = Color.Unspecified,
            blurRadius = NuvioTheme.effects.blurPanel * delayedBlurProgress,
            noiseFactor = 0.04f * delayedBlurProgress
        )
    } else {
        Modifier
    }
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val panelBackgroundBrush = remember(blurEnabled, bgElevated, bgCard) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(colors.media.glassPanelTop, colors.media.glassPanelMiddle, colors.media.glassPanelBottom))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }
    val panelBorderColor = remember(blurEnabled, borderBase) {
        if (blurEnabled) colors.text.onOverlay.copy(alpha = 0.14f) else borderBase.copy(alpha = 0.9f)
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .then(expandedPanelBlurModifier)
            .graphicsLayer {
                shape = panelShape
                clip = true
            }
            .clip(panelShape)
            .background(brush = panelBackgroundBrush, shape = panelShape)
            .border(width = NuvioStrokes.tokens.hairline, color = panelBorderColor, shape = panelShape)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs)
    ) {
        if (showProfileSelector && activeProfileName.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = NuvioTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                SidebarProfileItem(
                    profileName = activeProfileName,
                    profileColorHex = activeProfileColorHex,
                    profileAvatarImageUrl = activeProfileAvatarImageUrl,
                    focusEnabled = keepSidebarFocusDuringCollapse,
                    labelAlpha = sidebarLabelAlpha,
                    onFocusChanged = { focused ->
                        if (focused) onDrawerItemFocused(drawerItems.size)
                    },
                    onClick = onSwitchProfile,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = NuvioTheme.spacing.md),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(36.dp),
                    alpha = sidebarLabelAlpha
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.offset(y = (-12).dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm - NuvioTheme.spacing.xxs)
            ) {
                drawerItems.forEachIndexed { index, item ->
                    key(item.route) {
                        SidebarNavigationItem(
                            label = item.label,
                            iconRes = item.iconRes,
                            icon = item.icon,
                            selected = selectedDrawerRoute == item.route,
                            focusEnabled = keepSidebarFocusDuringCollapse,
                            labelAlpha = sidebarLabelAlpha,
                            iconScale = sidebarIconScale,
                            onFocusChanged = {
                                if (it) {
                                    onDrawerItemFocused(index)
                                }
                            },
                            onClick = { onDrawerItemClick(item.route) },
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNavigationItem(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusEnabled: Boolean,
    labelAlpha: Float,
    iconScale: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> colors.selection.mutedBackground
            isFocused -> colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowSoftAlpha)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowStrongAlpha) else Color.Transparent,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "sidebarItemBorder"
    )

    val contentColor = if (selected) colors.selection.mutedForeground else colors.text.onOverlay
    val iconCircleColor = if (selected) colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowSoftAlpha) else colors.SurfaceVariant
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, borderColor),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .size(SidebarLeadingVisualSize)
                .clip(CircleShape)
                .background(iconCircleColor)
                .padding(NuvioTheme.spacing.sm - NuvioTheme.spacing.xxs)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(NuvioComponents.tokens.sidebar.iconSize)
                )

                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(NuvioComponents.tokens.sidebar.iconSize)
                )
            }
        }
        Spacer(modifier = Modifier.width(SidebarContentGap))

        AutoResizeText(
            text = label,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge
        )
    }
    }
}

@Composable
private fun SidebarProfileItem(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    focusEnabled: Boolean,
    labelAlpha: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowSoftAlpha) else Color.Transparent,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "profileItemBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) colors.text.onOverlay.copy(alpha = NuvioTheme.effects.glowStrongAlpha) else Color.Transparent,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast),
        label = "profileItemBorder"
    )
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, borderColor),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, vertical = NuvioTheme.spacing.sm + NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier.size(SidebarLeadingVisualSize),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = SidebarLeadingVisualSize,
                avatarImageUrl = profileAvatarImageUrl
            )
        }
        Spacer(modifier = Modifier.width(SidebarProfileContentGap))
        AutoResizeText(
            text = profileName,
            color = colors.text.onOverlay,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            )
        )
    }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}
