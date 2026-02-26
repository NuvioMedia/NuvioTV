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
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.formatReleaseDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
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
                text = uiState.episodeStreamsError ?: "Failed to load streams",
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
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        uiState.episodesError != null -> {
            Text(
                text = uiState.episodesError ?: "Failed to load episodes",
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

                    Spacer(modifier = Modifier.height(12.dp))
                }

                LazyColumn(
                    state = episodesListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        items(sortedSeasons, key = { it }) { season ->
            val isSelected = selectedSeason == season
            var isFocused by remember { mutableStateOf(false) }

            Card(
                onClick = { onSeasonSelected(season) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedTabFocusRequester) else Modifier)
                    .onFocusChanged { isFocused = it.isFocused },
                shape = CardDefaults.shape(shape = RoundedCornerShape(24.dp)),
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) Color(0xFFF5F5F5) else NuvioColors.BackgroundCard,
                    focusedContainerColor = if (isSelected) Color.White else NuvioColors.Secondary
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(1.dp, if (isSelected) Color.Transparent else NuvioColors.Border),
                        shape = RoundedCornerShape(24.dp)
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(24.dp)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1.0f)
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isSelected -> Color.Black
                        isFocused -> NuvioColors.OnSecondary
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
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) }?.takeIf { it.isNotBlank() }
    }
    val episodeCode = remember(episode.season, episode.episode) {
        val s = episode.season
        val e = episode.episode
        if (s != null && e != null) {
            "S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}"
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
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
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
                    contentDescription = episode.title,
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

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(NuvioColors.Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Current",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Episode info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = episode.title.ifBlank { "Episode" },
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
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
