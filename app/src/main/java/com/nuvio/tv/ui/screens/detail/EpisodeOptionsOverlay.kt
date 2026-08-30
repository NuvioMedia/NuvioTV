package com.nuvio.tv.ui.screens.detail

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import kotlinx.coroutines.launch
import java.util.Locale

private data class EpisodeOverlayAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpisodeOptionsOverlay(
    episode: Video,
    imdbRating: Double? = null,
    isWatched: Boolean,
    blurUnwatchedEpisodes: Boolean = false,
    style: EpisodeOptionsOverlayStyle = EpisodeOptionsOverlayStyle.ARTWORK,
    isPending: Boolean,
    isSeasonFullyWatched: Boolean = false,
    hasPreviousEpisodes: Boolean = false,
    hasProgress: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    onOpenEpisodeComments: () -> Unit = {},
    showOpenEpisodeComments: Boolean = false,
    onPlayManually: () -> Unit = {},
    showPlayManually: Boolean = false,
    onToggleWatched: () -> Unit,
    onMarkSeasonWatched: () -> Unit = {},
    onMarkSeasonUnwatched: () -> Unit = {},
    onMarkPreviousEpisodesWatched: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val overlayColor = Color(0xFF050505)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primaryFocusRequester = remember { FocusRequester() }
    val detailsFocusRequester = remember { FocusRequester() }
    val detailsScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val title = episode.title.localizeEpisodeTitle(context)
    val description = episode.overview?.trim().orEmpty()
    val titleStyle = episodeOverlayTitleStyle(title.length)
    val descriptionStyle = episodeOverlayDescriptionStyle(description.length)
    val isNoneStyle = !shouldShowEpisodeOverlayBackdrop(style)
    val isCompactLayout = configuration.screenWidthDp < 1200 || configuration.screenHeightDp < 700
    val horizontalPadding = if (isNoneStyle || !isCompactLayout) 64.dp else 32.dp
    val verticalPadding = if (isNoneStyle || !isCompactLayout) 48.dp else 24.dp
    val contentSpacing = if (isNoneStyle || !isCompactLayout) 72.dp else 40.dp
    val actionsWidth = if (isNoneStyle || !isCompactLayout) 360.dp else 320.dp
    val blurBackdrop = shouldBlurEpisodeOverlayBackdrop(
        style = style,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        isWatched = isWatched
    )
    val thumbnailUrl = remember(episode.thumbnail, style, blurBackdrop) {
        when {
            isNoneStyle -> null
            blurBackdrop -> episode.thumbnail?.takeIf { it.isNotBlank() }
            else -> episodeOverlayBackdropUrl(episode.thumbnail)
        }
    }
    val backdropWidthPx = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val backdropHeightPx = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }
    }
    val thumbnailRequest = remember(
        context,
        thumbnailUrl,
        backdropWidthPx,
        backdropHeightPx,
        blurBackdrop
    ) {
        thumbnailUrl?.let { url ->
            episodeOverlayBackdropRequest(
                context,
                url,
                backdropWidthPx,
                backdropHeightPx,
                blur = blurBackdrop
            )
        }
    }
    val noneBackgroundBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF050505),
                Color(0xFF090909),
                Color(0xFF111111)
            )
        )
    }
    val ratingLabel = remember(imdbRating) {
        imdbRating?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
    }
    val episodeLabel = when {
        episode.season != null && episode.episode != null -> {
            stringResource(R.string.season_episode_format, episode.season, episode.episode)
        }
        episode.episode != null -> {
            "${stringResource(R.string.episodes_episode)} ${episode.episode}"
        }
        else -> stringResource(R.string.episodes_dialog_subtitle)
    }
    val actions = buildList {
        add(
            EpisodeOverlayAction(
                label = if (isWatched) {
                    stringResource(R.string.episodes_mark_unwatched)
                } else {
                    stringResource(R.string.episodes_mark_watched)
                },
                enabled = !isPending,
                onClick = onToggleWatched
            )
        )
        add(
            EpisodeOverlayAction(
                label = if (isSeasonFullyWatched) {
                    stringResource(R.string.episodes_mark_season_unwatched)
                } else {
                    stringResource(R.string.episodes_mark_season_watched)
                },
                onClick = if (isSeasonFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched
            )
        )
        if (hasPreviousEpisodes) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.episodes_mark_previous_watched),
                    onClick = onMarkPreviousEpisodesWatched
                )
            )
        }
        add(
            EpisodeOverlayAction(
                label = stringResource(R.string.episodes_play),
                onClick = onPlay
            )
        )
        if (showOpenEpisodeComments) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.episodes_open_comments),
                    onClick = onOpenEpisodeComments
                )
            )
        }
        if (showPlayManually) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.play_manually),
                    onClick = onPlayManually
                )
            )
        }
        if (hasProgress) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.cw_action_start_from_beginning),
                    onClick = onStartFromBeginning
                )
            )
        }
    }
    val initialActionIndex = actions.indexOfFirst { it.enabled }.coerceAtLeast(0)
    var acceptsSelectKey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isNoneStyle) {
                        Modifier.background(noneBackgroundBrush)
                    } else {
                        Modifier.background(Color(0xFF050505))
                    }
                )
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (isSelectKey(native.keyCode)) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            acceptsSelectKey = true
                        }
                        if (!acceptsSelectKey) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            if (thumbnailRequest != null) {
                AsyncImage(
                    model = thumbnailRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    filterQuality = FilterQuality.High
                )
            }
            if (!isNoneStyle) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.00f to overlayColor,
                                    0.10f to overlayColor.copy(alpha = 0.97f),
                                    0.20f to overlayColor.copy(alpha = 0.93f),
                                    0.30f to overlayColor.copy(alpha = 0.87f),
                                    0.40f to overlayColor.copy(alpha = 0.78f),
                                    0.50f to overlayColor.copy(alpha = 0.67f),
                                    0.60f to overlayColor.copy(alpha = 0.55f),
                                    0.70f to overlayColor.copy(alpha = 0.45f),
                                    0.80f to overlayColor.copy(alpha = 0.38f),
                                    0.90f to overlayColor.copy(alpha = 0.34f),
                                    1.00f to overlayColor.copy(alpha = 0.30f)
                                ),
                                startX = if (isRtl) size.width else 0f,
                                endX = if (isRtl) 0f else size.width
                            )
                            onDrawBehind {
                                drawRect(brush = brush)
                            }
                        }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                horizontalArrangement = Arrangement.spacedBy(contentSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isNoneStyle) {
                                Modifier.padding(end = 24.dp)
                            } else {
                                Modifier
                                    .fillMaxHeight()
                                    .verticalScroll(detailsScrollState)
                                    .focusRequester(detailsFocusRequester)
                                    .focusProperties {
                                        if (isRtl) {
                                            left = primaryFocusRequester
                                        } else {
                                            right = primaryFocusRequester
                                        }
                                    }
                                    .focusable()
                                    .onPreviewKeyEvent { event ->
                                        when {
                                            event.type != KeyEventType.KeyDown -> false
                                            event.key == Key.DirectionDown && detailsScrollState.value < detailsScrollState.maxValue -> {
                                                coroutineScope.launch {
                                                    detailsScrollState.animateScrollTo(
                                                        (detailsScrollState.value + 260)
                                                            .coerceAtMost(detailsScrollState.maxValue)
                                                    )
                                                }
                                                true
                                            }
                                            event.key == Key.DirectionUp && detailsScrollState.value > 0 -> {
                                                coroutineScope.launch {
                                                    detailsScrollState.animateScrollTo(
                                                        (detailsScrollState.value - 260).coerceAtLeast(0)
                                                    )
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                            }
                        ),
                    verticalArrangement = if (isNoneStyle) {
                        Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    } else {
                        Arrangement.spacedBy(NuvioTheme.spacing.lg, Alignment.CenterVertically)
                    }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = episodeLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NuvioTheme.colors.Primary
                        )

                        ratingLabel?.let { rating ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ImdbRatingSourceLabel(
                                    logoModifier = Modifier.size(30.dp),
                                    textStyle = MaterialTheme.typography.titleMedium,
                                    textColor = Color.White.copy(alpha = 0.72f)
                                )
                                Text(
                                    text = rating,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }
                    }

                    Text(
                        text = title,
                        style = if (isNoneStyle) MaterialTheme.typography.displayLarge else titleStyle,
                        color = Color.White,
                        maxLines = if (isNoneStyle) 3 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = if (isNoneStyle) {
                                MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal)
                            } else {
                                descriptionStyle
                            },
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = if (isNoneStyle) 8 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .width(actionsWidth)
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    actions.forEachIndexed { index, action ->
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isNoneStyle) {
                                        Modifier
                                    } else {
                                        Modifier.focusProperties {
                                            if (isRtl) {
                                                right = detailsFocusRequester
                                            } else {
                                                left = detailsFocusRequester
                                            }
                                        }
                                    }
                                )
                                .then(
                                    if (index == initialActionIndex) {
                                        Modifier.focusRequester(primaryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun episodeOverlayTitleStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 40 -> typography.displayLarge
        length <= 75 -> typography.displayMedium
        length <= 120 -> typography.headlineLarge
        else -> typography.headlineMedium
    }
}

@Composable
private fun episodeOverlayDescriptionStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 240 -> typography.headlineMedium.copy(fontWeight = FontWeight.Normal)
        length <= 420 -> typography.titleLarge.copy(fontWeight = FontWeight.Normal)
        length <= 700 -> typography.titleMedium.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 24.sp
        )
        else -> typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 22.sp
        )
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

