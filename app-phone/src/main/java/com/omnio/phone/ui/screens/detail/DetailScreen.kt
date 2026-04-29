package com.omnio.phone.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnio.tv.domain.model.Meta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onPlayRequest: (PlaybackRequest) -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.userMessage) {
        val message = state.userMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    LaunchedEffect(viewModel) {
        viewModel.playbackRequests.collect(onPlayRequest)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = state.meta?.name.takeUnless { it.isNullOrBlank() } ?: ""
                    Text(text = title, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            if (state.meta != null) {
                PlayBottomBar(
                    isInLibrary = state.isInLibrary,
                    isResolving = state.isResolvingPlayback,
                    onPlay = { viewModel.requestPlayback() },
                    onChooseSource = { viewModel.openSourceSelection() },
                    onToggleLibrary = viewModel::toggleLibrary
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> LoadingState()
                state.error != null -> ErrorState(
                    message = state.error ?: "Something went wrong loading this title.",
                    onRetry = viewModel::retry
                )
                state.meta == null -> EmptyState()
                else -> DetailContent(
                    listState = listState,
                    meta = state.meta!!,
                    seasons = state.seasons,
                    selectedSeason = state.selectedSeason,
                    episodes = state.episodesForSeason,
                    onSelectSeason = viewModel::selectSeason,
                    onEpisodeClick = { video -> viewModel.requestPlayback(video) }
                )
            }
        }
    }

    state.streamSelection?.let { selection ->
        StreamSelectionSheet(
            state = selection,
            onDismiss = viewModel::dismissSourceSelection,
            onSelectStream = viewModel::selectStream,
            onSetAddonFilter = viewModel::setAddonFilter
        )
    }
}

@Composable
private fun DetailContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    meta: Meta,
    seasons: List<Int>,
    selectedSeason: Int?,
    episodes: List<com.omnio.tv.domain.model.Video>,
    onSelectSeason: (Int) -> Unit,
    onEpisodeClick: (com.omnio.tv.domain.model.Video) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "hero") { DetailHero(meta = meta) }
        item(key = "meta") { DetailMetaSection(meta = meta) }
        meta.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(key = "description") { DetailDescription(description = description) }
        }
        if (seasons.isNotEmpty() && selectedSeason != null) {
            item(key = "episodes") {
                DetailEpisodeList(
                    seasons = seasons,
                    selectedSeason = selectedSeason,
                    episodes = episodes,
                    onSelectSeason = onSelectSeason,
                    onEpisodeClick = onEpisodeClick
                )
            }
        }
    }
}

@Composable
private fun PlayBottomBar(
    isInLibrary: Boolean,
    isResolving: Boolean,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
    onToggleLibrary: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlay,
                enabled = !isResolving,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(text = "Loading…")
                } else {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Text(
                        text = "Play",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onChooseSource,
                enabled = !isResolving,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = "Choose source"
                )
            }
            OutlinedButton(
                onClick = onToggleLibrary,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isInLibrary) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (isInLibrary) "Remove from library" else "Add to library"
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Something went wrong loading this title.",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "We couldn't find this title.",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
    }
}
