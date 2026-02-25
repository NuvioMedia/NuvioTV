@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun NextEpisodeCardOverlay(
    nextEpisode: NextEpisodeInfo?,
    visible: Boolean,
    controlsVisible: Boolean,
    isPlayable: Boolean,
    unairedMessage: String?,
    isAutoPlaySearching: Boolean,
    autoPlaySourceName: String?,
    autoPlayCountdownSec: Int?,
    onPlayNext: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (nextEpisode == null) return

    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: internalFocusRequester

    LaunchedEffect(visible, controlsVisible) {
        if (visible && !controlsVisible) {
            delay(350)
            runCatching { activeFocusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier
    ) {
        val onCardClick: () -> Unit = if (isPlayable) onPlayNext else ({})
        Card(
            onClick = onCardClick,
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = Color(0xE3191919),
                focusedContainerColor = Color(0xE3191919)
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1f),
            modifier = Modifier
                .width(420.dp)
                .focusRequester(activeFocusRequester)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 64.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .drawWithCache {
                            val overlayBrush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.32f)
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(brush = overlayBrush)
                            }
                        }
                ) {
                    AsyncImage(
                        model = nextEpisode.thumbnail,
                        contentDescription = "Next episode thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = stringResource(R.string.next_episode_label),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "S${nextEpisode.season}E${nextEpisode.episode} • ${nextEpisode.title}",
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    val autoPlayStatus = when {
                        !isPlayable && !unairedMessage.isNullOrBlank() -> unairedMessage
                        isAutoPlaySearching -> "Finding source..."
                        !autoPlaySourceName.isNullOrBlank() && autoPlayCountdownSec != null ->
                            "Playing via $autoPlaySourceName in ${autoPlayCountdownSec}s"
                        else -> null
                    }
                    if (autoPlayStatus != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = autoPlayStatus,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .drawWithCache {
                            val strokeWidth = 1.dp.toPx()
                            val strokeColor = Color.White.copy(alpha = 0.2f)
                            onDrawBehind {
                                drawRoundRect(
                                    color = strokeColor,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        x = size.height / 2f,
                                        y = size.height / 2f
                                    ),
                                    style = Stroke(width = strokeWidth)
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isPlayable) Color.White else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isPlayable) "Play" else "Unaired",
                        color = if (isPlayable) Color.White else Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                }
            }
        }
    }
}
