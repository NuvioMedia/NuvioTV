@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
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
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    persistAmplification: Boolean,
    onTrackSelected: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tracksFocusRequester = remember { FocusRequester() }
    val minusFocusRequester = remember { FocusRequester() }
    val plusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecrease = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncrease = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible) return@LaunchedEffect

        if (tracks.isNotEmpty()) {
            val targetIndex = lastFocusedAudioIndex
                ?.let { idx -> tracks.indexOfFirst { it.index == idx } }
                ?.takeIf { it >= 0 }
                ?: tracks.indexOfFirst { it.index == selectedIndex }
                    .takeIf { it >= 0 }
                ?: 0
            listState.scrollToItem(targetIndex)
            delay(120)
            runCatching { tracksFocusRequester.requestFocus() }
        } else {
            delay(120)
            val initialMixFocusRequester = when {
                canDecrease -> minusFocusRequester
                canIncrease -> plusFocusRequester
                else -> persistFocusRequester
            }
            runCatching { initialMixFocusRequester.requestFocus() }
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 88.dp)
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.audio_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.width(460.dp)) {
                    AudioTracksContent(
                        tracks = tracks,
                        selectedIndex = selectedIndex,
                        listState = listState,
                        initialFocusRequester = tracksFocusRequester,
                        rightFocusRequester = when {
                            canDecrease -> minusFocusRequester
                            canIncrease -> plusFocusRequester
                            else -> persistFocusRequester
                        },
                        onTrackFocused = { lastFocusedAudioIndex = it },
                        onTrackSelected = onTrackSelected
                    )
                }
                Column(modifier = Modifier.width(286.dp)) {
                    AudioMixContent(
                        audioAmplificationDb = audioAmplificationDb,
                        isAmplificationAvailable = isAmplificationAvailable,
                        persistAmplification = persistAmplification,
                        minusFocusRequester = minusFocusRequester,
                        plusFocusRequester = plusFocusRequester,
                        persistFocusRequester = persistFocusRequester,
                        leftFocusRequester = tracksFocusRequester,
                        onAmplificationChange = onAmplificationChange,
                        onPersistAmplificationChange = onPersistAmplificationChange
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackFocused: (Int) -> Unit,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            text = stringResource(R.string.audio_lang_default),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        modifier = Modifier
            .heightIn(max = 620.dp)
            .fillMaxWidth()
    ) {
        items(items = tracks, key = { track -> track.index }) { track ->
            AudioTrackCard(
                track = track,
                isSelected = track.index == selectedIndex,
                onFocused = { onTrackFocused(track.index) },
                onClick = { onTrackSelected(track.index) },
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (
                    track.index == selectedIndex || (selectedIndex < 0 && track == tracks.firstOrNull())
                ) {
                    initialFocusRequester
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?
) {
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" | ")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() },
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

@Composable
private fun AudioMixContent(
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    persistAmplification: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAmplificationChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecrease = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncrease = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val plusLeftFocusRequester = if (canDecrease) minusFocusRequester else leftFocusRequester
    val persistLeftFocusRequester = when {
        canIncrease -> plusFocusRequester
        canDecrease -> minusFocusRequester
        else -> leftFocusRequester
    }
    val helperText = when {
        !isAmplificationAvailable -> stringResource(R.string.audio_mix_unavailable)
        persistAmplification -> stringResource(
            R.string.audio_mix_range_saved,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
        else -> stringResource(
            R.string.audio_mix_range,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.audio_mix_label),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.92f)
        )

        Text(
            text = stringResource(R.string.audio_mix_value_db, currentDb),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MixStepCard(
                icon = Icons.Default.Remove,
                enabled = canDecrease,
                focusRequester = minusFocusRequester,
                leftFocusRequester = leftFocusRequester,
                rightFocusRequester = if (canIncrease) plusFocusRequester else persistFocusRequester,
                onClick = {
                    val nextDb = currentDb - 1
                    onAmplificationChange(nextDb)
                    if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncrease) {
                        runCatching { plusFocusRequester.requestFocus() }
                    }
                }
            )
            MixStepCard(
                icon = Icons.Default.Add,
                enabled = canIncrease,
                focusRequester = plusFocusRequester,
                leftFocusRequester = plusLeftFocusRequester,
                rightFocusRequester = persistFocusRequester,
                onClick = {
                    val nextDb = currentDb + 1
                    onAmplificationChange(nextDb)
                    if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecrease) {
                        runCatching { minusFocusRequester.requestFocus() }
                    }
                }
            )
        }

        Card(
            onClick = { onPersistAmplificationChange(!persistAmplification) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(persistFocusRequester)
                .focusProperties { left = persistLeftFocusRequester },
            colors = CardDefaults.colors(
                containerColor = if (persistAmplification) NuvioColors.Secondary else Color.Transparent,
                focusedContainerColor = if (persistAmplification) NuvioColors.Secondary else Color.Transparent
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
            Text(
                text = if (persistAmplification) {
                    stringResource(R.string.audio_mix_persist_on)
                } else {
                    stringResource(R.string.audio_mix_persist_off)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (persistAmplification) NuvioColors.OnSecondary else Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f)
        )
    }
}

@Composable
private fun MixStepCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester = FocusRequester.Default,
    onClick: () -> Unit
) {
    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        modifier = Modifier
            .width(78.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftFocusRequester
                right = rightFocusRequester
            },
        colors = CardDefaults.colors(
            containerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, if (enabled) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) NuvioColors.FocusRing else Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
            )
        }
    }
}
