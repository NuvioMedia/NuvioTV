@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R

@Composable
fun NextEpisodeEndPromptOverlay(
    nextEpisode: NextEpisodeInfo,
    onContinue: () -> Unit,
    onReturnToDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val continueFocusRequester = remember { FocusRequester() }
    val returnFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val nextEpisodeText = nextEpisodeEndPromptLabel(nextEpisode)

    LaunchedEffect(nextEpisode.videoId) {
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { continueFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .focusGroup()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.player_next_episode_prompt_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.player_next_episode_prompt_message),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = stringResource(R.string.next_episode_label),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.46f),
                textAlign = TextAlign.Center
            )

            Text(
                text = nextEpisodeText,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogButton(
                    text = stringResource(R.string.player_next_episode_prompt_yes),
                    onClick = onContinue,
                    isPrimary = true,
                    modifier = Modifier
                        .focusRequester(continueFocusRequester)
                        .focusProperties { right = returnFocusRequester }
                )

                DialogButton(
                    text = stringResource(R.string.player_next_episode_prompt_no),
                    onClick = onReturnToDetails,
                    isPrimary = false,
                    modifier = Modifier
                        .focusRequester(returnFocusRequester)
                        .focusProperties { left = continueFocusRequester }
                )
            }
        }
    }
}

@Composable
private fun nextEpisodeEndPromptLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val episodeCode = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode
    )
    return "$episodeCode - ${nextEpisode.title}"
}
