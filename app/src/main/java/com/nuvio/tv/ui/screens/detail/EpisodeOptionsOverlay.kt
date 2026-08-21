package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import java.util.Locale

private data class EpisodeOverlayAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EpisodeOptionsOverlay(
    episode: Video,
    imdbRating: Double? = null,
    isWatched: Boolean,
    isPending: Boolean,
    isSeasonFullyWatched: Boolean = false,
    hasPreviousEpisodes: Boolean = false,
    hasProgress: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    onOpenEpisodeComments: () -> Unit = {},
    showOpenEpisodeComments: Boolean = false,
    onPlayManually: () -> Unit = {},
    showPlayManually: Boolean = false,
    onToggleWatched: () -> Unit,
    onMarkSeasonWatched: () -> Unit = {},
    onMarkSeasonUnwatched: () -> Unit = {},
    onMarkPreviousEpisodesWatched: () -> Unit = {}
) {
    val context = LocalContext.current
    val primaryFocusRequester = remember { FocusRequester() }
    val title = episode.title.localizeEpisodeTitle(context)
    val description = episode.overview?.trim().orEmpty()
    val ratingLabel = remember(imdbRating) {
        imdbRating?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
    }
    val episodeLabel = when {
        episode.season != null && episode.episode != null -> {
            stringResource(R.string.season_episode_format, episode.season, episode.episode)
        }
        episode.episode != null -> {
            "${stringResource(R.string.episodes_episode)} ${episode.episode}"
        }
        else -> stringResource(R.string.episodes_dialog_subtitle)
    }
    val actions = buildList {
        add(
            EpisodeOverlayAction(
                label = if (isWatched) {
                    stringResource(R.string.episodes_mark_unwatched)
                } else {
                    stringResource(R.string.episodes_mark_watched)
                },
                enabled = !isPending,
                onClick = onToggleWatched
            )
        )
        add(
            EpisodeOverlayAction(
                label = if (isSeasonFullyWatched) {
                    stringResource(R.string.episodes_mark_season_unwatched)
                } else {
                    stringResource(R.string.episodes_mark_season_watched)
                },
                onClick = if (isSeasonFullyWatched) onMarkSeasonUnwatched else onMarkSeasonWatched
            )
        )
        if (hasPreviousEpisodes) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.episodes_mark_previous_watched),
                    onClick = onMarkPreviousEpisodesWatched
                )
            )
        }
        add(
            EpisodeOverlayAction(
                label = stringResource(R.string.episodes_play),
                onClick = onPlay
            )
        )
        if (showOpenEpisodeComments) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.episodes_open_comments),
                    onClick = onOpenEpisodeComments
                )
            )
        }
        if (showPlayManually) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.play_manually),
                    onClick = onPlayManually
                )
            )
        }
        if (hasProgress) {
            add(
                EpisodeOverlayAction(
                    label = stringResource(R.string.cw_action_start_from_beginning),
                    onClick = onStartFromBeginning
                )
            )
        }
    }
    val initialActionIndex = actions.indexOfFirst { it.enabled }.coerceAtLeast(0)
    var acceptsSelectKey by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF050505),
                            Color(0xFF090909),
                            Color(0xFF111111)
                        )
                    )
                )
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (isSelectKey(native.keyCode)) {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            acceptsSelectKey = true
                        }
                        if (!acceptsSelectKey) {
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp, vertical = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(72.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = episodeLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = NuvioTheme.colors.Primary
                        )

                        ratingLabel?.let { rating ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ImdbRatingSourceLabel(
                                    logoModifier = Modifier.size(30.dp),
                                    textStyle = MaterialTheme.typography.titleMedium,
                                    textColor = Color.White.copy(alpha = 0.72f)
                                )
                                Text(
                                    text = rating,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
                        }
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    actions.forEachIndexed { index, action ->
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (index == initialActionIndex) {
                                        Modifier.focusRequester(primaryFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
