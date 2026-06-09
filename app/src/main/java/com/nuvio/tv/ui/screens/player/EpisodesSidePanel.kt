@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.formatReleaseDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpisodesSidePanel(
    uiState: PlayerUiState,
    episodesFocusRequester: FocusRequester,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onBackToEpisodes: () -> Unit,
    onReloadEpisodeStreams: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onEpisodeSelected: (Video) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(
        uiState.showEpisodeStreams
    ) {
        try {
            if (uiState.showEpisodeStreams) {
                streamsFocusRequester.requestFocus()
            } else {
                episodesFocusRequester.requestFocus()
            }
        } catch (_: Exception) {
            // Focus requester may not be ready yet
        }
    }

   
    LaunchedEffect(
        uiState.showEpisodeStreams,
        uiState.isLoadingEpisodeStreams,
        uiState.episodeFilteredStreams.isNotEmpty()
    ) {
        if (!uiState.showEpisodeStreams) return@LaunchedEffect
        if (uiState.isLoadingEpisodeStreams) return@LaunchedEffect
        if (uiState.episodeFilteredStreams.isEmpty()) return@LaunchedEffect
        runCatching { streamsFocusRequester.requestFocus() }
    }

    // Right panel only (scrim is handled in PlayerScreen)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.showEpisodeStreams) stringResource(R.string.episodes_panel_streams_title) else stringResource(R.string.episodes_panel_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioTheme.colors.TextPrimary
                    )

                    DialogButton(
                        text = stringResource(R.string.episodes_panel_close),
                        onClick = onClose,
                        isPrimary = false
                    )
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                if (uiState.showEpisodeStreams) {
                    EpisodeStreamsView(
                        uiState = uiState,
                        streamsFocusRequester = streamsFocusRequester,
                        onBackToEpisodes = onBackToEpisodes,
                        onReload = onReloadEpisodeStreams,
                        onAddonFilterSelected = onAddonFilterSelected,
                        onStreamSelected = onStreamSelected
                    )
                } else {
                    EpisodesListView(
                        uiState = uiState,
                        episodesFocusRequester = episodesFocusRequester,
                        onSeasonSelected = onSeasonSelected,
                        onEpisodeSelected = onEpisodeSelected
                    )
                }
            }
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeStreamsView(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onBackToEpisodes: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit
) {
    // Streams for selected episode
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DialogButton(
            text = stringResource(R.string.episodes_panel_back),
            onClick = onBackToEpisodes,
            isPrimary = false
        )
        DialogButton(
            text = stringResource(R.string.episodes_panel_reload),
            onClick = onReload,
            isPrimary = false
        )

        val season = uiState.episodeStreamsSeason
        val episode = uiState.episodeStreamsEpisode
        val title = uiState.episodeStreamsTitle
        Text(
            text = buildString {
                if (season != null && episode != null) append("S$season E$episode")
                if (!title.isNullOrBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(title)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

    AnimatedVisibility(
        visible = !uiState.isLoadingEpisodeStreams && uiState.episodeAvailableAddons.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(120))
    ) {
        AddonFilterChips(
            addons = uiState.episodeAvailableAddons,
            selectedAddon = uiState.episodeSelectedAddonFilter,
            onAddonSelected = onAddonFilterSelected
        )
    }

    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

    when {
        uiState.isLoadingEpisodeStreams -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NuvioTheme.spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        uiState.episodeStreamsError != null -> {
            Text(
                text = uiState.episodeStreamsError,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f)
            )
        }

        uiState.episodeFilteredStreams.isEmpty() -> {
            Text(
                text = stringResource(R.string.episodes_panel_no_streams),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(top = NuvioTheme.spacing.xs),
                modifier = Modifier.fillMaxHeight()
            ) {
                items(uiState.episodeFilteredStreams) { stream ->
                    StreamItem(
                        stream = stream,
                        focusRequester = streamsFocusRequester,
                        requestInitialFocus = stream == uiState.episodeFilteredStreams.firstOrNull(),
                        showFileSizeBadges = uiState.showFileSizeBadges,
                        badgePlacement = uiState.streamBadgePlacement,
                        onClick = { onStreamSelected(stream) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodesListView(
    uiState: PlayerUiState,
    episodesFocusRequester: FocusRequester,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Video) -> Unit
) {
    val seasonTabFocusRequester = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()
    val lastOpenedEpisodeIndex = remember(
        uiState.episodes,
        uiState.episodeStreamsForVideoId
    ) {
        val targetId = uiState.episodeStreamsForVideoId
        if (targetId.isNullOrBlank()) -1
        else uiState.episodes.indexOfFirst { it.id == targetId }
    }
    val currentEpisodeIndex = remember(uiState.episodes, uiState.currentSeason, uiState.currentEpisode) {
        uiState.episodes.indexOfFirst { episode ->
            episode.season == uiState.currentSeason && episode.episode == uiState.currentEpisode
        }
    }

    LaunchedEffect(uiState.showEpisodeStreams, uiState.episodes, currentEpisodeIndex) {
        if (uiState.showEpisodeStreams || uiState.episodes.isEmpty()) return@LaunchedEffect

        val targetIndex = when {
            lastOpenedEpisodeIndex >= 0 -> lastOpenedEpisodeIndex
            currentEpisodeIndex >= 0 -> currentEpisodeIndex
            else -> 0
        }
        runCatching {
            episodesListState.scrollToItem(targetIndex)
            delay(32)
            episodesFocusRequester.requestFocus()
        }
    }

    when {
        uiState.isLoadingEpisodes -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = NuvioTheme.spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        uiState.episodesError != null -> {
            Text(
                text = uiState.episodesError,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f)
            )
        }

        uiState.episodes.isEmpty() -> {
            Text(
                text = stringResource(R.string.episodes_panel_no_episodes),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        else -> {
            Column(modifier = Modifier.fillMaxHeight()) {
                if (uiState.episodesAvailableSeasons.isNotEmpty()) {
                    EpisodesSeasonTabs(
                        seasons = uiState.episodesAvailableSeasons,
                        selectedSeason = uiState.episodesSelectedSeason,
                        selectedTabFocusRequester = seasonTabFocusRequester,
                        onSeasonSelected = onSeasonSelected
                    )

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                }

                LazyColumn(
                    state = episodesListState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.xs),
                    modifier = Modifier
                        .fillMaxHeight()
                        .focusProperties { up = seasonTabFocusRequester }
                ) {
                    itemsIndexed(uiState.episodes) { index, episode ->
                        val isCurrent = episode.season == uiState.currentSeason &&
                            episode.episode == uiState.currentEpisode
                        val requestInitialFocus = when {
                            lastOpenedEpisodeIndex >= 0 -> index == lastOpenedEpisodeIndex
                            currentEpisodeIndex >= 0 -> isCurrent
                            else -> index == 0
                        }
                        val episodeKey = episode.season?.let { s ->
                            episode.episode?.let { e -> s to e }
                        }
                        val isWatched = episodeKey != null && (
                            uiState.episodeWatchProgressMap[episodeKey]?.isCompleted() == true ||
                            uiState.watchedEpisodeKeys.contains(episodeKey)
                        )
                        EpisodeItem(
                            episode = episode,
                            isCurrent = isCurrent,
                            isWatched = isWatched,
                            blurUnwatched = uiState.blurUnwatchedEpisodes,
                            focusRequester = episodesFocusRequester,
                            requestInitialFocus = requestInitialFocus,
                            onClick = { onEpisodeSelected(episode) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesSeasonTabs(
    seasons: List<Int>,
    selectedSeason: Int?,
    selectedTabFocusRequester: FocusRequester,
    onSeasonSelected: (Int) -> Unit
) {
    val sortedSeasons = remember(seasons) {
        val regular = seasons.filter { it > 0 }.sorted()
        val specials = seasons.filter { it == 0 }
        regular + specials
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xs)
    ) {
        items(sortedSeasons, key = { it }) { season ->
            val isSelected = selectedSeason == season
            var isFocused by remember { mutableStateOf(false) }

            Card(
                onClick = { onSeasonSelected(season) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedTabFocusRequester) else Modifier)
                    .onFocusChanged { isFocused = it.isFocused },
                shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.spacing.xl)),
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) Color(0xFFF5F5F5) else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = if (isSelected) Color.White else NuvioTheme.colors.Secondary
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, if (isSelected) Color.Transparent else NuvioTheme.colors.Border),
                        shape = RoundedCornerShape(NuvioTheme.spacing.xl)
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(NuvioTheme.spacing.xl)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1.0f)
            ) {
                Text(
                    text = if (season == 0) stringResource(R.string.episodes_specials) else stringResource(R.string.episodes_season, season),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isSelected -> Color.Black
                        isFocused -> NuvioTheme.colors.OnSecondary
                        else -> NuvioTheme.extendedColors.textSecondary
                    },
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun EpisodeItem(
    episode: Video,
    isCurrent: Boolean,
    isWatched: Boolean = false,
    blurUnwatched: Boolean = false,
    focusRequester: FocusRequester,
    requestInitialFocus: Boolean,
    onClick: () -> Unit
) {
    val shouldBlur = blurUnwatched && !isWatched && !isCurrent
    val context = LocalContext.current
    val episodeTitle = episode.title.localizeEpisodeTitle(context).ifBlank { context.getString(R.string.episodes_episode) }
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) }?.takeIf { it.isNotBlank() }
    }
    val episodeCode = remember(episode.season, episode.episode) {
        val s = episode.season
        val e = episode.episode
        if (s != null && e != null) {
            context.getString(R.string.season_episode_format, s, e)
        } else {
            null
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.xl)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.01f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.xl))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Thumbnail with episode badge
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(90.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.SurfaceVariant)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(episode.thumbnail)
                        .crossfade(true)
                        .apply {
                            if (shouldBlur) {
                                transformations(com.nuvio.tv.ui.util.BlurTransformation())
                            }
                        }
                        .build(),
                    contentDescription = episodeTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (episodeCode != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(NuvioTheme.spacing.sm)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                    ) {
                        Text(
                            text = episodeCode,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(NuvioTheme.colors.Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_current),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Episode info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (formattedDate != null) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textTertiary
                    )
                }

                episode.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