internal fun shouldShowEpisodeOverlayBackdrop(style: EpisodeOptionsOverlayStyle): Boolean {
    return style != EpisodeOptionsOverlayStyle.NONE
}

internal fun shouldBlurEpisodeOverlayBackdrop(
    style: EpisodeOptionsOverlayStyle,
    blurUnwatchedEpisodes: Boolean,
    isWatched: Boolean
): Boolean {
    return style == EpisodeOptionsOverlayStyle.BLUR ||
        (style == EpisodeOptionsOverlayStyle.ARTWORK && blurUnwatchedEpisodes && !isWatched)
}

private const val TMDB_IMAGE_SIZE_PREFIX = "/t/p/"
private const val OVERLAY_BLUR_MAX_WIDTH_PX = 480

internal fun episodeOverlayBackdropUrl(thumbnail: String?): String? {
    return thumbnail?.takeIf { it.isNotBlank() }?.let(::upgradeTmdbImageUrl)
}

internal fun episodeOverlayBackdropDecodeSize(
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean
): Pair<Int, Int> {
    val width = screenWidthPx.coerceAtLeast(1)
    val height = screenHeightPx.coerceAtLeast(1)
    if (!blur) return width to height
    val blurredWidth = (width / 4).coerceIn(1, OVERLAY_BLUR_MAX_WIDTH_PX)
    val blurredHeight = ((height.toLong() * blurredWidth) / width).toInt().coerceAtLeast(1)
    return blurredWidth to blurredHeight
}

