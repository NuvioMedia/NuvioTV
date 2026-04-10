package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import dev.chrisbanes.haze.HazeDefaults.blurRadius
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
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
    val delayedBlurProgress = ((sidebarExpandProgress - 0.34f) / 0.66f).coerceIn(0f, 1f)
    val showPanelBlur = blurEnabled && isSidebarExpanded && !sidebarCollapsePending && delayedBlurProgress > 0f

    val bgElevated = NuvioColors.BackgroundElevated
    val bgCard = NuvioColors.BackgroundCard
    val panelBackgroundBrush = remember(blurEnabled, bgElevated, bgCard) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(Color(0xA0F0F4F7), Color(0xA0BCC6CC)))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }

    var focusedIndex by remember { mutableStateOf<Int?>(null) }
    val itemBounds = remember { mutableStateMapOf<Int, Rect>() }
    var parentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // This padding creates the "illusion" space for the indicator to bleed into
    val bleedPadding = 12.dp

    Box(
        modifier = Modifier
            .wrapContentWidth()
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
                clip = false // Ensure nothing is clipped
            }
            // We apply padding HERE so the internal coordinate system
            // has room for the indicator outside the visible background
            .padding(start = bleedPadding, top = 20.dp, bottom = bleedPadding, end = bleedPadding)
            .onGloballyPositioned { parentCoords = it }
    ) {
        // 1. THE VISIBLE PANEL BACKGROUND
        // We put this in its own Box so the background doesn't fill the "bleed" area
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (blurEnabled) {
                        Modifier.border(width = 1.dp, color = Color(0x05000000), shape = panelShape)
                    } else {
                        Modifier.border(width = 1.dp, color = Color(0x15FFFFFF), shape = panelShape)
                    }
                )
                .then(
                    if (showPanelBlur) {
                        Modifier.hazeChild(
                            state = sidebarHazeState,
                            shape = panelShape,
                            style = HazeStyle(
                                Color(0x60000000),
                                (10f * delayedBlurProgress).dp,
                                0.05f * delayedBlurProgress
                            )
                        )
                    } else Modifier
                )
                .background(brush = panelBackgroundBrush, shape = panelShape)
        )

        // 2. GLIDING FOCUS INDICATOR
        val currentRect = focusedIndex?.let { itemBounds[it] }
        if (currentRect != null) {
            val density = LocalDensity.current
            // The "bleed" is now just the indicator being larger than the item
            val horizontalBleed = with(density) { 12.dp.toPx() }
            val verticalBleed = with(density) { 12.dp.toPx() }

            val animX by animateFloatAsState(
                targetValue = currentRect.left - (horizontalBleed / 2),
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
                label = "indicatorX"
            )
            val animY by animateFloatAsState(
                targetValue = currentRect.top - (verticalBleed / 2),
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
                label = "indicatorY"
            )
            val animWidth by animateFloatAsState(
                targetValue = currentRect.width + horizontalBleed,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
                label = "indicatorWidth"
            )
            val animHeight by animateFloatAsState(
                targetValue = currentRect.height + verticalBleed,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
                label = "indicatorHeight"
            )

            val indicatorColor = if (focusedIndex == drawerItems.size) {
                Color.White.copy(alpha = 0.18f)
            } else {
                Color(0xE0D5D5D5)
            }
            val animColor by animateColorAsState(
                targetValue = indicatorColor,
                animationSpec = tween(durationMillis = 200),
                label = "indicatorColor"
            )

            Box(
                modifier = Modifier
                    .zIndex(0.5f)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(0, 0) { placeable.place(0, 0) }
                    }
                    .offset { IntOffset(animX.toInt(), animY.toInt()) }
                    .size(
                        width = with(LocalDensity.current) { animWidth.toDp() },
                        height = with(LocalDensity.current) { animHeight.toDp() }
                    )
                    .then(
                        if (blurEnabled) {
                            Modifier.border(
                                width = 1.dp,
                                color = Color(0x10000000),
                                shape = panelShape
                            )
                        } else {
                            Modifier.border(
                                width = 1.dp,
                                color = Color(0x50FFFFFF),
                                shape = panelShape
                            )
                        }
                    )
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(999.dp),
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black
                    )
                    .background(animColor, RoundedCornerShape(999.dp))
            )
        }

        // 3. THE ITEMS
        Row(
            modifier = Modifier
                .zIndex(1f)
                .padding(vertical = 4.dp),
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
                        if (focused) {
                            focusedIndex = drawerItems.size
                            onDrawerItemFocused(drawerItems.size)
                        } else if (focusedIndex == drawerItems.size) {
                            focusedIndex = null
                        }
                    },
                    onClick = onSwitchProfile,
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .onGloballyPositioned { coords ->
                            parentCoords?.let { parent ->
                                itemBounds[drawerItems.size] = parent.localBoundingBoxOf(coords)
                            }
                        }
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
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedIndex = index
                            onDrawerItemFocused(index)
                        } else if (focusedIndex == index) {
                            focusedIndex = null
                        }
                    },
                    onClick = { onDrawerItemClick(item.route) },
                    modifier = Modifier
                        .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                        .onGloballyPositioned { coords ->
                            parentCoords?.let { parent ->
                                itemBounds[index] = parent.localBoundingBoxOf(coords)
                            }
                        }
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

    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 250),
        label = "sidebarItemScale"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isFocused) Color(0xFF10151F) else Color.White,
        animationSpec = tween(durationMillis = 200),
        label = "sidebarItemContentColor"
    )

    val textShadow = if (!isFocused) {
        Shadow(
            color = Color.Black.copy(alpha = 0.35f),
            offset = Offset(0f, 0f),
            blurRadius = 20f
        )
    } else {
        null
    }

    Row(
        modifier = modifier
            .zIndex(if (selected || isFocused) 1f else 0f)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(shape)
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
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(SidebarLeadingVisualSize)
                .clip(CircleShape)
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

        Text(
            text = label,
            color = contentColor,
            modifier = Modifier
                .graphicsLayer { alpha = labelAlpha },
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 14.sp,
                shadow = textShadow
            ),
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

    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 250),
        label = "profileItemScale"
    )
    Row(
        modifier = modifier
            .zIndex(if (isFocused) 1f else 0f)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(shape)
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
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
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
