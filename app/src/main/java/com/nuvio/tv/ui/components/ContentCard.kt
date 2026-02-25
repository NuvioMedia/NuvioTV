package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

private const val BACKDROP_ASPECT_RATIO = 16f / 9f
private const val TRAILER_PREVIEW_REQUEST_FOCUS_DEBOUNCE_MS = 140L
private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContentCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showLabels: Boolean = true,
    focusedPosterBackdropExpandEnabled: Boolean = false,
    focusedPosterBackdropExpandDelaySeconds: Int = 3,
    focusedPosterBackdropTrailerEnabled: Boolean = false,
    focusedPosterBackdropTrailerMuted: Boolean = true,
    trailerPreviewUrl: String? = null,
    onRequestTrailerPreview: (MetaPreview) -> Unit = {},
    isWatched: Boolean = false,
    onFocus: (MetaPreview) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val baseCardWidth = when (item.posterShape) {
        PosterShape.POSTER -> posterCardStyle.width
        PosterShape.LANDSCAPE -> 260.dp
        PosterShape.SQUARE -> 170.dp
    }
    val baseCardHeight = when (item.posterShape) {
        PosterShape.POSTER -> posterCardStyle.height
        PosterShape.LANDSCAPE -> 148.dp
        PosterShape.SQUARE -> 170.dp
    }
    val expandedCardWidth = baseCardHeight * BACKDROP_ASPECT_RATIO

    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var isBackdropExpanded by remember { mutableStateOf(false) }
    var trailerFirstFrameRendered by remember(trailerPreviewUrl) { mutableStateOf(false) }
    val watchedIconEndPadding by animateDpAsState(
        targetValue = if (isFocused) 18.dp else 8.dp,
        animationSpec = tween(durationMillis = 180),
        label = "contentCardWatchedIconEndPadding"
    )

    val needsFocusState = focusedPosterBackdropExpandEnabled || focusedPosterBackdropTrailerEnabled
    val lastFocusedRef = remember { booleanArrayOf(false) }

    if (focusedPosterBackdropExpandEnabled) {
        LaunchedEffect(
            focusedPosterBackdropExpandDelaySeconds,
            isFocused,
            interactionNonce,
            item.id
        ) {
            if (!isFocused) {
                isBackdropExpanded = false
                return@LaunchedEffect
            }

            val delaySeconds = focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(1)

            isBackdropExpanded = false
            val backdropDelayMs = delaySeconds * 1000L
            delay(backdropDelayMs)
            if (isFocused && focusedPosterBackdropExpandEnabled) {
                isBackdropExpanded = true
            }
        }
    }

    if (focusedPosterBackdropTrailerEnabled) {
        LaunchedEffect(
            item.id,
            isFocused,
            trailerPreviewUrl
        ) {
            if (!isFocused) return@LaunchedEffect
            if (trailerPreviewUrl != null) return@LaunchedEffect
            delay(TRAILER_PREVIEW_REQUEST_FOCUS_DEBOUNCE_MS)
            if (!isFocused) return@LaunchedEffect
            onRequestTrailerPreview(item)
        }
    }

    val animatedCardWidth = if (focusedPosterBackdropExpandEnabled) {
        val targetCardWidth = if (isBackdropExpanded) expandedCardWidth else baseCardWidth
        val width by animateDpAsState(targetValue = targetCardWidth, label = "contentCardWidth")
        width
    } else {
        baseCardWidth
    }
    val metaTokens = if (isBackdropExpanded) {
        remember(item.type, item.genres, item.releaseInfo, item.imdbRating) {
            buildList {
                add(
                    item.apiType
                        .replaceFirstChar { ch -> ch.uppercase() }
                )
                item.genres.firstOrNull()?.let { add(it) }
                item.releaseInfo
                    ?.let { YEAR_REGEX.find(it)?.value }
                    ?.let { add(it) }
                item.imdbRating?.let { add(String.format("%.1f", it)) }
            }
        }
    } else {
        emptyList()
    }

    Column(
        modifier = modifier.width(animatedCardWidth)
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        // Keep decode size stable during width animation to avoid recreating requests/painters every frame.
        val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
            maxOf(baseCardWidth, expandedCardWidth)
        } else {
            baseCardWidth
        }
        val requestWidthPx = remember(maxRequestCardWidth, density) {
            with(density) { maxRequestCardWidth.roundToPx() }
        }
        val requestHeightPx = remember(baseCardHeight, density) {
            with(density) { baseCardHeight.roundToPx() }
        }
        val imageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
            item.background ?: item.poster
        } else {
            item.poster
        }
        val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(false)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
        val logoRequestHeightPx = remember(density) {
            with(density) { 48.dp.roundToPx() }
        }
        val logoModel = remember(context, item.logo, requestWidthPx, logoRequestHeightPx) {
            item.logo?.let { logoUrl ->
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .crossfade(false)
                    .size(width = requestWidthPx, height = logoRequestHeightPx)
                    .build()
            }
        }

        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    val focusedNow = state.isFocused
                    if (needsFocusState) {
                        if (focusedNow != isFocused) {
                            isFocused = focusedNow
                            if (focusedNow) {
                                interactionNonce++
                                onFocus(item)
                            } else {
                                isBackdropExpanded = false
                            }
                        }
                    } else {
                        if (focusedNow != lastFocusedRef[0]) {
                            lastFocusedRef[0] = focusedNow
                            if (focusedNow) onFocus(item)
                        }
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && isFocused && shouldResetBackdropTimer(native)) {
                            interactionNonce++
                        }
                        if (onLongPress != null) {
                            if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                                longPressTriggered = true
                                onLongPress()
                                return@onPreviewKeyEvent true
                            }
                            val isLongPress = native.isLongPress || native.repeatCount > 0
                            if (isLongPress && isSelectKey(native.keyCode)) {
                                longPressTriggered = true
                                onLongPress()
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        isSelectKey(native.keyCode)
                    ) {
                        return@onPreviewKeyEvent true
                    }
                    false
                }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                ),
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(baseCardHeight)
                    .clip(cardShape)
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    MonochromePosterPlaceholder()
                }

                val shouldPlayTrailerPreview = isBackdropExpanded &&
                    focusedPosterBackdropTrailerEnabled &&
                    isFocused &&
                    trailerPreviewUrl != null

                if (focusedPosterBackdropTrailerEnabled) {
                    LaunchedEffect(shouldPlayTrailerPreview) {
                        if (!shouldPlayTrailerPreview) {
                            trailerFirstFrameRendered = false
                        }
                    }
                }

                val trailerCoverAlpha = if (focusedPosterBackdropTrailerEnabled) {
                    val alpha by animateFloatAsState(
                        targetValue = if (shouldPlayTrailerPreview && !trailerFirstFrameRendered) 1f else 0f,
                        animationSpec = tween(durationMillis = 250),
                        label = "trailerCoverAlpha"
                    )
                    alpha
                } else {
                    0f
                }

                if (shouldPlayTrailerPreview) {
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        isPlaying = true,
                        onEnded = {
                            trailerFirstFrameRendered = false
                            isBackdropExpanded = false
                        },
                        onFirstFrameRendered = {
                            trailerFirstFrameRendered = true
                        },
                        modifier = Modifier.fillMaxSize(),
                        muted = focusedPosterBackdropTrailerMuted
                    )
                }

                if (shouldPlayTrailerPreview && !imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = trailerCoverAlpha },
                        contentScale = ContentScale.Crop
                    )
                }

                if (isBackdropExpanded) {
                    val logoAreaGradient = remember {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.76f)
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(logoAreaGradient)
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .fillMaxWidth(0.75f)
                    ) {
                        if (item.logo != null) {
                            AsyncImage(
                                model = logoModel,
                                contentDescription = item.name,
                                modifier = Modifier
                                    .height(48.dp)
                                    .fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart
                            )
                        } else {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = watchedIconEndPadding, top = 8.dp)
                            .zIndex(2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }

        if (isBackdropExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (metaTokens.isNotEmpty()) {
                    Text(
                        text = metaTokens.joinToString("  •  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                item.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showLabels && !isBackdropExpanded) {
            val textMeasurer = rememberTextMeasurer()
            val titleStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary)
            val subtitleStyle = MaterialTheme.typography.labelMedium.copy(color = NuvioTheme.extendedColors.textSecondary)
            val releaseInfo = item.releaseInfo
            val itemName = item.name
            val labelHeight = if (releaseInfo != null) 52.dp else 32.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(labelHeight)
                    .drawWithCache {
                        val widthPx = size.width.toInt()
                        val titleLayout = textMeasurer.measure(
                            text = itemName,
                            style = titleStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = widthPx)
                        )
                        val subtitleLayout = if (releaseInfo != null) {
                            textMeasurer.measure(
                                text = releaseInfo,
                                style = subtitleStyle,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                constraints = Constraints(maxWidth = widthPx)
                            )
                        } else null
                        onDrawBehind {
                            drawText(titleLayout)
                            if (subtitleLayout != null) {
                                drawText(subtitleLayout, topLeft = androidx.compose.ui.geometry.Offset(0f, titleLayout.size.height + 4.dp.toPx()))
                            }
                        }
                    }
            )
        }
    }
}

private fun shouldResetBackdropTimer(nativeEvent: AndroidKeyEvent): Boolean {
    if (nativeEvent.action != AndroidKeyEvent.ACTION_DOWN) return false
    if (nativeEvent.repeatCount > 0 || nativeEvent.isLongPress) return false

    return when (nativeEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP,
        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_BACK -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
