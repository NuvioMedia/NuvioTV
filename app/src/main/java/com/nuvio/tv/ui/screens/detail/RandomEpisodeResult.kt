package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.PlayManualOverrideDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizeEpisodeTitle

@Composable
internal fun RandomEpisodeResult(
    episode: Video,
    isWatched: Boolean,
    hideArtwork: Boolean,
    count: Int,
    includeWatched: Boolean,
    interactive: Boolean,
    primaryFocusRequester: FocusRequester,
    closeFocusRequester: FocusRequester,
    isResume: Boolean,
    showManualPlayOption: Boolean,
    onPlay: () -> Unit,
    onPlayManually: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onPickAgain: () -> Unit,
    onChangeSelection: () -> Unit
) {
    val compact = LocalConfiguration.current.screenHeightDp < 600
    var showPlayOptions by remember(episode.id) { mutableStateOf(false) }
    var restorePlayFocusToken by remember { mutableIntStateOf(0) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RandomEpisodeArtwork(episode = episode, hideArtwork = hideArtwork, isWatched = isWatched)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(if (includeWatched) R.string.random_episode_include_watched else R.string.random_episode_unwatched),
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    pluralStringResource(R.plurals.random_episode_count, count, count),
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
            Text(stringResource(R.string.random_episode_your_pick), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.Primary)
            AnimatedContent(
                targetState = episode,
                contentKey = { it.id },
                transitionSpec = {
                    (fadeIn(tween(150, delayMillis = 70)) togetherWith
                        fadeOut(tween(70))).using(null)
                },
                label = "randomEpisodeDetails"
            ) { displayedEpisode ->
                EpisodeDetails(
                    episode = displayedEpisode,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth()
                        .then(if (displayedEpisode.id == episode.id) Modifier else Modifier.clearAndSetSemantics {})
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayButton(
                    text = stringResource(if (isResume) R.string.detail_btn_resume else R.string.hero_play),
                    onClick = { if (interactive) onPlay() },
                    onLongPress = if (interactive && (showManualPlayOption || isResume)) {
                        { showPlayOptions = true }
                    } else null,
                    focusRequester = primaryFocusRequester,
                    restoreFocusToken = restorePlayFocusToken,
                    modifier = Modifier.focusProperties {
                        canFocus = interactive
                        up = closeFocusRequester
                    }
                )
                if (count > 1) {
                    RandomEpisodeButton(
                        text = stringResource(R.string.random_episode_pick_again),
                        icon = Icons.Default.Shuffle,
                        onClick = onPickAgain,
                        interactive = interactive,
                        modifier = Modifier.focusProperties { up = closeFocusRequester }
                    )
                }
            }
            RandomEpisodeButton(
                text = stringResource(R.string.random_episode_change_mix),
                onClick = onChangeSelection,
                interactive = interactive,
                modifier = Modifier.focusProperties { down = FocusRequester.Cancel }
            )
            if (count == 1) {
                Text(
                    stringResource(R.string.random_episode_only_option),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (showPlayOptions && interactive) {
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
                onPlayManually()
            },
            showStartFromBeginning = isResume,
            onStartFromBeginning = {
                showPlayOptions = false
                onStartFromBeginning()
            }
        )
    }
}

@Composable
private fun EpisodeDetails(episode: Video, compact: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val overviewLines = if (compact) 2 else 3
    val spacing = if (compact) 10.dp else 14.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.season_episode_format, episode.season ?: 0, episode.episode ?: 0),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f)
            )
            episode.runtime?.takeIf { it > 0 }?.let {
                Text(stringResource(R.string.random_episode_runtime, it), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.55f))
            }
        }
        Text(
            episode.title.localizeEpisodeTitle(context),
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        episode.overview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = overviewLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class EpisodeArtwork(val id: String, val thumbnail: String?, val hidden: Boolean)

@Composable
private fun RandomEpisodeArtwork(episode: Video, hideArtwork: Boolean, isWatched: Boolean) {
    val artwork = EpisodeArtwork(episode.id, episode.thumbnail, hideArtwork)
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp)).background(Color(0xFF252525))
    ) {
        AnimatedContent(
            targetState = artwork,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.id },
            transitionSpec = {
                (fadeIn(tween(240, easing = LinearEasing)) togetherWith
                    fadeOut(tween(0, delayMillis = 240)))
                    .using(null).apply { targetContentZIndex = 1f }
            },
            label = "randomEpisodeArtwork"
        ) { displayed ->
            ArtworkImage(
                artwork = displayed,
                modifier = if (displayed.id == artwork.id) Modifier else Modifier.clearAndSetSemantics {}
            )
        }
        Text(
            text = stringResource(if (isWatched) R.string.random_episode_watched else R.string.random_episode_not_watched),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ArtworkImage(artwork: EpisodeArtwork, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(context, artwork.thumbnail, artwork.hidden) {
        artwork.thumbnail?.takeIf { it.isNotBlank() && !artwork.hidden }?.let {
            ImageRequest.Builder(context).data(it).size(960, 540).crossfade(180).build()
        }
    }
    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF252525)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (artwork.hidden) Icons.Default.VisibilityOff else Icons.Default.Shuffle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(48.dp)
            )
            if (artwork.hidden) {
                Text(stringResource(R.string.random_episode_artwork_hidden), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
            }
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RandomEpisodeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    detail: String? = null,
    enabled: Boolean = true,
    interactive: Boolean = true
) {
    val shape = RoundedCornerShape(if (subtitle != null) 16.dp else 28.dp)
    Button(
        onClick = { if (interactive) onClick() },
        enabled = enabled,
        modifier = modifier.then(if (interactive) Modifier else Modifier.focusProperties { canFocus = false }),
        shape = ButtonDefaults.shape(shape = shape),
        scale = ButtonDefaults.scale(focusedScale = if (subtitle != null) 1.01f else 1f, pressedScale = 0.985f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF202020),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color(0xFF161616),
            disabledContentColor = Color.White.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(
            horizontal = 20.dp,
            vertical = if (subtitle != null) 20.dp else 12.dp
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = text,
                style = if (subtitle != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            detail?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
