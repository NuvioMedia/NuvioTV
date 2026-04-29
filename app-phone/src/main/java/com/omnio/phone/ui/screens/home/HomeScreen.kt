package com.omnio.phone.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnio.phone.R
import com.omnio.tv.domain.model.CatalogRow
import com.omnio.tv.domain.model.MetaPreview
import com.omnio.tv.domain.model.WatchProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAddons: () -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit,
    onSeeAllClick: (CatalogRow) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        state = pullState,
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            state.installedAddonsCount == 0 && !state.isLoading ->
                EmptyAddonsState(onInstallClick = onNavigateToAddons)

            state.isLoading && state.rows.isEmpty() && state.continueWatching.isEmpty() ->
                LoadingState()

            state.rows.isEmpty() && state.continueWatching.isEmpty() && !state.isLoading ->
                EmptyCatalogsState(onManageAddons = onNavigateToAddons)

            else -> HomeFeed(
                heroItems = state.heroItems,
                rows = state.rows,
                continueWatching = state.continueWatching,
                onItemClick = onItemClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onSeeAllClick = onSeeAllClick
            )
        }
    }
}

@Composable
private fun HomeFeed(
    heroItems: List<MetaPreview>,
    rows: List<CatalogRow>,
    continueWatching: List<WatchProgress>,
    onItemClick: (MetaPreview) -> Unit,
    onContinueWatchingClick: (WatchProgress) -> Unit,
    onSeeAllClick: (CatalogRow) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        if (heroItems.isNotEmpty()) {
            item(key = "hero") {
                HeroCarousel(items = heroItems, onItemClick = onItemClick)
            }
        }
        if (continueWatching.isNotEmpty()) {
            item(key = "continue-watching") {
                ContinueWatchingRow(
                    items = continueWatching,
                    onItemClick = onContinueWatchingClick
                )
            }
        }
        items(rows, key = { "${it.addonId}:${it.apiType}:${it.catalogId}" }) { row ->
            CategoryRow(
                row = row,
                onItemClick = onItemClick,
                onSeeAllClick = onSeeAllClick
            )
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
private fun EmptyAddonsState(onInstallClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.home_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        )
        Button(onClick = onInstallClick) {
            Text(stringResource(R.string.home_empty_cta))
        }
    }
}

@Composable
private fun EmptyCatalogsState(onManageAddons: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.home_empty_no_catalogs_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.home_empty_no_catalogs_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp)
        )
        Button(onClick = onManageAddons) {
            Text(stringResource(R.string.home_empty_manage_addons))
        }
    }
}
