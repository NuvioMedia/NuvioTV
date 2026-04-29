package com.omnio.phone.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnio.tv.domain.model.AddonStreams
import com.omnio.tv.domain.model.Stream

private val QUALITY_REGEX = Regex(
    "\\b(4K|2160p|1440p|1080p|720p|480p|HDR10\\+|HDR10|HDR|DV|Dolby Vision|HEVC|H\\.265|x265|AV1|REMUX|BluRay|BLU-RAY|WEB-DL|WEBRip)\\b",
    RegexOption.IGNORE_CASE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamSelectionSheet(
    state: StreamSelectionState,
    onDismiss: () -> Unit,
    onSelectStream: (Stream) -> Unit,
    onSetAddonFilter: (String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SheetTitle()
        when {
            state.isLoading -> LoadingRow()
            state.groups.isEmpty() -> EmptyRow(
                message = state.error ?: "No sources found for this title."
            )
            else -> StreamListContent(
                groups = state.groups,
                addonFilter = state.addonFilter,
                onSetAddonFilter = onSetAddonFilter,
                onSelectStream = onSelectStream
            )
        }
    }
}

@Composable
private fun SheetTitle() {
    Text(
        text = "Choose source",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun StreamListContent(
    groups: List<AddonStreams>,
    addonFilter: String?,
    onSetAddonFilter: (String?) -> Unit,
    onSelectStream: (Stream) -> Unit
) {
    val visible = if (addonFilter == null) groups else groups.filter { it.addonName == addonFilter }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "filter-row") {
            AddonFilterRow(
                groups = groups,
                selected = addonFilter,
                onSelect = onSetAddonFilter
            )
        }
        visible.forEach { group ->
            if (addonFilter == null) {
                item(key = "header-${group.addonName}") {
                    AddonHeader(name = group.addonName, count = group.streams.size)
                }
            }
            items(
                items = group.streams,
                key = { stream ->
                    "${group.addonName}|${stream.getStreamUrl() ?: stream.getDisplayName()}|${stream.hashCode()}"
                }
            ) { stream ->
                StreamRow(
                    stream = stream,
                    onClick = { onSelectStream(stream) }
                )
            }
        }
    }
}

@Composable
private fun AddonFilterRow(
    groups: List<AddonStreams>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        item("all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = {
                    val total = groups.sumOf { it.streams.size }
                    Text("All ($total)")
                }
            )
        }
        items(groups, key = { it.addonName }) { group ->
            FilterChip(
                selected = selected == group.addonName,
                onClick = { onSelect(group.addonName) },
                label = { Text("${group.addonName} (${group.streams.size})") }
            )
        }
    }
}

@Composable
private fun AddonHeader(name: String, count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = "$name • $count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun StreamRow(stream: Stream, onClick: () -> Unit) {
    val playable = !stream.getStreamUrl().isNullOrBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (playable) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            val badges = stream.qualityBadges()
            if (badges.isNotEmpty()) {
                QualityBadgeRow(badges = badges)
            }
            stream.getDisplayDescription()
                ?.takeIf { it.isNotBlank() && it != stream.getDisplayName() }
                ?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            if (!playable) {
                Text(
                    text = "No playable URL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun QualityBadgeRow(badges: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        badges.take(6).forEach { badge ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyRow(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Stream.qualityBadges(): List<String> {
    val source = listOfNotNull(name, title, description).joinToString(" ")
    if (source.isBlank()) return emptyList()
    return QUALITY_REGEX.findAll(source)
        .map { it.value.uppercase().replace("BLU-RAY", "BLURAY") }
        .distinct()
        .toList()
}
