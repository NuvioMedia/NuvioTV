package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.CalendarRecommendation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CalendarScreen(
    onEpisodeClick: (CalendarEpisode) -> Unit,
    onRecommendationClick: (CalendarRecommendation) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!)
                Text("Try again", Modifier.padding(top = 16.dp).clickable { viewModel.refresh() }, color = MaterialTheme.colorScheme.primary)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 56.dp, end = 32.dp, top = 32.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    Text("Calendar", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("New episodes from shows in your library", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
                }
                if (state.days.isEmpty()) item { Text("No announced episodes in the next 90 days.") }
                items(state.days, key = { it.date.toString() }) { day ->
                    Text(day.date.calendarLabel(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(day.episodes, key = { it.videoId }) { EpisodeCard(it) { onEpisodeClick(it) } }
                    }
                }
                if (state.recommendations.isNotEmpty()) {
                    item { Text("Because it’s in your library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(state.recommendations, key = { it.item.id }) { recommendation ->
                                RecommendationCard(recommendation) { onRecommendationClick(recommendation) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: CalendarEpisode, onClick: () -> Unit) {
    Column(Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(bottom = 10.dp)) {
        AsyncImage(episode.stillUrl ?: episode.background ?: episode.poster, episode.episodeName, Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Text(episode.showName, Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("S${episode.seasonNumber} E${episode.episodeNumber}  •  ${episode.episodeName}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
    }
}

@Composable
private fun RecommendationCard(recommendation: CalendarRecommendation, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(recommendation.item.poster, recommendation.item.name, Modifier.fillMaxWidth().height(225.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Text(recommendation.item.name, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Like ${recommendation.recommendedBy.firstOrNull().orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
    }
}

private fun LocalDate.calendarLabel(): String = when (this) {
    LocalDate.now() -> "Today"
    LocalDate.now().plusDays(1) -> "Tomorrow"
    else -> format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
}
