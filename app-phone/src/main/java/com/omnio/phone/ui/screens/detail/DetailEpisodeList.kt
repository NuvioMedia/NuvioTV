package com.omnio.phone.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.omnio.tv.domain.model.Video

@Composable
fun DetailEpisodeList(
    seasons: List<Int>,
    selectedSeason: Int?,
    episodes: List<Video>,
    onSelectSeason: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    modifier: Modifier = Modifier
) {
    if (seasons.isEmpty() || selectedSeason == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Episodes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        SeasonPicker(
            seasons = seasons,
            selected = selectedSeason,
            onSelect = onSelectSeason
        )
        if (episodes.isEmpty()) {
            Text(
                text = "No episodes available for this season.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                episodes.forEach { episode ->
                    EpisodeRow(episode = episode, onClick = { onEpisodeClick(episode) })
                }
            }
        }
    }
}

@Composable
private fun SeasonPicker(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = "Season $selected",
                style = MaterialTheme.typography.titleSmall
            )
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = { Text("Season $season") },
                    onClick = {
                        expanded = false
                        if (season != selected) onSelect(season)
                    }
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Video,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val thumb = episode.thumbnail
            if (!thumb.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumb)
                        .crossfade(true)
                        .build(),
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val episodeNumber = episode.episode?.let { "E$it" }
            val header = listOfNotNull(episodeNumber, episode.title.takeIf { it.isNotBlank() })
                .joinToString("  ·  ")
            Text(
                text = header.ifBlank { "Episode" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val footer = listOfNotNull(
                episode.released?.substringBefore('T')?.takeIf { it.isNotBlank() },
                episode.runtime?.let { "${it}m" }
            ).joinToString("  •  ")
            if (footer.isNotBlank()) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Box(modifier = Modifier.height(0.dp))
}
