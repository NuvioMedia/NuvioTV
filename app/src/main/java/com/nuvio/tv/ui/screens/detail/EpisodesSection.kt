package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonTabs(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit,
    onSeasonLongPress: (Int) -> Unit = {},
    selectedTabFocusRequester: FocusRequester
) {
    // Move season 0 (specials) to the end
    val sortedSeasons = remember(seasons) {
        val regularSeasons = seasons.filter { it > 0 }.sorted()
        val specials = seasons.filter { it == 0 }
        regularSeasons + specials
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sortedSeasons, key = { it }) { season ->
            val isSelected = season == selectedSeason
            var isFocused by remember { mutableStateOf(false) }
            var longPressTriggered by remember { mutableStateOf(false) }

            Card(
                onClick = {
                    if (longPressTriggered) {
                        longPressTriggered = false
                    } else {
                        onSeasonSelected(season)
                    }
                },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedTabFocusRequester) else Modifier)
                    .onFocusChanged {
                    val nowFocused = it.isFocused
                    isFocused = nowFocused
                    if (nowFocused && !isSelected) {
                        onSeasonSelected(season)
                    }
                }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                            if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                                longPressTriggered = true
                                onSeasonLongPress(season)
                                return@onPreviewKeyEvent true
                            }
                            val isLongPress = native.isLongPress || native.repeatCount > 0
                            if (isLongPress && isSelectKey(native.keyCode)) {
                                longPressTriggered = true
                                onSeasonLongPress(season)
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (native.action == AndroidKeyEvent.ACTION_UP &&
                            longPressTriggered &&
                            isSelectKey(native.keyCode)
                        ) {
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                shape = CardDefaults.shape(
                    shape = RoundedCornerShape(20.dp)
                ),
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) NuvioColors.SurfaceVariant else NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.Secondary
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(20.dp)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1.0f)
            ) {
                Text(
                    text = if (season == 0) stringResource(R.string.episodes_specials) else stringResource(R.string.episodes_season, season),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isFocused -> NuvioColors.OnSecondary
                        isSelected -> NuvioColors.TextPrimary
                        else -> NuvioTheme.extendedColors.textSecondary
                    },
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodesRow(
    episodes: List<Video>,
    episodeProgressMap: Map<Pair<Int, Int>, com.nuvio.tv.domain.model.WatchProgress> = emptyMap(),
    watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    episodeWatchedPendingKeys: Set<String> = emptySet(),
    blurUnwatchedEpisodes: Boolean = false,
    onEpisodeClick: (Video) -> Unit,
    onToggleEpisodeWatched: (Video) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit = {},
    onMarkSeasonUnwatched: (Int) -> Unit = {},
    isSeasonFullyWatched: Boolean = false,
    selectedSeason: Int = 1,
    onMarkPreviousEpisodesWatched: (Video) -> Unit = {},
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester? = null,
    restoreEpisodeId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {}
) {
    val restoreFocusRequester = remember { FocusRequester() }
    var optionsEpisode by remember { mutableStateOf<Video?>(null) }

    LaunchedEffect(restoreFocusToken, restoreEpisodeId, episodes) {
        if (restoreFocusToken <= 0 || restoreEpisodeId.isNullOrBlank()) return@LaunchedEffect
        if (episodes.none { it.id == restoreEpisodeId }) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(episodes, key = { it.id }) { episode ->
            val progress = episode.season?.let { s ->
                episode.episode?.let { e ->
                    episodeProgressMap[s to e]
                }
            }
            val isMarkedWatched = episode.season?.let { s ->
                episode.episode?.let { e ->
                    watchedEpisodes.contains(s to e)
                }
            } ?: false
            EpisodeCard(
                episode = episode,
                watchProgress = progress,
                isMarkedWatched = isMarkedWatched,
                blurUnwatched = blurUnwatchedEpisodes,
                onClick = { onEpisodeClick(episode) },
                onLongPress = { optionsEpisode = episode },
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                focusRequester = if (episode.id == restoreEpisodeId) restoreFocusRequester else null,
                onFocusRestored = if (episode.id == restoreEpisodeId) onRestoreFocusHandled else null
            )
        }
    }

    optionsEpisode?.let { selectedEpisode ->
        val selectedWatched = selectedEpisode.season?.let { season ->
            selectedEpisode.episode?.let { episode ->
                episodeProgressMap[season to episode]?.isCompleted() == true
                    || watchedEpisodes.contains(season to episode)
            }
        } ?: false
        val isPending = episodeWatchedPendingKeys.contains(episodePendingKey(selectedEpisode))
        val firstEpisodeInSeason = episodes.minByOrNull { it.episode ?: Int.MAX_VALUE }
        val hasPreviousEpisodes = selectedEpisode.episode != null &&
            firstEpisodeInSeason?.episode != null &&
            selectedEpisode.episode > firstEpisodeInSeason.episode

        EpisodeOptionsDialog(
            episode = selectedEpisode,
            isWatched = selectedWatched,
            isPending = isPending,
            isSeasonFullyWatched = isSeasonFullyWatched,
            hasPreviousEpisodes = hasPreviousEpisodes,
            onDismiss = { optionsEpisode = null },
            onPlay = {
                onEpisodeClick(selectedEpisode)
                optionsEpisode = null
            },
            onToggleWatched = {
                onToggleEpisodeWatched(selectedEpisode)
                optionsEpisode = null
            },
            onMarkSeasonWatched = {
                onMarkSeasonWatched(selectedSeason)
                optionsEpisode = null
            },
            onMarkSeasonUnwatched = {
                onMarkSeasonUnwatched(selectedSeason)
                optionsEpisode = null
            },
            onMarkPreviousEpisodesWatched = {
                onMarkPreviousEpisodesWatched(selectedEpisode)
                optionsEpisode = null
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: Video,
    watchProgress: com.nuvio.tv.domain.model.WatchProgress? = null,
    isMarkedWatched: Boolean = false,
    blurUnwatched: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    onFocusRestored: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) } ?: ""
    }
    val isWatched = watchProgress?.isCompleted() == true || isMarkedWatched
    val shouldBlur = blurUnwatched && !isWatched
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val thumbnailWidth = 280.dp
    val cardWidth = 280.dp
    val watchedIconEndPadding by animateDpAsState(
        targetValue = if (isFocused) 24.dp else 10.dp,
        animationSpec = tween(durationMillis = 180),
        label = "watchedIconEndPadding"
    )
    val strEpisode = stringResource(R.string.episodes_episode)
    val episodeCode = remember(episode.season, episode.episode) {
        if (episode.season != null && episode.episode != null) {
            "S${episode.season.toString().padStart(2, '0')}E${episode.episode.toString().padStart(2, '0')}"
        } else {
            strEpisode
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val titleMedium = MaterialTheme.typography.titleMedium
    val titleSmall = MaterialTheme.typography.titleSmall
    val labelSmall = MaterialTheme.typography.labelSmall
    val bodySmall = MaterialTheme.typography.bodySmall
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "episodeOverlayAlpha"
    )
    val shouldRenderOverlay = overlayAlpha > 0.01f
    val episodeCodeTextStyle = remember(titleMedium) {
        titleMedium.copy(
            shadow = Shadow(
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f),
                offset = Offset(0f, 0f),
                blurRadius = 5f
            )
        )
    }
    val thumbnailWidthContentPx = remember(density) {
        with(density) { (280.dp - 20.dp).roundToPx() } // card width minus horizontal padding
    }
    val overlayLayouts = remember(
        shouldRenderOverlay,
        episode.title,
        episode.overview,
        formattedDate,
        episode.runtime,
        titleSmall,
        labelSmall,
        bodySmall,
        thumbnailWidthContentPx
    ) {
        if (!shouldRenderOverlay) {
            null
        } else {
            val metaStyle = labelSmall.copy(color = Color.White.copy(alpha = 0.75f))
            val dateLayout = if (formattedDate.isNotBlank()) textMeasurer.measure(
                text = formattedDate,
                style = metaStyle,
                constraints = Constraints(maxWidth = thumbnailWidthContentPx),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            ) else null
            val runtimeLayout = episode.runtime?.let {
                textMeasurer.measure(
                    text = "${it}m",
                    style = metaStyle,
                    maxLines = 1
                )
            }
            val titleLayout = textMeasurer.measure(
                text = episode.title,
                style = titleSmall.copy(color = Color.White),
                constraints = Constraints(maxWidth = thumbnailWidthContentPx),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val overviewLayout = episode.overview?.let {
                textMeasurer.measure(
                    text = it,
                    style = bodySmall.copy(color = Color.White.copy(alpha = 0.85f)),
                    constraints = Constraints(maxWidth = thumbnailWidthContentPx),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OverlayLayouts(dateLayout, runtimeLayout, titleLayout, overviewLayout)
        }
    }
    val overlayHeightDp = remember(overlayLayouts, density) {
        val layouts = overlayLayouts ?: return@remember 0.dp
        with(density) {
            val lineSpacingPx = 3.dp.roundToPx()
            var h = 0
            if (layouts.date != null || layouts.runtime != null) {
                h += maxOf(layouts.date?.size?.height ?: 0, layouts.runtime?.size?.height ?: 0) + lineSpacingPx
            }
            h += layouts.title.size.height
            layouts.overview?.let { h += lineSpacingPx + it.size.height }
            h.toDp() + 14.dp // top + bottom padding
        }
    }
    val overlayBrush = remember {
        Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.35f to Color.Black.copy(alpha = 0.55f),
            1.0f to Color.Black.copy(alpha = 0.82f)
        )
    }
    val thumbnailWidthPx = remember(thumbnailWidth, density) {
        with(density) { thumbnailWidth.roundToPx() }
    }
    val thumbnailHeightPx = remember(density) {
        with(density) { 158.dp.roundToPx() }
    }
    val thumbnailRequest = remember(context, episode.thumbnail, thumbnailWidthPx, thumbnailHeightPx, shouldBlur) {
        ImageRequest.Builder(context)
            .data(episode.thumbnail)
            .crossfade(false)
            .size(width = thumbnailWidthPx, height = thumbnailHeightPx)
            .apply {
                if (shouldBlur) {
                    transformations(com.nuvio.tv.ui.util.BlurTransformation())
                }
            }
            .build()
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
            .width(cardWidth)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocusRestored?.invoke()
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
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
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isSelectKey(native.keyCode)
                ) {
                    return@onPreviewKeyEvent true
                }
                false
            }
            .focusProperties {
                up = upFocusRequester
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            },
        shape = CardDefaults.shape(
            shape = RoundedCornerShape(8.dp)
        ),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.0f)
    ) {
        Box(
            modifier = Modifier
                .width(thumbnailWidth)
                .height(158.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
                AsyncImage(
                    model = thumbnailRequest,
                    contentDescription = episode.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                val episodeCodeLayout = remember(episodeCode, episodeCodeTextStyle) {
                    textMeasurer.measure(
                        text = episodeCode,
                        style = episodeCodeTextStyle,
                        maxLines = 1
                    )
                }
                Canvas(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .width(with(density) { (episodeCodeLayout.size.width + 16.dp.roundToPx()).toDp() })
                        .height(with(density) { (episodeCodeLayout.size.height + 6.dp.roundToPx()).toDp() })
                ) {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.62f),
                        size = size,
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = episodeCodeLayout,
                        topLeft = Offset(8.dp.toPx(), 3.dp.toPx()),
                        color = NuvioColors.TextPrimary
                    )
                }

                // Watched indicator
                if (watchProgress?.isCompleted() == true || isMarkedWatched) {
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
                            tint = androidx.compose.ui.graphics.Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                // Progress bar overlay at bottom of thumbnail
                watchProgress?.let { progress ->
                    if (progress.isInProgress()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(NuvioColors.Background.copy(alpha = 0.5f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.progressPercentage)
                                    .height(4.dp)
                                    .background(NuvioColors.Primary)
                            )
                        }
                    }
                }

                val resolvedOverlayLayouts = overlayLayouts
                if (shouldRenderOverlay && resolvedOverlayLayouts != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(overlayAlpha)
                            .background(overlayBrush)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(overlayHeightDp)
                            .alpha(overlayAlpha)
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)
                            .drawWithCache {
                                val lineSpacing = 3.dp.toPx()
                                onDrawBehind {
                                    var y = 0f
                                    if (resolvedOverlayLayouts.date != null || resolvedOverlayLayouts.runtime != null) {
                                        resolvedOverlayLayouts.date?.let { drawText(it, topLeft = Offset(0f, y)) }
                                        resolvedOverlayLayouts.runtime?.let {
                                            drawText(it, topLeft = Offset(size.width - it.size.width, y))
                                        }
                                        val metaHeight = maxOf(
                                            resolvedOverlayLayouts.date?.size?.height ?: 0,
                                            resolvedOverlayLayouts.runtime?.size?.height ?: 0
                                        ).toFloat()
                                        y += metaHeight + lineSpacing
                                    }
                                    drawText(resolvedOverlayLayouts.title, topLeft = Offset(0f, y))
                                    resolvedOverlayLayouts.overview?.let {
                                        y += resolvedOverlayLayouts.title.size.height + lineSpacing
                                        drawText(it, topLeft = Offset(0f, y))
                                    }
                                }
                            }
                    )
                }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeOptionsDialog(
    episode: Video,
    isWatched: Boolean,
    isPending: Boolean,
    isSeasonFullyWatched: Boolean = false,
    hasPreviousEpisodes: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onMarkSeasonWatched: () -> Unit = {},
    onMarkSeasonUnwatched: () -> Unit = {},
    onMarkPreviousEpisodesWatched: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = episode.title,
        subtitle = stringResource(R.string.episodes_dialog_subtitle)
    ) {
        Button(
            onClick = onToggleWatched,
            enabled = !isPending,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (isWatched) stringResource(R.string.episodes_mark_unwatched) else stringResource(R.string.episodes_mark_watched))
        }

        Button(
            onClick = if (isSeasonFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSeasonFullyWatched) stringResource(R.string.episodes_mark_season_unwatched) else stringResource(R.string.episodes_mark_season_watched))
        }

        if (hasPreviousEpisodes) {
            Button(
                onClick = onMarkPreviousEpisodesWatched,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.episodes_mark_previous_watched))
            }
        }

        Button(
            onClick = onPlay,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.episodes_play))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonOptionsDialog(
    season: Int,
    isFullyWatched: Boolean,
    onDismiss: () -> Unit,
    onMarkSeasonWatched: () -> Unit,
    onMarkSeasonUnwatched: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (season == 0) stringResource(R.string.episodes_specials) else stringResource(R.string.episodes_season, season),
        subtitle = stringResource(R.string.episodes_season_actions)
    ) {
        Button(
            onClick = if (isFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (isFullyWatched) stringResource(R.string.episodes_mark_season_unwatched) else stringResource(R.string.episodes_mark_season_watched))
        }
    }
}

private data class OverlayLayouts(
    val date: TextLayoutResult?,
    val runtime: TextLayoutResult?,
    val title: TextLayoutResult,
    val overview: TextLayoutResult?
)

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun episodePendingKey(video: Video): String {
    return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
}
