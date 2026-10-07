package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.CardDepthSurface
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GridContentCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showLabel: Boolean = true,
    showLogo: Boolean = false,
    imageCrossfade: Boolean = true,
    isWatched: Boolean = false,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    depthSurface: CardDepthSurface = CardDepthSurface.POSTERS,
    showReleaseInfo: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onFocused: () -> Unit = {}
) {
    val cardShape = remember(posterCardStyle.cornerRadius) { RoundedCornerShape(posterCardStyle.cornerRadius) }
    val cardDepthStyle = LocalCardDepthStyle.current
    val density = LocalDensity.current
    val globalLandscape = LocalLandscapePosterMode.current
    val effectivePosterShape = if (globalLandscape) PosterShape.LANDSCAPE else item.posterShape

    // Derive card height from item's posterShape aspect ratio while keeping width from posterCardStyle.
    // This ensures grids and rows display landscape/square shapes correctly.
    val cardHeight = when (effectivePosterShape) {
        PosterShape.POSTER -> posterCardStyle.height
        PosterShape.LANDSCAPE -> {
            val landscapeWidth = if (globalLandscape) posterCardStyle.height else posterCardStyle.width
            landscapeWidth / PosterShape.LANDSCAPE.aspectRatio()
        }
        PosterShape.SQUARE -> posterCardStyle.width
    }

    // Items that are already landscape (e.g. More Like This, Collections, Trailers)
    // keep their explicit posterCardStyle width even in global landscape mode.
    // fillMaxWidth only makes sense inside a grid where cells constrain width.
    val isNativelyLandscape = item.posterShape == PosterShape.LANDSCAPE

    val landscapeCardWidth = if (globalLandscape && !isNativelyLandscape) posterCardStyle.height else posterCardStyle.width

    val requestCardWidth = landscapeCardWidth
    val requestWidthPx = remember(density, requestCardWidth) { with(density) { requestCardWidth.roundToPx() }.coerceAtLeast(1) }
    val requestHeightPx = remember(density, cardHeight) { with(density) { cardHeight.roundToPx() }.coerceAtLeast(1) }
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    Column(
        modifier = modifier
            .width(landscapeCardWidth)
            .recompositionHighlighter()
    ) {
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .then(
                    if (globalLandscape) Modifier.fillMaxWidth().aspectRatio(PosterShape.LANDSCAPE.aspectRatio())
                    else Modifier.width(posterCardStyle.width).height(cardHeight)
                )
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .then(
                    if (upFocusRequester != null || downFocusRequester != null) {
                        Modifier.focusProperties {
                            if (upFocusRequester != null) {
                                up = upFocusRequester
                            }
                            if (downFocusRequester != null) {
                                down = downFocusRequester
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && onLongPress != null) {
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (onLongPress != null &&
                        longPressKeyTracker.handle(native, ::isSelectKey) {
                            longPressTriggered = true
                            onLongPress()
                        }
                    ) {
                        if (native.action == AndroidKeyEvent.ACTION_UP) {
                            longPressTriggered = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(posterCardStyle.focusedBorderWidth),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
                    .nuvioCardDepth(
                        shape = cardShape,
                        surface = depthSurface,
                        style = cardDepthStyle
                    )
            ) {
                val context = LocalContext.current
                val bgCardColor = NuvioTheme.colors.BackgroundCard
                val bgPainter = remember(bgCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgCardColor) }
                val alwaysBackdropWithLogo = LocalAlwaysBackdropWithLogo.current
                val effectiveLandscapePoster = if (globalLandscape && alwaysBackdropWithLogo) null else item.landscapePoster
                val effectiveImageUrl = if (globalLandscape) {
                    effectiveLandscapePoster ?: item.background ?: item.poster
                } else {
                    item.poster
                }
                val revalidationKey = com.nuvio.tv.core.image.rememberImageRevalidationKey(effectiveImageUrl)
                val imageModel = remember(effectiveImageUrl, requestWidthPx, requestHeightPx, revalidationKey) {
                    val builder = ImageRequest.Builder(context)
                        .data(effectiveImageUrl)
                        .crossfade(imageCrossfade)
                        .size(width = requestWidthPx, height = requestHeightPx)
                        .memoryCacheKey("${effectiveImageUrl}_${requestWidthPx}x${requestHeightPx}_v$revalidationKey")
                    if (revalidationKey > 0) {
                        builder.placeholderMemoryCacheKey("${effectiveImageUrl}_${requestWidthPx}x${requestHeightPx}_v${revalidationKey - 1}")
                    }
                    val fallbackUrl = item.rawPosterUrl
                    if (!fallbackUrl.isNullOrBlank() && fallbackUrl != effectiveImageUrl) {
                        builder.memoryCacheKeyExtras(
                            mapOf(com.nuvio.tv.core.image.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackUrl)
                        )
                    }
                    builder.build()
                }
                if (effectiveImageUrl.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter
                    )
                }

                // Landscape clearlogo overlay on backdrop cards
                val isLandscapeBackdropCard = globalLandscape &&
                    effectiveLandscapePoster.isNullOrBlank()
                val showLandscapeClearlogo = isLandscapeBackdropCard &&
                    !item.logo.isNullOrBlank()
                if (showLandscapeClearlogo) {
                    var landscapeLogoFailed by remember(item.logo) { mutableStateOf(false) }
                    if (!landscapeLogoFailed) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithCache {
                                    val gradient = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.Transparent,
                                            0.58f to Color.Transparent,
                                            1.0f to Color.Black.copy(alpha = 0.75f)
                                        )
                                    )
                                    onDrawBehind { drawRect(gradient) }
                                }
                        )
                        val logoRequest = remember(item.logo) {
                            ImageRequest.Builder(context)
                                .data(item.logo)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = item.name,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(0.62f)
                                .then(if (globalLandscape) Modifier.fillMaxHeight(0.34f) else Modifier.height(cardHeight * 0.34f))
                                .padding(start = 10.dp, end = 10.dp, bottom = NuvioTheme.spacing.sm),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            onError = { landscapeLogoFailed = true }
                        )
                    }
                } else if (isLandscapeBackdropCard && !showLandscapeClearlogo && !showLabel) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val gradient = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.58f to Color.Transparent,
                                        1.0f to Color.Black.copy(alpha = 0.75f)
                                    )
                                )
                                onDrawBehind { drawRect(gradient) }
                            }
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = NuvioTheme.spacing.md)
                    )
                } else if (showLogo && !item.logo.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(cardHeight * 0.45f)
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithCache {
                                val gradient = Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                                )
                                onDrawBehind { drawRect(gradient) }
                            }
                    )
                    val logoRequest = remember(item.logo) {
                        ImageRequest.Builder(context)
                            .data(item.logo)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = cardHeight * 0.35f)
                            .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                    )
                }

                if (isWatched) {
                    WatchedMarker(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
                            .zIndex(2f)
                    )
                }
            }
        }

        if (showLabel && (!showLogo || item.logo.isNullOrBlank())) {
            FocusMarqueeText(
                text = item.name,
                focused = isFocused,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                    .padding(top = NuvioTheme.spacing.sm, start = NuvioTheme.spacing.xxs, end = NuvioTheme.spacing.xxs)
            )
            item.releaseInfo?.takeIf { showReleaseInfo }?.let { info ->
                FocusMarqueeText(
                    text = info,
                    focused = isFocused,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary,
                    modifier = Modifier
                        .then(if (globalLandscape) Modifier.fillMaxWidth() else Modifier.width(posterCardStyle.width))
                        .padding(top = 2.dp, start = NuvioTheme.spacing.xxs, end = NuvioTheme.spacing.xxs)
                )
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
