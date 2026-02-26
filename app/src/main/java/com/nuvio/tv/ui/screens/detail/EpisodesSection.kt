package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val EPISODE_CARD_CONTENT_TYPE = "episode_card"

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
    episodeRatings: Map<Pair<Int, Int>, Double> = emptyMap(),
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
    val cardMetrics = rememberEpisodeCardMetrics()

    LaunchedEffect(restoreFocusToken, restoreEpisodeId, episodes) {
        if (restoreFocusToken <= 0 || restoreEpisodeId.isNullOrBlank()) return@LaunchedEffect
        if (episodes.none { it.id == restoreEpisodeId }) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = cardMetrics.rowHorizontalPadding,
            vertical = cardMetrics.rowVerticalPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(cardMetrics.itemSpacing)
    ) {
        items(
            items = episodes,
            key = { it.id },
            contentType = { EPISODE_CARD_CONTENT_TYPE }
        ) { episode ->
            val progress = episode.season?.let { s ->
                episode.episode?.let { e ->
                    episodeProgressMap[s to e]
                }
            }
            val imdbRating = episode.season?.let { s ->
                episode.episode?.let { e ->
                    episodeRatings[s to e]
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
                imdbRating = imdbRating,
                isMarkedWatched = isMarkedWatched,
                blurUnwatched = blurUnwatchedEpisodes,
                cardMetrics = cardMetrics,
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
    imdbRating: Double? = null,
    isMarkedWatched: Boolean = false,
    blurUnwatched: Boolean = false,
    cardMetrics: EpisodeCardMetrics,
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
        episode.released?.let(::formatEpisodeCardDate).orEmpty()
    }
    val runtimeLabel = remember(episode.runtime) {
        episode.runtime?.takeIf { it > 0 }?.let(::formatEpisodeRuntime)
    }
    val ratingLabel = remember(imdbRating) {
        imdbRating?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
    }
    val description = remember(episode.overview) { episode.overview?.trim().orEmpty() }
    val isWatched = watchProgress?.isCompleted() == true || isMarkedWatched
    val shouldBlur = blurUnwatched && !isWatched
    val progressPercent = watchProgress?.progressPercentage ?: 0f
    val showProgress = progressPercent >= 0.02f && progressPercent < 0.85f
    val showCompletedBadge = isWatched
    val showNotStartedBadge = !showCompletedBadge && progressPercent < 0.02f
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val shape = remember(cardMetrics.cornerRadius) { RoundedCornerShape(cardMetrics.cornerRadius) }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.94f,
        animationSpec = tween(durationMillis = 180),
        label = "episodeOverlayAlpha"
    )
    val thumbnailWidthPx = remember(cardMetrics.cardWidth, density) {
        with(density) { cardMetrics.cardWidth.roundToPx() }
    }
    val thumbnailHeightPx = remember(cardMetrics.cardHeight, density) {
        with(density) { cardMetrics.cardHeight.roundToPx() }
    }
    val overlayBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Black.copy(alpha = 0.06f),
                0.22f to Color.Black.copy(alpha = 0.18f),
                0.52f to Color.Black.copy(alpha = 0.62f),
                0.82f to Color.Black.copy(alpha = 0.86f),
                1.0f to Color.Black.copy(alpha = 0.95f)
            )
        )
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
    val imdbLogoRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.imdb_logo_2016)
            .crossfade(false)
            .build()
    }
    val strEpisode = stringResource(R.string.episodes_episode)
    val episodeCode = remember(episode.episode, strEpisode) {
        val prefix = strEpisode.uppercase(Locale.getDefault())
        episode.episode?.let { number -> "$prefix $number" } ?: prefix
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
            .width(cardMetrics.cardWidth)
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
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Box(
            modifier = Modifier
                .width(cardMetrics.cardWidth)
                .height(cardMetrics.cardHeight)
                .clip(shape)
                .background(NuvioColors.BackgroundCard)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.14f),
                    shape = shape
                )
        ) {
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = episode.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(overlayAlpha)
                    .background(overlayBrush)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = cardMetrics.contentPadding,
                        end = cardMetrics.contentPadding,
                        top = cardMetrics.contentPadding,
                        bottom = cardMetrics.contentBottomPadding
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.42f),
                            shape = RoundedCornerShape(cardMetrics.episodeBadgeCornerRadius)
                        )
                        .padding(
                            horizontal = cardMetrics.episodeBadgeHorizontalPadding,
                            vertical = cardMetrics.episodeBadgeVerticalPadding
                        )
                ) {
                    Text(
                        text = episodeCode,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = cardMetrics.episodeBadgeLetterSpacing,
                            color = Color.White.copy(alpha = 0.9f)
                        ),
                        maxLines = 1
                    )
                }

                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = cardMetrics.titleLineHeight
                    ),
                    color = NuvioColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.9f),
                            lineHeight = cardMetrics.descriptionLineHeight
                        ),
                        maxLines = cardMetrics.descriptionMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (runtimeLabel != null || ratingLabel != null || formattedDate.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        runtimeLabel?.let { runtime ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = NuvioColors.TextSecondary,
                                    modifier = Modifier.size(cardMetrics.metadataIconSize)
                                )
                                Text(
                                    text = runtime,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = NuvioColors.TextSecondary
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        ratingLabel?.let { rating ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = imdbLogoRequest,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(cardMetrics.imdbLogoWidth)
                                        .height(cardMetrics.imdbLogoHeight),
                                    contentScale = ContentScale.Fit
                                )
                                Text(
                                    text = rating,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color(0xFFF5C518),
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        if (formattedDate.isNotBlank()) {
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelSmall.copy(color = NuvioColors.TextSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (showProgress) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(cardMetrics.progressBarHeight)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPercent)
                            .height(cardMetrics.progressBarHeight)
                            .background(NuvioColors.Primary)
                    )
                }
            }

            if (showCompletedBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = cardMetrics.statusBadgeInset,
                            top = cardMetrics.statusBadgeInset
                        )
                        .size(cardMetrics.statusBadgeSize)
                        .background(NuvioColors.Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.episodes_cd_watched),
                        tint = Color.White,
                        modifier = Modifier.size(cardMetrics.statusIconSize)
                    )
                }
            } else if (showNotStartedBadge) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = cardMetrics.statusBadgeInset,
                            top = cardMetrics.statusBadgeInset
                        )
                        .size(cardMetrics.statusBadgeSize)
                ) {
                    drawCircle(
                        color = NuvioColors.TextSecondary.copy(alpha = 0.9f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
                        )
                    )
                }
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

