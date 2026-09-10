package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.RaiLiveChannel
import com.nuvio.tv.ui.components.LoadingIndicator

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onPlaybackResolved: (LiveTvPlaybackRequest) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val playbackRequest = uiState.playbackRequest
    if (playbackRequest != null) {
        onPlaybackResolved(playbackRequest)
        viewModel.consumePlaybackRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showBuiltInHeader) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(
                            horizontal = NuvioTheme.spacing.xl,
                            vertical = NuvioTheme.spacing.lg
                        )
                ) {
                    Text(
                        text = stringResource(R.string.live_tv_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.live_tv_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            val errorMessage = when (uiState.error) {
                LiveTvError.PROVIDER_NOT_INSTALLED -> stringResource(R.string.live_tv_error_provider_not_installed)
                LiveTvError.UNAVAILABLE -> stringResource(R.string.live_tv_error_unavailable)
                null -> null
            }
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.sm)
                        .background(
                            color = Color(0xFF5A1C1C),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(
                    horizontal = NuvioTheme.spacing.xl,
                    vertical = NuvioTheme.spacing.lg
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(viewModel.channels, key = { it.slug }) { channel ->
                    ChannelTile(
                        channel = channel,
                        isResolving = uiState.resolvingChannelSlug == channel.slug,
                        onClick = { viewModel.playChannel(channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(
    channel: RaiLiveChannel,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(150),
        label = "channelTileScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.none,
        animationSpec = tween(120),
        label = "channelTileBorder"
    )
    val fallbackColor = NuvioTheme.colors.Surface
    val backgroundColor = remember(channel.colorHex, fallbackColor) {
        runCatching { Color(android.graphics.Color.parseColor(channel.colorHex)) }
            .getOrDefault(fallbackColor)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                border = if (isFocused) {
                    NuvioTheme.focusRing.border(borderWidth)
                } else {
                    BorderStroke(borderWidth, Color.Transparent)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isResolving,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(channel.logoUrl)
                .crossfade(true)
                .build(),
            contentDescription = channel.displayName,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(2f),
            contentScale = ContentScale.Fit
        )
        Text(
            text = channel.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
        )
        if (isResolving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(modifier = Modifier.fillMaxWidth(0.3f).aspectRatio(1f))
            }
        }
    }
}
