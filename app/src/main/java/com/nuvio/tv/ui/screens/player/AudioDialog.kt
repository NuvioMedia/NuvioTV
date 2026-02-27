@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
internal fun AudioSelectionDialog(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    persistAmplification: Boolean,
    onTrackSelected: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Audio", "Volume")
    val tabFocusRequesters = remember { tabs.map { FocusRequester() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F0F0F))
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Audio",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        AudioTab(
                            title = title,
                            isSelected = selectedTabIndex == index,
                            focusRequester = tabFocusRequesters[index],
                            onClick = { selectedTabIndex = index }
                        )
                        if (index < tabs.lastIndex) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }

                when (selectedTabIndex) {
                    0 -> AudioTracksContent(
                        tracks = tracks,
                        selectedIndex = selectedIndex,
                        onTrackSelected = onTrackSelected
                    )
                    1 -> AudioAmplificationContent(
                        audioAmplificationDb = audioAmplificationDb,
                        isAmplificationAvailable = isAmplificationAvailable,
                        persistAmplification = persistAmplification,
                        onAmplificationChange = onAmplificationChange,
                        onPersistAmplificationChange = onPersistAmplificationChange
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            tabFocusRequesters[0].requestFocus()
        } catch (_: Exception) {}
    }
}

@Composable
private fun AudioTab(
    title: String,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.18f)
                isFocused -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = if (isSelected) {
                Color.White.copy(alpha = 0.22f)
            } else {
                Color.White.copy(alpha = 0.12f)
            }
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No audio tracks available",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        items(tracks) { track ->
            AudioTrackItem(
                track = track,
                isSelected = track.index == selectedIndex,
                onClick = { onTrackSelected(track.index) }
            )
        }
    }
}

@Composable
private fun AudioTrackItem(
    track: TrackInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (isSelected) {
                Color.White.copy(alpha = 0.12f)
            } else {
                Color.White.copy(alpha = 0.05f)
            },
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f)
                )
                if (track.language != null) {
                    Text(
                        text = track.language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioAmplificationContent(
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    persistAmplification: Boolean,
    onAmplificationChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDb = audioAmplificationDb.coerceIn(
        AUDIO_AMPLIFICATION_MIN_DB,
        AUDIO_AMPLIFICATION_MAX_DB
    )
    val canDecrease = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncrease = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Amplification",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.85f)
        )

        Text(
            text = "$currentDb dB",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AmplificationStepButton(
                icon = Icons.Default.Remove,
                enabled = canDecrease,
                onClick = { onAmplificationChange(currentDb - 1) }
            )
            AmplificationStepButton(
                icon = Icons.Default.Add,
                enabled = canIncrease,
                onClick = { onAmplificationChange(currentDb + 1) }
            )
        }

        Card(
            onClick = { onPersistAmplificationChange(!persistAmplification) },
            modifier = Modifier.padding(top = 20.dp),
            colors = CardDefaults.colors(
                containerColor = if (persistAmplification) {
                    Color.White.copy(alpha = 0.16f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                focusedContainerColor = if (persistAmplification) {
                    Color.White.copy(alpha = 0.2f)
                } else {
                    Color.White.copy(alpha = 0.14f)
                }
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(
                text = if (persistAmplification) {
                    "Persist between sessions: ON"
                } else {
                    "Persist between sessions: OFF"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        val helperText = if (isAmplificationAvailable) {
            if (persistAmplification) {
                "Range: ${AUDIO_AMPLIFICATION_MIN_DB} dB to ${AUDIO_AMPLIFICATION_MAX_DB} dB (saved)"
            } else {
                "Range: ${AUDIO_AMPLIFICATION_MIN_DB} dB to ${AUDIO_AMPLIFICATION_MAX_DB} dB"
            }
        } else {
            "Amplification is not available on this device"
        }
        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

@Composable
private fun AmplificationStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        modifier = Modifier
            .size(64.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                !enabled -> Color.White.copy(alpha = 0.04f)
                isFocused -> Color.White.copy(alpha = 0.2f)
                else -> Color.White.copy(alpha = 0.1f)
            },
            focusedContainerColor = if (enabled) {
                Color.White.copy(alpha = 0.22f)
            } else {
                Color.White.copy(alpha = 0.08f)
            }
        ),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
            )
        }
    }
}
