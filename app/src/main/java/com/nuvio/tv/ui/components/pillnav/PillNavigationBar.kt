package com.nuvio.tv.ui.components.pillnav

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 10-foot sizes. The pill sits in the header band root screens keep free when their built-in headers are hidden
 * (Settings reserves 68dp, Library/Discover a transparent title row), about where the modern sidebar's floating
 * pill sits, so it ends at 56dp and needs no content inset.
 */
internal object PillNavTokens {
    val barHeight = 44.dp
    val barTopGap = 12.dp
    val barSideMargin = 48.dp
    val innerPadding = 4.dp
    val itemHorizontalPadding = 18.dp
    val actionsGap = 12.dp
    val iconItemSize = 36.dp
    val iconSize = 22.dp
    val avatarSize = 30.dp
    val labelSize = 17.sp
    const val unselectedAlpha = 0.72f
    const val focusedScale = 1.08f
}

// Two tiers. With a [PillGlassBackdrop] (Android 13+, 3 GB+ RAM) the pill is liquid glass that bends the screen
// behind it. Everywhere else it is static glass: a dense tinted fill, a rim lit from the top left and a darker
// lens edge that give it thickness, all cached draws with no sampling of what is behind the pill.
private val GlassBaseColor = Color(0xFF1C1C1E)
private val GlassFocusedColor = Color(0xFF2C2C30)

internal enum class PillNavEntryKind { Tab, Settings, Profile }

/** One focusable slot of the pill. [key] is the route for tabs and settings. */
internal data class PillNavEntry(
    val key: String,
    val kind: PillNavEntryKind,
    val route: String?,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
)

/** Focus and hide-on-scroll state shared between the pill and the scaffold. */
@Stable
internal class PillNavBarState {
    /** True while any pill item holds focus. */
    var hasFocus by mutableStateOf(false)
        internal set

    /** Key of the pill item focused last; kept after focus leaves so D-pad moves can resolve it. */
    var focusedKey by mutableStateOf<String?>(null)
        internal set

    /** Home only: hidden after the user moves down into the rows, back on the way up (phone's hide-on-scroll). */
    var hiddenByScroll by mutableStateOf(false)
        private set

    fun show() {
        hiddenByScroll = false
    }

    fun hide() {
        if (!hasFocus) hiddenByScroll = true
    }
}

/**
 * Floating glass pill at the top centre: text tabs for the sidebar destinations, the settings gear and the
 * profile picture. A glass lens slides to the selected item, or to the focused one while the pill has focus.
 * Left/Right move between items and never leave the pill; Up stays; Down calls [onExitDown].
 */
