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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    onTrackSelected: (Int) -> Unit,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tracksFocusRequester = remember { FocusRequester() }
    val delayMinusFocusRequester = remember { FocusRequester() }
    val delayPlusFocusRequester = remember { FocusRequester() }
    val ampMinusFocusRequester = remember { FocusRequester() }
    val ampPlusFocusRequester = remember { FocusRequester() }
    val centerMinusFocusRequester = remember { FocusRequester() }
    val centerPlusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingControlFocusTarget by rememberSaveable {
        mutableStateOf<AudioControlFocusTarget?>(null)
    }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible) return@LaunchedEffect

        if (tracks.isNotEmpty()) {
            val selectedListIndex = tracks.indexOfFirst { it.index == selectedIndex }.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(selectedListIndex)
            delay(120)
            runCatching { tracksFocusRequester.requestFocus() }
        } else {
            delay(120)
            val initialControlsFocusRequester = when {
                canDecreaseDelay -> delayMinusFocusRequester
                canIncreaseDelay -> delayPlusFocusRequester
                canDecreaseAmp -> ampMinusFocusRequester
                canIncreaseAmp -> ampPlusFocusRequester
                canDecreaseCenterMix -> centerMinusFocusRequester
                canIncreaseCenterMix -> centerPlusFocusRequester
                else -> persistFocusRequester
            }
            runCatching { initialControlsFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(visible, pendingControlFocusTarget, audioAmplificationDb, isAmplificationAvailable) {
        if (!visible) return@LaunchedEffect
        val target = pendingControlFocusTarget ?: return@LaunchedEffect
        val targetCanFocus = when (target) {
            AudioControlFocusTarget.DelayMinus -> canDecreaseDelay
            AudioControlFocusTarget.DelayPlus -> canIncreaseDelay
            AudioControlFocusTarget.AmpMinus -> canDecreaseAmp
            AudioControlFocusTarget.AmpPlus -> canIncreaseAmp
            AudioControlFocusTarget.CenterMinus -> canDecreaseCenterMix
            AudioControlFocusTarget.CenterPlus -> canIncreaseCenterMix
            AudioControlFocusTarget.Persist -> true
        }
        if (!targetCanFocus) return@LaunchedEffect
        val controlFocusRequester = when (target) {
            AudioControlFocusTarget.DelayMinus -> delayMinusFocusRequester
            AudioControlFocusTarget.DelayPlus -> delayPlusFocusRequester
            AudioControlFocusTarget.AmpMinus -> ampMinusFocusRequester
            AudioControlFocusTarget.AmpPlus -> ampPlusFocusRequester
            AudioControlFocusTarget.CenterMinus -> centerMinusFocusRequester
            AudioControlFocusTarget.CenterPlus -> centerPlusFocusRequester
            AudioControlFocusTarget.Persist -> persistFocusRequester
        }
        delay(80)
        controlFocusRequester.requestFocusAfterFrames(frames = 2)
        delay(200)
        runCatching { controlFocusRequester.requestFocus() }
        pendingControlFocusTarget = null
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp)
    ) {
        Column(
            modifier = Modifier
                .width(724.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.audio_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.width(444.dp)) {
                    AudioTracksContent(
                        tracks = tracks,
                        selectedIndex = selectedIndex,
                        listState = listState,
                        initialFocusRequester = tracksFocusRequester,
                        rightFocusRequester = when {
                            canDecreaseDelay -> delayMinusFocusRequester
                            canIncreaseDelay -> delayPlusFocusRequester
                            canDecreaseAmp -> ampMinusFocusRequester
                            canIncreaseAmp -> ampPlusFocusRequester
                            canDecreaseCenterMix -> centerMinusFocusRequester
                            canIncreaseCenterMix -> centerPlusFocusRequester
                            else -> persistFocusRequester
                        },
                        onTrackFocused = { lastFocusedAudioIndex = it },
                        onTrackSelected = onTrackSelected
                    )
                }
                Column(modifier = Modifier.width(268.dp)) {
                    AudioControlsContent(
                        audioDelayMs = audioDelayMs,
                        audioAmplificationDb = audioAmplificationDb,
                        isAmplificationAvailable = isAmplificationAvailable,
                        centerMixLevelDb = centerMixLevelDb,
                        isCenterMixAvailable = isCenterMixAvailable,
                        persistAmplification = persistAmplification,
                        delayMinusFocusRequester = delayMinusFocusRequester,
                        delayPlusFocusRequester = delayPlusFocusRequester,
                        ampMinusFocusRequester = ampMinusFocusRequester,
                        ampPlusFocusRequester = ampPlusFocusRequester,
                        centerMinusFocusRequester = centerMinusFocusRequester,
                        centerPlusFocusRequester = centerPlusFocusRequester,
                        persistFocusRequester = persistFocusRequester,
                        leftFocusRequester = tracksFocusRequester,
                        onAudioDelayChange = onAudioDelayChange,
                        onAmplificationChange = { nextDb, focusTarget ->
                            pendingControlFocusTarget = focusTarget
                            onAmplificationChange(nextDb)
                        },
                        onCenterMixLevelChange = onCenterMixLevelChange,
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
                if (!track.language.isNullOrBlank() && track.language != "und") {
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
private fun AudioControlsContent(
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    delayMinusFocusRequester: FocusRequester,
    delayPlusFocusRequester: FocusRequester,
    ampMinusFocusRequester: FocusRequester,
    ampPlusFocusRequester: FocusRequester,
    centerMinusFocusRequester: FocusRequester,
    centerPlusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int, AudioControlFocusTarget) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    val firstDelayFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        delayPlusFocusRequester
    }
    val firstAmpFocusRequester = when {
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val firstCenterFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val delayPlusLeftFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val ampPlusLeftFocusRequester = if (canDecreaseAmp) {
        ampMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val centerPlusLeftFocusRequester = if (canDecreaseCenterMix) {
        centerMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val persistLeftFocusRequester = when {
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        else -> leftFocusRequester
    }
    val centerHelperText = if (isCenterMixAvailable) {
        stringResource(R.string.audio_center_mix_help)
    } else {
        stringResource(R.string.audio_center_mix_unavailable)
    }

    val amplificationHelperText = when {
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
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdjustmentSection(
            title = stringResource(R.string.audio_delay_label),
            valueText = formatAudioDelay(currentDelayMs),
            helperText = stringResource(
                R.string.audio_delay_range,
                AUDIO_DELAY_MIN_MS / 1000f,
                AUDIO_DELAY_MAX_MS / 1000f
            ),
            canDecrease = canDecreaseDelay,
            canIncrease = canIncreaseDelay,
            minusFocusRequester = delayMinusFocusRequester,
            plusFocusRequester = delayPlusFocusRequester,
            minusLeftFocusRequester = leftFocusRequester,
            plusLeftFocusRequester = delayPlusLeftFocusRequester,
            upFocusRequester = null,
            downFocusRequester = firstAmpFocusRequester,
            onDecrease = {
                val nextDelayMs = currentDelayMs - AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDelayMs = currentDelayMs + AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            }
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdjustmentSection(
                title = stringResource(R.string.audio_mix_label),
                valueText = stringResource(R.string.audio_mix_value_db, currentDb),
                helperText = amplificationHelperText,
                canDecrease = canDecreaseAmp,
                canIncrease = canIncreaseAmp,
                minusFocusRequester = ampMinusFocusRequester,
                plusFocusRequester = ampPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = ampPlusLeftFocusRequester,
                upFocusRequester = firstDelayFocusRequester,
                downFocusRequester = firstCenterFocusRequester,
                onDecrease = {
                    val nextDb = currentDb - 1
                    val target = if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        AudioControlFocusTarget.AmpPlus
                    } else {
                        AudioControlFocusTarget.AmpMinus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        runCatching { ampPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentDb + 1
                    val target = if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        AudioControlFocusTarget.AmpMinus
                    } else {
                        AudioControlFocusTarget.AmpPlus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        runCatching { ampMinusFocusRequester.requestFocus() }
                    }
                }
            )

            AdjustmentSection(
                title = stringResource(R.string.audio_center_mix_label),
                valueText = stringResource(R.string.audio_center_mix_value_db, currentCenterMixDb),
                helperText = centerHelperText,
                canDecrease = canDecreaseCenterMix,
                canIncrease = canIncreaseCenterMix,
                minusFocusRequester = centerMinusFocusRequester,
                plusFocusRequester = centerPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = centerPlusLeftFocusRequester,
                upFocusRequester = firstAmpFocusRequester,
                downFocusRequester = persistFocusRequester,
                onDecrease = {
                    val nextDb = currentCenterMixDb - 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb <= CENTER_MIX_LEVEL_MIN_DB && canIncreaseCenterMix) {
                        runCatching { centerPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentCenterMixDb + 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb >= CENTER_MIX_LEVEL_MAX_DB && canDecreaseCenterMix) {
                        runCatching { centerMinusFocusRequester.requestFocus() }
                    }
                }
            )

            Card(
                onClick = { onPersistAmplificationChange(!persistAmplification) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(persistFocusRequester)
                    .focusProperties {
                        left = persistLeftFocusRequester
                        up = firstCenterFocusRequester
                    },
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
                    style = MaterialTheme.typography.bodySmall,
                    color = if (persistAmplification) NuvioColors.OnSecondary else Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun AdjustmentSection(
    title: String,
    valueText: String,
    helperText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    minusLeftFocusRequester: FocusRequester,
    plusLeftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f)
        )

        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StepCard(
                icon = Icons.Default.Remove,
                enabled = canDecrease,
                focusRequester = minusFocusRequester,
                leftFocusRequester = minusLeftFocusRequester,
                rightFocusRequester = if (canIncrease) plusFocusRequester else FocusRequester.Default,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onDecrease
            )
            StepCard(
                icon = Icons.Default.Add,
                enabled = canIncrease,
                focusRequester = plusFocusRequester,
                leftFocusRequester = plusLeftFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onIncrease
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
private fun StepCard(
    icon: ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester = FocusRequester.Default,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        modifier = Modifier
            .width(56.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftFocusRequester
                right = rightFocusRequester
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
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
                .padding(vertical = 6.dp),
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

private enum class AudioControlFocusTarget {
    DelayMinus,
    DelayPlus,
    AmpMinus,
    AmpPlus,
    CenterMinus,
    CenterPlus,
    Persist
}

private fun formatAudioDelay(delayMs: Int): String {
    return if (delayMs == 0) {
        String.format(Locale.US, "%.3fs", 0f)
    } else {
        String.format(Locale.US, "%+.3fs", delayMs / 1000f)
    }
}
