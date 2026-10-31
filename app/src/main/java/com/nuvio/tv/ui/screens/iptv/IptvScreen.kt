@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvLoadStage
import com.nuvio.tv.data.iptv.IptvLoadStatus
import com.nuvio.tv.data.iptv.IptvPlaylist
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun IptvScreen(
    viewModel: IptvViewModel = hiltViewModel(),
    onPlayChannel: (IptvChannel) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val previewPlayer = rememberIptvPreviewPlayer()

    var showManager by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<IptvPlaylist?>(null) }

    val openEditor: (IptvPlaylist?) -> Unit = { playlist ->
        editingPlaylist = playlist
        showManager = false
        showEditor = true
    }

    val playFullscreen: (IptvChannel) -> Unit = { channel ->
        previewPlayer.stopIptvPreview()
        viewModel.onWatchFullscreen(channel)
        onPlayChannel(channel)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        val status = uiState.status
        when {
            !uiState.isInitialized -> Unit
            uiState.playlists.isEmpty() -> IptvMessageState(
                title = stringResource(R.string.iptv_empty_title),
                message = stringResource(R.string.iptv_empty_message),
                primaryLabel = stringResource(R.string.iptv_add_playlist),
                primaryIcon = Icons.Filled.Add,
                onPrimary = { openEditor(null) },
                requestFocus = !showEditor && !showManager
            )
            !uiState.hasAnyChannels && status is IptvLoadStatus.Error -> IptvMessageState(
                title = stringResource(R.string.iptv_load_failed),
                message = status.message,
                primaryLabel = stringResource(R.string.iptv_retry),
                primaryIcon = Icons.Filled.Refresh,
                onPrimary = viewModel::refresh,
                secondaryLabel = stringResource(R.string.iptv_manage_playlists),
                onSecondary = { showManager = true },
                requestFocus = !showEditor && !showManager
            )
            !uiState.hasAnyChannels -> IptvLoadingState(status = status)
            else -> IptvGuide(
                channels = uiState.channels,
                epg = uiState.epg,
                groups = uiState.groups,
                selectedGroupId = uiState.selectedGroupId,
                favorites = uiState.favorites,
                previewChannel = uiState.previewChannel,
                focusChannelKey = uiState.focusChannelKey,
                status = status,
                previewPlayer = previewPlayer,
                externalDialogOpen = showManager || showEditor,
                onSelectGroup = viewModel::selectGroup,
                onPreviewChannel = viewModel::previewChannel,
                onPlayChannel = playFullscreen,
                onToggleFavorite = viewModel::toggleFavorite,
                onManagePlaylists = { showManager = true },
                onRefresh = viewModel::refresh
            )
        }
    }

    if (showManager) {
        IptvPlaylistManagerDialog(
            playlists = uiState.playlists,
            onDismiss = { showManager = false },
            onAdd = { openEditor(null) },
            onEdit = { openEditor(it) },
            onRefresh = {
                showManager = false
                viewModel.refresh()
            }
        )
    }

    if (showEditor) {
        IptvPlaylistEditorDialog(
            initial = editingPlaylist,
            onDismiss = { showEditor = false },
            onSave = { playlist ->
                showEditor = false
                viewModel.savePlaylist(playlist)
            },
            onDelete = { playlist ->
                showEditor = false
                if (uiState.previewChannel?.playlistId == playlist.id) {
                    previewPlayer.stopIptvPreview()
                    viewModel.stopPreview()
                }
                viewModel.deletePlaylist(playlist.id)
            }
        )
    }
}

@Composable
private fun IptvMessageState(
    title: String,
    message: String,
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimary: () -> Unit,
    requestFocus: Boolean,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val primaryFocus = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.LiveTv,
            contentDescription = null,
            tint = NuvioTheme.colors.Secondary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 6,
            modifier = Modifier.widthIn(max = 620.dp)
        )
        Spacer(modifier = Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IptvButton(
                text = primaryLabel,
                icon = primaryIcon,
                primary = true,
                onClick = onPrimary,
                modifier = Modifier.focusRequester(primaryFocus)
            )
            if (secondaryLabel != null && onSecondary != null) {
                IptvButton(
                    text = secondaryLabel,
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    onClick = onSecondary
                )
            }
        }
    }
    LaunchedEffect(requestFocus) {
        if (!requestFocus) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { primaryFocus.requestFocus() }
    }
}

@Composable
private fun IptvLoadingState(status: IptvLoadStatus) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(20.dp))
        val loading = status as? IptvLoadStatus.Loading
        val name = loading?.playlistName.orEmpty()
        Text(
            text = if (loading?.stage == IptvLoadStage.EPG) {
                stringResource(R.string.iptv_loading_epg)
            } else {
                stringResource(R.string.iptv_loading_playlist)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        if (name.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}
