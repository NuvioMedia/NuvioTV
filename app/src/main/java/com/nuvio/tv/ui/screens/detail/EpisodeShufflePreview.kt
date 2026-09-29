package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.PlayManualOverrideDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizeEpisodeTitle

@Composable
internal fun EpisodeShufflePreview(
    meta: Meta,
    episode: Video,
    includeWatched: Boolean,
    isWatched: Boolean,
    isResume: Boolean,
    showManualPlayOption: Boolean,
    blurUnwatchedEpisodes: Boolean,
    canShuffleAgain: Boolean,
    starting: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayManually: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onShuffleAgain: () -> Unit
) {
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var acceptsSelectKey by remember { mutableStateOf(false) }
    var showPlayOptions by remember(episode.id) { mutableStateOf(false) }
    var restorePlayFocusToken by remember { mutableIntStateOf(0) }
    val compact = LocalConfiguration.current.screenHeightDp < 600

    Dialog(
        onDismissRequest = { if (!starting) onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        LaunchedEffect(canShuffleAgain) { playFocus.requestFocusAfterFrames() }
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
        }
        Column(
            modifier = Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF070707), Color(0xFF101010), Color(0xFF151515))))
                .onPreviewKeyEvent { event ->
                    if (starting) return@onPreviewKeyEvent true
                    val native = event.nativeKeyEvent
                    if (native.keyCode in listOf(AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            acceptsSelectKey = true
                        }
                        if (!acceptsSelectKey) return@onPreviewKeyEvent true
                        if (native.action == AndroidKeyEvent.ACTION_UP) acceptsSelectKey = false
                    }
                    false
                }
                .padding(horizontal = if (compact) 40.dp else 64.dp, vertical = if (compact) 24.dp else 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(
                    onClick = { if (!starting) onBack() },
                    modifier = Modifier.focusRequester(backFocus).focusProperties { down = playFocus }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_back), modifier = Modifier.padding(start = 8.dp))
                }
                Text(meta.name, style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShuffleEpisodeArtwork(episode, isWatched, blurUnwatchedEpisodes && !isWatched)
                    Text(
                        stringResource(if (includeWatched) R.string.random_episode_include_watched else R.string.random_episode_unwatched),
                        style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
                    Text(stringResource(R.string.shuffle_preview_title), style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.Primary)
                    Text(stringResource(R.string.season_episode_format, episode.season ?: 0, episode.episode ?: 0),
                        style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                    Text(episode.title.localizeEpisodeTitle(LocalContext.current),
                        style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                        color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    episode.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary,
                            maxLines = if (compact) 3 else 4, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayButton(
                            text = stringResource(when {
                                starting -> R.string.shuffle_starting
                                isResume -> R.string.detail_btn_resume
                                else -> R.string.shuffle_play_episode
                            }),
                            onClick = { if (!starting) onPlay() },
                            onLongPress = if (!starting && (showManualPlayOption || isResume)) {
                                { showPlayOptions = true }
                            } else null,
                            focusRequester = playFocus,
                            restoreFocusToken = restorePlayFocusToken,
                            modifier = Modifier.focusProperties { up = backFocus; down = FocusRequester.Cancel }
                        )
                        if (canShuffleAgain) {
                            Button(
                                onClick = { if (!starting) onShuffleAgain() },
                                modifier = Modifier.focusProperties { up = backFocus; down = FocusRequester.Cancel }
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.shuffle_again), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (!canShuffleAgain && !starting) {
                        Text(stringResource(R.string.shuffle_only_episode), style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
        }
    }
    if (showPlayOptions && !starting && (showManualPlayOption || isResume)) {
        PlayManualOverrideDialog(
            title = episode.title.localizeEpisodeTitle(LocalContext.current),
            subtitle = stringResource(R.string.season_episode_format, episode.season ?: 0, episode.episode ?: 0),
            onDismiss = {
                showPlayOptions = false
                restorePlayFocusToken++
            },
            showPlayManually = showManualPlayOption,
            onPlayManually = {
                showPlayOptions = false
                restorePlayFocusToken++
                onPlayManually()
            },
            showStartFromBeginning = isResume,
            onStartFromBeginning = {
                showPlayOptions = false
                restorePlayFocusToken++
                onStartFromBeginning()
            }
        )
    }
}

@Composable
private fun ShuffleEpisodeArtwork(episode: Video, isWatched: Boolean, hidden: Boolean) {
    val context = LocalContext.current
    val request = remember(context, episode.thumbnail, hidden) {
        episode.thumbnail?.takeIf { it.isNotBlank() && !hidden }?.let {
            episodeOverlayBackdropRequest(context, it, 960, 540)
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp)).background(NuvioTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (hidden) Icons.Default.VisibilityOff else Icons.Default.Shuffle, contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(48.dp))
            if (hidden) Text(stringResource(R.string.shuffle_artwork_hidden), style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary)
        }
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        Text(
            stringResource(if (isWatched) R.string.library_filter_watched else R.string.library_watched_filter_unwatched),
            style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