private data class EpisodeCardMetrics(
    val rowHorizontalPadding: Dp,
    val rowVerticalPadding: Dp,
    val itemSpacing: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val contentBottomPadding: Dp,
    val episodeBadgeHorizontalPadding: Dp,
    val episodeBadgeVerticalPadding: Dp,
    val episodeBadgeCornerRadius: Dp,
    val episodeBadgeLetterSpacing: androidx.compose.ui.unit.TextUnit,
    val titleLineHeight: androidx.compose.ui.unit.TextUnit,
    val descriptionLineHeight: androidx.compose.ui.unit.TextUnit,
    val descriptionMaxLines: Int,
    val metadataIconSize: Dp,
    val imdbLogoWidth: Dp,
    val imdbLogoHeight: Dp,
    val progressBarHeight: Dp,
    val statusBadgeSize: Dp,
    val statusIconSize: Dp,
    val statusBadgeInset: Dp
)

@Composable
private fun rememberEpisodeCardMetrics(): EpisodeCardMetrics {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(screenWidthDp) {
        when {
            screenWidthDp >= 1300 -> EpisodeCardMetrics(
                rowHorizontalPadding = 56.dp,
                rowVerticalPadding = 18.dp,
                itemSpacing = 20.dp,
                cardWidth = 400.dp,
                cardHeight = 280.dp,
                cornerRadius = 20.dp,
                contentPadding = 20.dp,
                contentBottomPadding = 24.dp,
                episodeBadgeHorizontalPadding = 10.dp,
                episodeBadgeVerticalPadding = 5.dp,
                episodeBadgeCornerRadius = 8.dp,
                episodeBadgeLetterSpacing = 1.0.sp,
                titleLineHeight = 28.sp,
                descriptionLineHeight = 22.sp,
                descriptionMaxLines = 4,
                metadataIconSize = 16.dp,
                imdbLogoWidth = 28.dp,
                imdbLogoHeight = 14.dp,
                progressBarHeight = 3.dp,
                statusBadgeSize = 32.dp,
                statusIconSize = 20.dp,
                statusBadgeInset = 16.dp
            )

            screenWidthDp >= 1000 -> EpisodeCardMetrics(
                rowHorizontalPadding = 52.dp,
                rowVerticalPadding = 18.dp,
                itemSpacing = 18.dp,
                cardWidth = 360.dp,
                cardHeight = 250.dp,
                cornerRadius = 18.dp,
                contentPadding = 18.dp,
                contentBottomPadding = 22.dp,
                episodeBadgeHorizontalPadding = 9.dp,
                episodeBadgeVerticalPadding = 4.dp,
                episodeBadgeCornerRadius = 7.dp,
                episodeBadgeLetterSpacing = 0.9.sp,
                titleLineHeight = 25.sp,
                descriptionLineHeight = 20.sp,
                descriptionMaxLines = 4,
                metadataIconSize = 15.dp,
                imdbLogoWidth = 26.dp,
                imdbLogoHeight = 13.dp,
                progressBarHeight = 3.dp,
                statusBadgeSize = 28.dp,
                statusIconSize = 18.dp,
                statusBadgeInset = 14.dp
            )

            screenWidthDp >= 760 -> EpisodeCardMetrics(
                rowHorizontalPadding = 48.dp,
                rowVerticalPadding = 16.dp,
                itemSpacing = 16.dp,
                cardWidth = 320.dp,
                cardHeight = 220.dp,
                cornerRadius = 16.dp,
                contentPadding = 16.dp,
                contentBottomPadding = 20.dp,
                episodeBadgeHorizontalPadding = 8.dp,
                episodeBadgeVerticalPadding = 4.dp,
                episodeBadgeCornerRadius = 6.dp,
                episodeBadgeLetterSpacing = 0.9.sp,
                titleLineHeight = 22.sp,
                descriptionLineHeight = 18.sp,
                descriptionMaxLines = 3,
                metadataIconSize = 14.dp,
                imdbLogoWidth = 24.dp,
                imdbLogoHeight = 12.dp,
                progressBarHeight = 3.dp,
                statusBadgeSize = 24.dp,
                statusIconSize = 16.dp,
                statusBadgeInset = 12.dp
            )

            else -> EpisodeCardMetrics(
                rowHorizontalPadding = 32.dp,
                rowVerticalPadding = 14.dp,
                itemSpacing = 14.dp,
                cardWidth = 280.dp,
                cardHeight = 190.dp,
                cornerRadius = 16.dp,
                contentPadding = 14.dp,
                contentBottomPadding = 16.dp,
                episodeBadgeHorizontalPadding = 7.dp,
                episodeBadgeVerticalPadding = 3.dp,
                episodeBadgeCornerRadius = 5.dp,
                episodeBadgeLetterSpacing = 0.8.sp,
                titleLineHeight = 19.sp,
                descriptionLineHeight = 16.sp,
                descriptionMaxLines = 3,
                metadataIconSize = 13.dp,
                imdbLogoWidth = 22.dp,
                imdbLogoHeight = 11.dp,
                progressBarHeight = 3.dp,
                statusBadgeSize = 22.dp,
                statusIconSize = 14.dp,
                statusBadgeInset = 10.dp
            )
        }
    }
}

private fun formatEpisodeRuntime(runtimeMinutes: Int): String {
    if (runtimeMinutes <= 0) return ""
    val hours = runtimeMinutes / 60
    val minutes = runtimeMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatEpisodeCardDate(isoDate: String): String {
    val locale = Locale.getDefault()
    val outputFormat = SimpleDateFormat("MMM d, yyyy", locale)
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = inputFormat.parse(isoDate)
        date?.let { outputFormat.format(it) }.orEmpty()
    } catch (_: Exception) {
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = inputFormat.parse(isoDate)
            date?.let { outputFormat.format(it) }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun episodePendingKey(video: Video): String {
    return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
}
