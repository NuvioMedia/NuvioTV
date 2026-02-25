package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodeRatingsSection(
    episodes: List<Video>,
    ratings: Map<Pair<Int, Int>, Double>,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    title: String = "Ratings",
    upFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null
) {
    val seasonNumbers = remember(episodes) {
        episodes
            .mapNotNull { it.season }
            .filter { it > 0 } // Never show specials season (S0)
            .distinct()
            .sorted()
    }
    val seasonSignature = remember(seasonNumbers) { seasonNumbers.joinToString(",") }
    val seasonFocusRequesters = remember(seasonNumbers) {
        seasonNumbers.associateWith { FocusRequester() }
    }
    val defaultSeason = remember(seasonNumbers) {
        seasonNumbers.firstOrNull { it > 0 } ?: seasonNumbers.firstOrNull() ?: 0
    }
    var selectedSeason by rememberSaveable(seasonSignature) {
        mutableIntStateOf(defaultSeason)
    }

    LaunchedEffect(seasonNumbers, defaultSeason) {
        if (selectedSeason !in seasonNumbers) {
            selectedSeason = defaultSeason
        }
    }

    val episodesForSeason = remember(episodes, selectedSeason) {
        episodes
            .filter { it.season == selectedSeason && it.episode != null }
            .sortedBy { it.episode }
    }
    val defaultChipColor = NuvioColors.BackgroundCard
    val defaultChipTextColor = NuvioColors.TextSecondary
    val seasonRatings = remember(episodesForSeason, ratings) {
        episodesForSeason.mapNotNull { episode ->
            val season = episode.season ?: return@mapNotNull null
            val episodeNumber = episode.episode ?: return@mapNotNull null
            val rating = ratings[season to episodeNumber]
            val ratingText = rating?.let { String.format("%.1f", it) } ?: "—"
            val chipColor = rating?.let(::ratingColor) ?: defaultChipColor
            val chipTextColor = rating?.let(::ratingTextColor) ?: defaultChipTextColor
            EpisodeRatingChipUi(
                id = episode.id,
                episodeNumber = episodeNumber,
                ratingText = ratingText,
                chipColor = chipColor,
                chipTextColor = chipTextColor
            )
        }
    }
    val hasTitle = title.isNotBlank()
    val upFocusModifier = if (upFocusRequester != null) {
        Modifier.focusProperties { up = upFocusRequester }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (hasTitle) 14.dp else 6.dp, bottom = 8.dp)
    ) {
        if (hasTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 48.dp)
            )
        }

        when {
            isLoading -> {
                Text(
                    text = stringResource(R.string.ratings_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }
            !error.isNullOrBlank() -> {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }
            seasonNumbers.isEmpty() -> {
                Text(
                    text = stringResource(R.string.ratings_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                )
            }
            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonNumbers, key = { it }) { season ->
                        val isSelected = season == selectedSeason
                        val modifierWithRequester = if (firstItemFocusRequester != null && season == selectedSeason) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier.focusRequester(seasonFocusRequesters.getValue(season))
                        }

                        Card(
                            onClick = { selectedSeason = season },
                            modifier = modifierWithRequester
                                .then(upFocusModifier)
                                .onFocusChanged { state ->
                                    if (state.isFocused && selectedSeason != season) {
                                        selectedSeason = season
                                    }
                                },
                            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) {
                                    NuvioColors.FocusBackground
                                } else {
                                    NuvioColors.BackgroundCard
                                },
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(
                                text = "S$season",
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.ratings_season_summary, selectedSeason, episodesForSeason.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 2.dp)
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seasonRatings, key = { it.id }) { episodeRating ->
                        val selectedSeasonUpRequester = firstItemFocusRequester ?: seasonFocusRequesters[selectedSeason]

                        Card(
                            onClick = { },
                            modifier = if (selectedSeasonUpRequester != null) {
                                Modifier.focusProperties { up = selectedSeasonUpRequester }
                            } else {
                                Modifier
                            },
                            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                            colors = CardDefaults.colors(
                                containerColor = episodeRating.chipColor,
                                focusedContainerColor = episodeRating.chipColor
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            ),
                            scale = CardDefaults.scale(focusedScale = 1.03f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 46.dp)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "E${episodeRating.episodeNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = episodeRating.chipTextColor
                                )
                                Text(
                                    text = episodeRating.ratingText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = episodeRating.chipTextColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ratingColor(value: Double): androidx.compose.ui.graphics.Color {
    return when {
        value >= 9.0 -> androidx.compose.ui.graphics.Color(0xFF186A3B)
        value >= 8.0 -> androidx.compose.ui.graphics.Color(0xFF28B463)
        value >= 7.5 -> androidx.compose.ui.graphics.Color(0xFFF4D03F)
        value >= 7.0 -> androidx.compose.ui.graphics.Color(0xFFF39C12)
        value >= 6.0 -> androidx.compose.ui.graphics.Color(0xFFE74C3C)
        else -> androidx.compose.ui.graphics.Color(0xFF633974)
    }
}

private fun ratingTextColor(value: Double): Color {
    return when {
        value >= 7.0 && value < 8.0 -> Color(0xFF1D1D1F)
        else -> Color.White
    }
}

private data class EpisodeRatingChipUi(
    val id: String,
    val episodeNumber: Int,
    val ratingText: String,
    val chipColor: Color,
    val chipTextColor: Color
)
