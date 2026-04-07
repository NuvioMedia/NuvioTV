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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.size.Size
import kotlin.collections.forEachIndexed
import kotlin.collections.getValue
import kotlin.ranges.coerceIn
import kotlin.text.isNotEmpty

private val SidebarLeadingVisualSize = 22.dp
private val SidebarContentGap = 8.dp
private val SidebarProfileContentGap = 10.dp

@Composable
internal fun ModernTopbarBlurPanel(
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
            blurRadius = (20f * delayedBlurProgress).dp,
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

    Box(
        modifier = Modifier
            .padding(top = 20.dp)
            .wrapContentWidth()
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 1.15f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .then(expandedPanelBlurModifier)
            .graphicsLayer {
                shape = panelShape
                clip = true
            }
            .clip(panelShape)
            .background(brush = panelBackgroundBrush, shape = panelShape)
            .border(width = 0.75.dp, color = panelBorderColor, shape = panelShape)
            .padding(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showProfileSelector && activeProfileName.isNotEmpty()) {
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
                    modifier = Modifier.widthIn(max = 140.dp)
                )
            }

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
                        .focusRequester(drawerItemFocusRequesters.getValue(item.route))
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
    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(SidebarLeadingVisualSize)
                .clip(CircleShape)
                .background(iconCircleColor)
                .padding(4.dp)
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
                    modifier = Modifier.size(14.dp)
                )

                iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(SidebarContentGap))

        Text(
            text = label,
            color = contentColor,
            modifier = Modifier
                .graphicsLayer { alpha = labelAlpha },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
        Text(
            text = profileName,
            color = Color.White,
            modifier = Modifier
                .graphicsLayer { alpha = labelAlpha },
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val context = LocalContext.current
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(rawIconRes)
            .decoderFactory(SvgDecoder.Factory())
            .size(Size.ORIGINAL)
            .build()
    )
}
