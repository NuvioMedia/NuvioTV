package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest

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
            blurRadius = (26f * delayedBlurProgress).dp,
            noiseFactor = 0.04f * delayedBlurProgress
        )
    } else {
        Modifier
    }
    val bgElevated = NuvioColors.BackgroundElevated
    val bgCard = NuvioColors.BackgroundCard
    val borderBase = NuvioColors.Border
    val panelBackgroundBrush = remember(blurEnabled, bgElevated, bgCard) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(Color(0xD64A4F59), Color(0xCC3F454F), Color(0xC640474F)))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }
    val panelBorderColor = remember(blurEnabled, borderBase) {
        if (blurEnabled) Color.White.copy(alpha = 0.14f) else borderBase.copy(alpha = 0.9f)
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
            .border(width = 1.dp, color = panelBorderColor, shape = panelShape)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        val headerLogoRes = if (isSidebarExpanded) R.drawable.app_logo_wordmark else R.drawable.app_logo_mark
        val headerLogoHeight = if (isSidebarExpanded) 42.dp else 34.dp
        val headerLogoContentDescription = if (isSidebarExpanded) "NuvioTV" else "Nuvio"

        Image(
            painter = painterResource(id = headerLogoRes),
            contentDescription = headerLogoContentDescription,
            modifier = Modifier
                .fillMaxWidth()
                .height(headerLogoHeight)
                .offset(y = 12.dp),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.offset(y = (-12).dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                drawerItems.forEachIndexed { index, item ->
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

        if (activeProfileName.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SidebarProfileItem(
                    profileName = activeProfileName,
                    profileColorHex = activeProfileColorHex,
                    focusEnabled = keepSidebarFocusDuringCollapse,
                    labelAlpha = sidebarLabelAlpha,
                    onFocusChanged = { focused ->
                        if (focused) onDrawerItemFocused(drawerItems.size)
                    },
                    onClick = onSwitchProfile,
                    modifier = Modifier.fillMaxWidth(0.92f)
                )
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
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> Color.White
            isFocused -> Color.White.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180),
        label = "sidebarItemBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) Color.White.copy(alpha = 0.4f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "sidebarItemBorder"
    )

    val contentColor = if (selected) Color(0xFF10151F) else Color.White
    val iconCircleColor = if (selected) Color(0xFFE7E2EF) else Color(0xFF6A6A74)
    val iconContainerSize = 34.dp
    val contentGap = 14.dp

    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor, shape = shape)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusable(enabled = focusEnabled)
            .onPreviewKeyEvent { event ->
                if (focusEnabled && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(iconContainerSize)
                .clip(CircleShape)
                .background(iconCircleColor)
                .padding(6.dp)
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
                    modifier = Modifier.size(22.dp)
                )

                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(contentGap))

        Text(
            text = label,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(iconContainerSize + contentGap))
    }
}

@Composable
private fun SidebarProfileItem(
    profileName: String,
    profileColorHex: String,
    focusEnabled: Boolean,
    labelAlpha: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "profileItemBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) Color.White.copy(alpha = 0.4f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "profileItemBorder"
    )
    val contentGap = 14.dp
    val iconContainerSize = 34.dp

    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.5.dp, color = borderColor, shape = shape)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusable(enabled = focusEnabled)
            .onPreviewKeyEvent { event ->
                if (focusEnabled && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileAvatarCircle(
            name = profileName,
            colorHex = profileColorHex,
            size = iconContainerSize
        )
        Spacer(modifier = Modifier.width(contentGap))
        Text(
            text = profileName,
            color = Color.White,
            modifier = Modifier
                .graphicsLayer { alpha = labelAlpha },
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
            maxLines = 1
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter = rememberAsyncImagePainter(
    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(rawIconRes)
        .decoderFactory(SvgDecoder.Factory())
        .build()
)