internal fun episodeOverlayBackdropMemoryCacheKey(
    url: String,
    widthPx: Int,
    heightPx: Int,
    blur: Boolean
): String {
    return "${url}_${widthPx}x${heightPx}_blur$blur"
}

internal fun episodeOverlayBackdropRequest(
    context: Context,
    url: String,
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean = false
): ImageRequest {
    val (widthPx, heightPx) = episodeOverlayBackdropDecodeSize(screenWidthPx, screenHeightPx, blur)
    val cacheKey = episodeOverlayBackdropMemoryCacheKey(url, widthPx, heightPx, blur)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(url)
        .crossfade(true)
        .size(width = widthPx, height = heightPx)
        .apply {
            if (blur) transformations(BlurTransformation())
        }
        .build()
}

internal fun upgradeTmdbImageUrl(url: String, size: String = "w1280"): String {
    val sizeStart = url.indexOf(TMDB_IMAGE_SIZE_PREFIX, ignoreCase = true)
        .takeIf { it >= 0 }
        ?.plus(TMDB_IMAGE_SIZE_PREFIX.length)
        ?: return url
    val sizeEnd = url.indexOf('/', sizeStart).takeIf { it > sizeStart } ?: return url
    val currentSize = url.substring(sizeStart, sizeEnd)
    if (currentSize.equals(size, ignoreCase = true)) return url
    return url.substring(0, sizeStart) + size + url.substring(sizeEnd)
}