@Composable
internal fun PillNavigationBar(
    entries: List<PillNavEntry>,
    selectedKey: String?,
    state: PillNavBarState,
    requesterFor: (String) -> FocusRequester,
    frosted: Boolean,
    interactive: Boolean,
    hidden: Boolean,
    canLeaveUp: Boolean,
    isRtl: Boolean,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    onEntryClick: (PillNavEntry) -> Unit,
    onExitDown: () -> Unit,
    onExitUp: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: PillGlassBackdrop? = null,
) {
    val barFocused = state.hasFocus
    val hideTarget = if (hidden && !barFocused) 1f else 0f
    // Read only inside graphicsLayer below, so hiding and showing redraw the layer without recomposing or relayout.
    val hideFraction = animateFloatAsState(
        targetValue = hideTarget,
        animationSpec = if (hideTarget == 1f) {
            tween(durationMillis = 240, easing = FastOutSlowInEasing)
        } else {
            tween(durationMillis = 320, easing = LinearOutSlowInEasing)
        },
        label = "pill_nav_hide",
    )
    val density = LocalDensity.current
    val hideDistancePx = with(density) { (PillNavTokens.barTopGap + PillNavTokens.barHeight + 8.dp).toPx() }

    // Only the selected item is reachable by focus search from the content, so D-pad Up always lands on it.
    val entryKeyForEntry = selectedKey?.takeIf { key -> entries.any { it.key == key } } ?: entries.firstOrNull()?.key
    val lensKey = if (barFocused) state.focusedKey ?: selectedKey else selectedKey

    val itemBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val indicator = remember { LiquidIndicator() }
    val lensTarget = lensKey?.let { itemBounds[it] }
    LaunchedEffect(lensKey, lensTarget) {
        val (x, width) = lensTarget ?: return@LaunchedEffect
        indicator.moveTo(x, x + width)
    }
    val selectedBounds = selectedKey?.let { itemBounds[it] }
    val showLens = lensTarget != null

    val accent = NuvioTheme.colors.Secondary
    // Read only inside drawBehind below, so the focus colour fade redraws without recomposing every frame.
    val glassColor = animateColorAsState(
        targetValue = when {
            barFocused -> GlassFocusedColor.copy(alpha = if (frosted) 0.88f else 0.95f)
            else -> GlassBaseColor.copy(alpha = if (frosted) 0.80f else 0.90f)
        },
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "pill_nav_glass",
    )
    val refracts = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val focusFraction = animateFloatAsState(
        targetValue = if (barFocused) 1f else 0f,
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "pill_nav_glass_focus",
    )
    val innerPaddingPx = with(density) { PillNavTokens.innerPadding.toPx() }
    val rowHeightPx = with(density) { (PillNavTokens.barHeight - PillNavTokens.innerPadding * 2).toPx() }
    var barOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = PillNavTokens.barTopGap, start = PillNavTokens.barSideMargin, end = PillNavTokens.barSideMargin),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .height(PillNavTokens.barHeight)
                .widthIn(max = 880.dp)
                .graphicsLayer {
                    val h = hideFraction.value
                    translationY = -h * hideDistancePx
                    // Fade a little ahead of the slide so the pill is gone before it reaches the screen edge.
                    alpha = (1f - 1.25f * h).coerceIn(0f, 1f)
                    scaleX = 1f - 0.05f * h
                    scaleY = 1f - 0.05f * h
                }
                .then(
                    if (refracts) {
                        Modifier.onGloballyPositioned { barOrigin = it.positionInRoot() }
                    } else {
                        Modifier
                            .drawWithCache {
                                val glass = StaticGlassPaint.create(this, accent, barFocused)
                                onDrawBehind {
                                    drawRoundRect(color = glassColor.value, cornerRadius = CornerRadius(size.minDimension / 2f))
                                    if (frosted) drawRoundRect(brush = FrostSheen, cornerRadius = CornerRadius(size.minDimension / 2f))
                                    drawStaticGlassEdge(glass)
                                }
                            }
                    }
                ),
        ) {
            if (refracts && backdrop != null) {
                LiquidPillGlass(
                    backdrop = backdrop,
                    origin = { barOrigin },
                    lens = {
                        if (!showLens || !indicator.placed) null
                        else indicator.bounds(rowHeightPx).translate(innerPaddingPx, innerPaddingPx)
                    },
                    focus = { focusFraction.value },
                    tint = accent,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(PillNavTokens.innerPadding)
                    .drawWithCache {
                        // Lens brushes and stroke are built once per size/focus change, not on every animation frame.
                        val lens = LensPaint.create(this, brighter = barFocused, refracts = refracts)
                        onDrawBehind {
                            if (barFocused && selectedBounds != null && lensKey != selectedKey) {
                                drawSelectedMarker(selectedBounds)
                            }
                            if (showLens) drawLiquidIndicator(indicator, lens)
                        }
                    }
                    .onFocusChanged { state.hasFocus = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // Up leaves the pill only for the update banner above it; otherwise it stays put.
                            Key.DirectionUp -> {
                                if (canLeaveUp) onExitUp()
                                true
                            }
                            Key.DirectionDown -> {
                                onExitDown()
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> {
                                val index = entries.indexOfFirst { it.key == state.focusedKey }
                                val forward = (event.key == Key.DirectionRight) != isRtl
                                val target = entries.getOrNull(if (forward) index + 1 else index - 1)
                                if (index >= 0 && target != null) {
                                    runCatching { requesterFor(target.key).requestFocus() }
                                }
                                true
                            }
                            else -> false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEachIndexed { index, entry ->
                    if (entry.kind != PillNavEntryKind.Tab && index > 0 && entries[index - 1].kind == PillNavEntryKind.Tab) {
                        Spacer(Modifier.width(PillNavTokens.actionsGap))
                    }
                    PillItem(
                        entry = entry,
                        selected = entry.key == selectedKey,
                        focusable = interactive && (barFocused || entry.key == entryKeyForEntry),
                        requester = requesterFor(entry.key),
                        onFocused = { state.focusedKey = entry.key },
                        onClick = { onEntryClick(entry) },
                        onBounds = { x, width ->
                            val bounds = x to width
                            if (itemBounds[entry.key] != bounds) itemBounds[entry.key] = bounds
                        },
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun PillItem(
    entry: PillNavEntry,
    selected: Boolean,
    focusable: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onBounds: (Float, Float) -> Unit,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) PillNavTokens.focusedScale else 1f,
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "pill_nav_item_scale",
    )
    val contentAlpha = if (focused || selected) 1f else PillNavTokens.unselectedAlpha
    val isTab = entry.kind == PillNavEntryKind.Tab
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (isTab) Modifier else Modifier.width(PillNavTokens.iconItemSize))
            .onPlaced { onBounds(it.positionInParent().x, it.size.width.toFloat()) }
            .focusRequester(requester)
            .focusProperties { canFocus = focusable }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = contentAlpha
            }
            .then(if (isTab) Modifier.padding(horizontal = PillNavTokens.itemHorizontalPadding) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (entry.kind) {
            PillNavEntryKind.Tab -> Text(
                text = entry.label,
                color = Color.White,
                fontSize = PillNavTokens.labelSize,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            PillNavEntryKind.Settings -> when {
                entry.icon != null -> Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label,
                    tint = Color.White,
                    modifier = Modifier.size(PillNavTokens.iconSize),
                )
                entry.iconRes != null -> Icon(
                    painter = rememberRawSvgPainter(entry.iconRes),
                    contentDescription = entry.label,
                    tint = Color.White,
                    modifier = Modifier.size(PillNavTokens.iconSize),
                )
            }
            PillNavEntryKind.Profile -> ProfileAvatarCircle(
                name = entry.label,
                colorHex = activeProfileColorHex,
                size = PillNavTokens.avatarSize,
                avatarImageUrl = activeProfileAvatarImageUrl,
                imageCrossfade = false,
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val sizePx = with(LocalDensity.current) { PillNavTokens.iconSize.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}

/**
 * The glass lens as two independently sprung edges, as on the phone: the leading edge races ahead and the
 * trailing edge follows, so the lens stretches in flight and settles back into a pill.
 */
@Stable
private class LiquidIndicator {
    val left = Animatable(0f)
    val right = Animatable(0f)
    var placed by mutableStateOf(false)
        private set
    private var restWidth = 1f

    suspend fun moveTo(targetLeft: Float, targetRight: Float) {
        restWidth = (targetRight - targetLeft).coerceAtLeast(1f)
        if (!placed) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            placed = true
            return
        }
        val movingRight = targetLeft > left.value
        val lead = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
        val trail = spring<Float>(dampingRatio = 0.86f, stiffness = 210f)
        coroutineScope {
            launch { left.animateTo(targetLeft, if (movingRight) trail else lead) }
            launch { right.animateTo(targetRight, if (movingRight) lead else trail) }
        }
    }

    /** 0 at rest, towards 1 while stretched in flight. */
    fun stretch(): Float = ((right.value - left.value) / restWidth - 1f).coerceIn(0f, 1.5f) / 1.5f

    /** The lens in row coordinates, thinner while stretched in flight. */
    fun bounds(rowHeight: Float): Rect {
        val height = rowHeight * (1f - 0.16f * stretch())
        val top = (rowHeight - height) / 2f
        return Rect(left.value, top, right.value, top + height)
    }
}

private fun DrawScope.drawSelectedMarker(bounds: Pair<Float, Float>) {
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(bounds.first, 0f),
        size = Size(bounds.second, size.height),
        cornerRadius = CornerRadius(size.height / 2f),
    )
}

/** Cached paint for the glass lens; gradients span the whole row height so they survive the in-flight squash. */
private class LensPaint(val fill: Brush, val rim: Brush?, val rimStroke: Stroke, val rimWidth: Float) {
    companion object {
        fun create(scope: androidx.compose.ui.draw.CacheDrawScope, brighter: Boolean, refracts: Boolean): LensPaint {
            // Liquid glass draws the lens and its rim in the shader; this only adds a whisper of frost on top.
            val boost = (if (brighter) 1.4f else 1f) * (if (refracts) 0.35f else 1f)
            val h = scope.size.height
            val rimWidth = with(scope) { 1.dp.toPx() }
            return LensPaint(
                fill = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f * boost),
                    0.55f to Color.White.copy(alpha = 0.16f * boost),
                    1f to Color.White.copy(alpha = 0.22f * boost),
                    startY = 0f,
                    endY = h,
                ),
                // Lit from the top left like the pill's own rim.
                rim = if (refracts) null else Brush.linearGradient(
                    0f to Color.White.copy(alpha = (0.62f * boost).coerceAtMost(1f)),
                    0.5f to Color.White.copy(alpha = 0.08f),
                    1f to Color.White.copy(alpha = 0.28f * boost),
                    start = Offset.Zero,
                    end = Offset(h * 2.5f, h),
                ),
                rimStroke = Stroke(rimWidth),
                rimWidth = rimWidth,
            )
        }
    }
}

private fun DrawScope.drawLiquidIndicator(indicator: LiquidIndicator, paint: LensPaint) {
    if (!indicator.placed) return
    val stretch = indicator.stretch()
    val squash = 1f - 0.16f * stretch
    val height = size.height * squash
    val top = (size.height - height) / 2f
    val width = indicator.right.value - indicator.left.value
    if (width <= 0f) return
    val left = indicator.left.value
    // Glass lens: a bright top falling to a soft base, with a specular rim.
    drawRoundRect(
        brush = paint.fill,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    val rimBrush = paint.rim ?: return
    val rim = paint.rimWidth
    drawRoundRect(
        brush = rimBrush,
        topLeft = Offset(left + rim / 2f, top + rim / 2f),
        size = Size(width - rim, height - rim),
        cornerRadius = CornerRadius((height - rim) / 2f),
        style = paint.rimStroke,
    )
}

/** Static top sheen over the glass fill: the frosted look without sampling what is behind the pill. */
private val FrostSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.5f to Color.White.copy(alpha = 0.03f),
    1f to Color.Transparent,
)

/** Static glass edge: a rim lit from the top left with a dim echo bottom right, over a darker inner lens band. */
private class StaticGlassPaint(val rim: Brush, val rimWidth: Float, val band: Brush, val bandWidth: Float) {
    companion object {
        fun create(scope: androidx.compose.ui.draw.CacheDrawScope, accent: Color, focused: Boolean): StaticGlassPaint {
            val light = if (focused) lerp(Color.White, accent, 0.35f) else Color.White
            val h = scope.size.height
            return StaticGlassPaint(
                rim = Brush.linearGradient(
                    0f to light.copy(alpha = if (focused) 0.70f else 0.46f),
                    0.45f to Color.White.copy(alpha = if (focused) 0.08f else 0.04f),
                    1f to light.copy(alpha = if (focused) 0.30f else 0.18f),
                    start = Offset.Zero,
                    end = Offset(h * 3f, h),
                ),
                rimWidth = with(scope) { (if (focused) 1.5.dp else 1.dp).toPx() },
                band = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Black.copy(alpha = 0.20f),
                    startY = 0f,
                    endY = h,
                ),
                bandWidth = with(scope) { 3.dp.toPx() },
            )
        }
    }
}

private fun DrawScope.drawStaticGlassEdge(paint: StaticGlassPaint) {
    val band = paint.bandWidth
    val rim = paint.rimWidth
    val inset = rim + band / 2f
    drawRoundRect(
        brush = paint.band,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius((size.height - inset * 2f) / 2f),
        style = Stroke(band),
    )
    drawRoundRect(
        brush = paint.rim,
        topLeft = Offset(rim / 2f, rim / 2f),
        size = Size(size.width - rim, size.height - rim),
        cornerRadius = CornerRadius((size.height - rim) / 2f),
        style = Stroke(rim),
    )
}

/**
 * Liquid glass over the recorded screen (Android 13+): the backdrop is replayed under the pill, lined up with the
 * screen, on top of the glass base colour (so holes such as video surfaces read as dark glass), and bent by
 * [PillGlassShader] in one pass over the pill's own area.
 */
@Composable
private fun LiquidPillGlass(
    backdrop: PillGlassBackdrop,
    origin: () -> Offset,
    lens: () -> Rect?,
    focus: () -> Float,
    tint: Color,
    modifier: Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    LiquidPillGlassApi33(backdrop, origin, lens, focus, tint, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiquidPillGlassApi33(
    backdrop: PillGlassBackdrop,
    origin: () -> Offset,
    lens: () -> Rect?,
    focus: () -> Float,
    tint: Color,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(PillGlassShader) }
    Box(
        modifier
            .graphicsLayer {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("density", density)
                shader.setFloatUniform("outset", 0f)
                shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
                val bounds = lens()
                if (bounds != null) {
                    shader.setFloatUniform("lens", bounds.left, bounds.top, bounds.right, bounds.bottom)
                } else {
                    shader.setFloatUniform("lens", 0f, 0f, 0f, 0f)
                }
                shader.setFloatUniform("focus", focus())
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
            }
            .drawBehind {
                backdrop.version // Redraw whenever the screen behind was re-recorded.
                drawRect(GlassBaseColor)
                with(backdrop) { drawAligned(origin()) }
            },
    )
}
