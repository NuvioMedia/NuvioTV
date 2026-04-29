package com.omnio.phone.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnio.tv.core.player.PlayerUiState
import com.omnio.tv.core.player.TrackInfo
import com.omnio.tv.domain.model.Stream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhonePlayerAudioSheet(
    uiState: PlayerUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetTitle("Audio")
        if (uiState.audioTracks.isEmpty()) {
            EmptyRow("No audio tracks available.")
        } else {
            LazyColumn {
                items(uiState.audioTracks, key = { it.index }) { track ->
                    TrackRow(
                        track = track,
                        isSelected = track.index == uiState.selectedAudioTrackIndex,
                        onClick = {
                            onSelect(track.index)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhonePlayerSubtitleSheet(
    uiState: PlayerUiState,
    onSelect: (Int) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetTitle("Subtitles")
        Column {
            DisableRow(
                isSelected = uiState.selectedSubtitleTrackIndex < 0,
                onClick = {
                    onDisable()
                    onDismiss()
                }
            )
            HorizontalDivider()
            if (uiState.subtitleTracks.isEmpty()) {
                EmptyRow("No subtitle tracks available.")
            } else {
                LazyColumn {
                    items(uiState.subtitleTracks, key = { it.index }) { track ->
                        TrackRow(
                            track = track,
                            isSelected = track.index == uiState.selectedSubtitleTrackIndex,
                            onClick = {
                                onSelect(track.index)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhonePlayerSourcesSheet(
    uiState: PlayerUiState,
    onSelectStream: (Stream) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetTitle("Sources")
        when {
            uiState.isLoadingSourceStreams -> EmptyRow("Loading sources…")
            uiState.sourceStreamsError != null -> EmptyRow(uiState.sourceStreamsError ?: "Failed to load sources.")
            uiState.sourceFilteredStreams.isEmpty() -> EmptyRow("No alternative sources found.")
            else -> LazyColumn {
                items(uiState.sourceFilteredStreams, key = { stream -> stream.getStreamUrl() ?: stream.getDisplayName() }) { stream ->
                    StreamRow(
                        stream = stream,
                        isCurrent = stream.getStreamUrl() == uiState.currentStreamUrl,
                        onClick = {
                            onSelectStream(stream)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhonePlayerSettingsSheet(
    backgroundPlaybackEnabled: Boolean,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetTitle("Player options")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Background playback", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Keep audio playing when leaving the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = backgroundPlaybackEnabled, onCheckedChange = onBackgroundPlaybackChange)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Surface(color = Color.Transparent) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun TrackRow(track: TrackInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = if (isSelected) "Selected" else null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(
                track.language,
                track.codec,
                track.channelCount?.let { ch -> if (ch > 0) "${ch}ch" else null }
            ).joinToString(" • ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DisableRow(isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = if (isSelected) "Selected" else null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
        )
        Text("Off", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StreamRow(stream: Stream, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isCurrent) Icons.Filled.Check else Icons.Filled.SettingsBackupRestore,
            contentDescription = if (isCurrent) "Current source" else null,
            tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stream.getDisplayName(), style = MaterialTheme.typography.bodyLarge)
            stream.getDisplayDescription()?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (stream.addonName.isNotBlank()) {
                Text(
                    stream.addonName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
