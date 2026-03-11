@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Card
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.languageCodeToName
import kotlinx.coroutines.delay

@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initialFocusRequester = remember { FocusRequester() }
    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 88.dp)
    ) {
        LaunchedEffect(visible, tracks, selectedIndex) {
            if (visible) {
                delay(120)
                runCatching { initialFocusRequester.requestFocus() }
            }
        }

        Column(
            modifier = Modifier
                .width(440.dp)
                .align(Alignment.BottomStart),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.audio_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (tracks.isEmpty()) {
                Text(
                    text = stringResource(R.string.audio_lang_default),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    modifier = Modifier.heightIn(max = 720.dp)
                ) {
                    items(items = tracks, key = { track -> track.index }) { track ->
                        AudioTrackCard(
                            track = track,
                            isSelected = track.index == selectedIndex,
                            onFocused = { lastFocusedAudioIndex = track.index },
                            onClick = { onTrackSelected(track.index) },
                            focusRequester = if (
                                track.index == lastFocusedAudioIndex ||
                                (lastFocusedAudioIndex == null && track.index == selectedIndex) ||
                                (lastFocusedAudioIndex == null && selectedIndex < 0 && track == tracks.firstOrNull())
                            ) {
                                initialFocusRequester
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?
) {
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" • ")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) onFocused()
            },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioColors.Secondary else Color.Transparent,
            focusedContainerColor = if (isSelected) NuvioColors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primaryTextColor = if (isSelected) NuvioColors.OnSecondary else Color.White
        val secondaryTextColor = if (isSelected) {
            NuvioColors.OnSecondary.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }
        val metadataTextColor = if (isSelected) {
            NuvioColors.OnSecondary.copy(alpha = 0.72f)
        } else {
            NuvioColors.TextTertiary
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryTextColor
                )
                if (!track.language.isNullOrBlank()) {
                    Text(
                        text = languageCodeToName(track.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = metadataTextColor
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioColors.OnSecondary
                )
            }
        }
    }
}
