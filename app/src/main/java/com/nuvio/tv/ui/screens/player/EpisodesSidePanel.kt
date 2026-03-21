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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.formatReleaseDate
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
            .width(760.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(NuvioColors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.showEpisodeStreams) stringResource(R.string.episodes_panel_streams_title) else stringResource(R.string.episodes_panel_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioColors.TextPrimary
                    )

                    DialogButton(
                        text = stringResource(R.string.episodes_panel_close),
                        onClick = onClose,
                        isPrimary = false
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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

    Spacer(modifier = Modifier.height(16.dp))

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

    Spacer(modifier = Modifier.height(16.dp))

    when {
        uiState.isLoadingEpisodeStreams -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                items(uiState.episodeFilteredStreams) { stream ->
                    StreamItem(
                        stream = stream,
                        focusRequester = streamsFocusRequester,
                        requestInitialFocus = stream == uiState.episodeFilteredStreams.firstOrNull(),
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
    val context = LocalContext.current
    val seasonRailFocusRequester = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()
    val seasonRailState = rememberLazyListState()
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
    val seasonCounts = remember(uiState.episodesAll) {
        uiState.episodesAll
            .mapNotNull { episode -> episode.season?.let { it to episode } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, episodes) -> episodes.size }
    }
    val seasonWatchedCounts = remember(
        uiState.episodesAll,
        uiState.episodeWatchProgressMap,
        uiState.watchedEpisodeKeys
    ) {
        uiState.episodesAll
            .groupBy { it.season }
            .mapNotNull { (season, episodes) ->
                season?.let { seasonNumber ->
                    seasonNumber to episodes.count { episode ->
                        val key = episode.episodeKey() ?: return@count false
                        uiState.episodeWatchProgressMap[key]?.isCompleted() == true ||
                            uiState.watchedEpisodeKeys.contains(key)
                    }
                }
            }
            .toMap()
    }
    val selectedSeasonEpisodeCount = remember(
        uiState.episodesSelectedSeason,
        uiState.episodes,
        seasonCounts
    ) {
        uiState.episodesSelectedSeason?.let { seasonCounts[it] } ?: uiState.episodes.size
    }
    val selectedSeasonWatchedCount = remember(
        uiState.episodesSelectedSeason,
        seasonWatchedCounts
    ) {
        uiState.episodesSelectedSeason?.let { seasonWatchedCounts[it] } ?: 0
    }
    val currentEpisodeVideo = remember(
        uiState.episodesAll,
        uiState.currentSeason,
        uiState.currentEpisode
    ) {
        uiState.episodesAll.firstOrNull { episode ->
            episode.season == uiState.currentSeason && episode.episode == uiState.currentEpisode
        }
    }
    val currentEpisodeCode = remember(uiState.currentSeason, uiState.currentEpisode) {
        formatEpisodeCode(uiState.currentSeason, uiState.currentEpisode)
    }
    val currentEpisodeTitle = remember(currentEpisodeVideo, uiState.currentEpisodeTitle, context) {
        (currentEpisodeVideo?.title ?: uiState.currentEpisodeTitle)
            ?.localizeEpisodeTitle(context)
            ?.takeIf { it.isNotBlank() }
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

    LaunchedEffect(uiState.episodesAvailableSeasons, uiState.episodesSelectedSeason) {
        val selectedSeason = uiState.episodesSelectedSeason ?: return@LaunchedEffect
        val seasonIndex = uiState.episodesAvailableSeasons
            .sortedWith(compareBy<Int> { if (it == 0) Int.MAX_VALUE else it })
            .indexOf(selectedSeason)
        if (seasonIndex >= 0) {
            runCatching { seasonRailState.scrollToItem(seasonIndex) }
        }
    }

    when {
        uiState.isLoadingEpisodes -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
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
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (uiState.episodesAvailableSeasons.isNotEmpty()) {
                    EpisodesSeasonRail(
                        seasons = uiState.episodesAvailableSeasons,
                        selectedSeason = uiState.episodesSelectedSeason,
                        currentSeason = uiState.currentSeason,
                        seasonCounts = seasonCounts,
                        seasonWatchedCounts = seasonWatchedCounts,
                        seasonRailState = seasonRailState,
                        selectedSeasonFocusRequester = seasonRailFocusRequester,
                        episodeFocusRequester = episodesFocusRequester,
                        onSeasonSelected = onSeasonSelected,
                        modifier = Modifier
                            .width(188.dp)
                            .fillMaxHeight()
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EpisodeSelectorSummaryCard(
                        selectedSeason = uiState.episodesSelectedSeason,
                        selectedSeasonEpisodeCount = selectedSeasonEpisodeCount,
                        selectedSeasonWatchedCount = selectedSeasonWatchedCount,
                        currentEpisodeCode = currentEpisodeCode,
                        currentEpisodeTitle = currentEpisodeTitle
                    )

                    LazyColumn(
                        state = episodesListState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 4.dp, end = 4.dp, bottom = 4.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        itemsIndexed(uiState.episodes) { index, episode ->
                            val isCurrent = episode.season == uiState.currentSeason &&
                                episode.episode == uiState.currentEpisode
                            val requestInitialFocus = when {
                                lastOpenedEpisodeIndex >= 0 -> index == lastOpenedEpisodeIndex
                                currentEpisodeIndex >= 0 -> isCurrent
                                else -> index == 0
                            }
                            val episodeKey = episode.episodeKey()
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
                                leftFocusRequester = seasonRailFocusRequester.takeIf {
                                    uiState.episodesAvailableSeasons.isNotEmpty()
                                },
                                onClick = { onEpisodeSelected(episode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesSeasonRail(
    seasons: List<Int>,
    selectedSeason: Int?,
    currentSeason: Int?,
    seasonCounts: Map<Int, Int>,
    seasonWatchedCounts: Map<Int, Int>,
    seasonRailState: androidx.compose.foundation.lazy.LazyListState,
    selectedSeasonFocusRequester: FocusRequester,
    episodeFocusRequester: FocusRequester,
    onSeasonSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedSeasons = remember(seasons) {
        val regular = seasons.filter { it > 0 }.sorted()
        val specials = seasons.filter { it == 0 }
        regular + specials
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NuvioColors.BackgroundCard)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.episodes_panel_seasons_title),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.extendedColors.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            state = seasonRailState,
            modifier = Modifier
                .fillMaxHeight()
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(sortedSeasons, key = { it }) { season ->
                SeasonRailItem(
                    season = season,
                    episodeCount = seasonCounts[season] ?: 0,
                    watchedCount = seasonWatchedCounts[season] ?: 0,
                    isSelected = selectedSeason == season,
                    isCurrentSeason = currentSeason == season,
                    selectedSeasonFocusRequester = selectedSeasonFocusRequester,
                    episodeFocusRequester = episodeFocusRequester,
                    onClick = { onSeasonSelected(season) }
                )
            }
        }
    }
}

@Composable
private fun EpisodeSelectorSummaryCard(
    selectedSeason: Int?,
    selectedSeasonEpisodeCount: Int,
    selectedSeasonWatchedCount: Int,
    currentEpisodeCode: String?,
    currentEpisodeTitle: String?
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NuvioColors.BackgroundCard)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.episodes_panel_title),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.extendedColors.textSecondary
        )

        Text(
            text = buildString {
                if (!currentEpisodeCode.isNullOrBlank()) {
                    append(currentEpisodeCode)
                }
                if (!currentEpisodeTitle.isNullOrBlank()) {
                    if (isNotEmpty()) append(" • ")
                    append(currentEpisodeTitle)
                }
                if (isEmpty() && selectedSeason != null) {
                    append(
                        if (selectedSeason == 0) {
                            context.getString(R.string.episodes_specials)
                        } else {
                            context.getString(R.string.episodes_season, selectedSeason)
                        }
                    )
                }
            },
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            selectedSeason?.let { season ->
                EpisodeMetaChip(
                    text = if (season == 0) {
                        stringResource(R.string.episodes_specials)
                    } else {
                        stringResource(R.string.episodes_season, season)
                    },
                    containerColor = NuvioColors.Secondary.copy(alpha = 0.16f),
                    contentColor = NuvioColors.OnSecondaryVariant
                )
            }

            EpisodeMetaChip(
                text = stringResource(R.string.episodes_panel_episode_count, selectedSeasonEpisodeCount),
                containerColor = Color.White.copy(alpha = 0.08f),
                contentColor = NuvioColors.TextPrimary
            )

            EpisodeMetaChip(
                text = stringResource(R.string.episodes_panel_watched_count, selectedSeasonWatchedCount),
                containerColor = NuvioColors.Primary.copy(alpha = 0.16f),
                contentColor = NuvioColors.TextPrimary
            )
        }

        Text(
            text = stringResource(R.string.episodes_panel_pick_episode),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.extendedColors.textSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonRailItem(
    season: Int,
    episodeCount: Int,
    watchedCount: Int,
    isSelected: Boolean,
    isCurrentSeason: Boolean,
    selectedSeasonFocusRequester: FocusRequester,
    episodeFocusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val progressFraction = remember(watchedCount, episodeCount) {
        if (episodeCount > 0) watchedCount.toFloat() / episodeCount.toFloat() else 0f
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelected) Modifier.focusRequester(selectedSeasonFocusRequester) else Modifier)
            .focusProperties { right = episodeFocusRequester }
            .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.18f) else NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (isSelected) NuvioColors.Secondary.copy(alpha = 0.45f) else NuvioColors.Border
                ),
                shape = shape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (season == 0) stringResource(R.string.episodes_specials) else stringResource(R.string.episodes_season, season),
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    isSelected -> NuvioColors.TextPrimary
                    isFocused -> NuvioColors.OnSecondary
                    else -> NuvioColors.TextPrimary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = stringResource(R.string.episodes_panel_episode_count, episodeCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    NuvioColors.TextPrimary.copy(alpha = 0.78f)
                } else {
                    NuvioTheme.extendedColors.textSecondary
                }
            )

            SeasonProgressBar(
                progressFraction = progressFraction,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.episodes_panel_watched_count, watchedCount),
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    NuvioColors.TextPrimary.copy(alpha = 0.82f)
                } else {
                    NuvioTheme.extendedColors.textTertiary
                }
            )

            if (isCurrentSeason) {
                EpisodeMetaChip(
                    text = stringResource(R.string.episodes_panel_now_playing),
                    containerColor = if (isSelected) {
                        Color.Black.copy(alpha = 0.16f)
                    } else {
                        NuvioColors.Secondary.copy(alpha = 0.16f)
                    },
                    contentColor = if (isSelected) {
                        NuvioColors.TextPrimary
                    } else {
                        NuvioColors.OnSecondaryVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SeasonProgressBar(
    progressFraction: Float,
    modifier: Modifier = Modifier
) {
    val fraction = progressFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(NuvioColors.Secondary)
            )
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
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val shouldBlur = blurUnwatched && !isWatched && !isCurrent
    val context = LocalContext.current
    val episodeTitle = episode.title.localizeEpisodeTitle(context).ifBlank { context.getString(R.string.episodes_episode) }
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) }?.takeIf { it.isNotBlank() }
    }
    val episodeCode = remember(episode.season, episode.episode) {
        formatEpisodeCode(episode.season, episode.episode)
    }
    val isUnavailable = episode.available == false

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (leftFocusRequester != null) Modifier.focusProperties { left = leftFocusRequester } else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    when {
                        isCurrent -> NuvioColors.Secondary.copy(alpha = 0.35f)
                        isWatched -> NuvioColors.Primary.copy(alpha = 0.25f)
                        else -> Color.Transparent
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.01f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(16.dp))
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(NuvioColors.SurfaceVariant)
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
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = episodeCode,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }

            // Episode info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isCurrent) {
                            EpisodeMetaChip(
                                text = stringResource(R.string.episodes_panel_now_playing),
                                containerColor = NuvioColors.Secondary.copy(alpha = 0.16f),
                                contentColor = NuvioColors.OnSecondaryVariant
                            )
                        } else if (isWatched) {
                            EpisodeMetaChip(
                                text = stringResource(R.string.episodes_cd_watched),
                                containerColor = NuvioColors.Primary.copy(alpha = 0.16f),
                                contentColor = NuvioColors.TextPrimary
                            )
                        }

                        if (isUnavailable) {
                            EpisodeMetaChip(
                                text = stringResource(R.string.episodes_unavailable),
                                containerColor = NuvioColors.Error.copy(alpha = 0.16f),
                                contentColor = NuvioColors.Error
                            )
                        }
                    }
                }

                if (episodeCode != null || formattedDate != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        episodeCode?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.extendedColors.textSecondary
                            )
                        }

                        formattedDate?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.extendedColors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
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

@Composable
private fun EpisodeMetaChip(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Video.episodeKey(): Pair<Int, Int>? {
    val seasonNumber = season ?: return null
    val episodeNumber = episode ?: return null
    return seasonNumber to episodeNumber
}

private fun formatEpisodeCode(season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}
