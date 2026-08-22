package com.nuvio.tv.ui.screens.detail

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.BlurTransformation
import com.nuvio.tv.ui.util.localizeEpisodeTitle
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
    val title = episode.title.localizeEpisodeTitle(context)
    val description = episode.overview?.trim().orEmpty()
    val blurUnwatchedBackdrop = blurUnwatchedEpisodes && !isWatched
    val thumbnailUrl = remember(episode.thumbnail, blurUnwatchedBackdrop) {
        if (blurUnwatchedBackdrop) {
            episode.thumbnail?.takeIf { it.isNotBlank() }
        } else {
            episodeOverlayBackdropUrl(episode.thumbnail)
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
        blurUnwatchedBackdrop
    ) {
        thumbnailUrl?.let { url ->
            episodeOverlayBackdropRequest(
                context,
                url,
                backdropWidthPx,
                backdropHeightPx,
                blur = blurUnwatchedBackdrop
            )
        }
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
                .background(Color(0xFF050505))
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.00f to overlayColor.copy(alpha = 0.95f),
                                0.28f to overlayColor.copy(alpha = 0.90f),
                                0.48f to overlayColor.copy(alpha = 0.72f),
                                0.70f to overlayColor.copy(alpha = 0.50f),
                                1.00f to overlayColor.copy(alpha = 0.40f)
                            ),
                            startX = if (isRtl) size.width else 0f,
                            endX = if (isRtl) 0f else size.width
                        )
                        onDrawBehind {
                            drawRect(brush = brush)
                        }
                    }
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp, vertical = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(72.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
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
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .width(360.dp)
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

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
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
