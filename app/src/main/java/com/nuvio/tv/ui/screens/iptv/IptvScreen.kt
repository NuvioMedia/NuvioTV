package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgramme
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun IptvScreen(
    viewModel: IptvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onPlaybackResolved: (IptvPlaybackRequest) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var guideChannel by remember { mutableStateOf<IptvChannel?>(null) }

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
                        .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)
                ) {
                    Text(
                        text = stringResource(R.string.iptv_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.iptv_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            when {
                uiState.error == IptvError.NOT_CONFIGURED -> {
                    IptvMessage(
                        text = stringResource(R.string.iptv_error_not_configured),
                        onClick = onOpenSettings,
                        actionLabel = stringResource(R.string.iptv_open_settings)
                    )
                }
                uiState.error == IptvError.LOAD_FAILED -> {
                    IptvMessage(
                        text = stringResource(R.string.iptv_error_load_failed),
                        onClick = { viewModel.load(forceRefresh = true) },
                        actionLabel = stringResource(R.string.iptv_retry)
                    )
                }
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.fillMaxWidth(0.1f).aspectRatio(1f))
                    }
                }
                else -> {
                    if (uiState.groups.size > 1) {
                        GroupFilterRow(
                            groups = uiState.groups,
                            selectedGroup = uiState.selectedGroup,
                            onSelect = viewModel::selectGroup
                        )
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
                        items(uiState.visibleChannels, key = { it.id + it.streamUrl }) { channel ->
                            IptvChannelTile(
                                channel = channel,
                                nowPlaying = viewModel.nowPlayingTitle(channel),
                                onClick = { viewModel.playChannel(channel) },
                                onLongClick = { guideChannel = channel }
                            )
                        }
                    }
                }
            }
        }
    }

    val currentGuideChannel = guideChannel
    if (currentGuideChannel != null) {
        IptvGuideDialog(
            channel = currentGuideChannel,
            programmes = viewModel.upcomingProgrammes(currentGuideChannel),
            onDismiss = { guideChannel = null }
        )
    }
}

@Composable
private fun IptvMessage(text: String, actionLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.padding(top = NuvioTheme.spacing.md))
        androidx.tv.material3.Button(onClick = onClick) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun GroupFilterRow(groups: List<String>, selectedGroup: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTheme.spacing.sm)
    ) {
        item {
            GroupChip(
                label = stringResource(R.string.iptv_all_groups),
                selected = selectedGroup == null,
                onClick = { onSelect(null) }
            )
        }
        items(groups) { group ->
            GroupChip(label = group, selected = selectedGroup == group, onClick = { onSelect(group) })
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val backgroundColor = when {
        isFocused -> NuvioTheme.colors.Primary
        selected -> NuvioTheme.colors.Surface
        else -> NuvioTheme.colors.Surface.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(
                border = if (selected && !isFocused) BorderStroke(1.dp, NuvioTheme.colors.Primary) else BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(20.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) Color.Black else NuvioTheme.colors.TextPrimary
        )
    }
}

@Composable
private fun IptvChannelTile(
    channel: IptvChannel,
    nowPlaying: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(150),
        label = "iptvTileScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.none,
        animationSpec = tween(120),
        label = "iptvTileBorder"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(NuvioTheme.colors.Surface)
            .border(
                border = if (isFocused) NuvioTheme.focusRing.border(borderWidth) else BorderStroke(borderWidth, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(),
                contentDescription = channel.name,
                modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(2f),
                contentScale = ContentScale.Fit
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (nowPlaying != null) {
                Text(
                    text = nowPlaying,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun IptvGuideDialog(channel: IptvChannel, programmes: List<EpgProgramme>, onDismiss: () -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = channel.name, subtitle = null, width = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            if (programmes.isEmpty()) {
                Text(
                    text = stringResource(R.string.iptv_no_guide_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                programmes.take(10).forEach { programme ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        Text(
                            text = timeFormat.format(programme.startMillis),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.Primary
                        )
                        Text(
                            text = programme.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
