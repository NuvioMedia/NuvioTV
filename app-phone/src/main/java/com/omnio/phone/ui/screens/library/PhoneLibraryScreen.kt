package com.omnio.phone.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnio.phone.R
import com.omnio.phone.ui.screens.home.PosterCard
import com.omnio.tv.domain.model.MetaPreview

@Composable
fun PhoneLibraryScreen(
    onItemClick: (MetaPreview) -> Unit,
    viewModel: PhoneLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryControlsRow(
            tabs = state.tabs,
            selected = state.selectedTab,
            onSelect = viewModel::onSelectTab,
            sortOptions = state.availableSortOptions,
            selectedSort = state.selectedSortOption,
            onSelectSort = viewModel::onSelectSortOption,
            sortEnabled = state.totalItemCount > 0
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> LoadingState()
                state.totalItemCount == 0 -> EmptyState()
                state.items.isEmpty() -> EmptyTabState()
                else -> LibraryGrid(items = state.items, onItemClick = onItemClick)
            }
        }
    }
}

@Composable
private fun LibraryControlsRow(
    tabs: List<PhoneLibraryTab>,
    selected: PhoneLibraryTabKey,
    onSelect: (PhoneLibraryTabKey) -> Unit,
    sortOptions: List<PhoneLibrarySortOption>,
    selectedSort: PhoneLibrarySortOption,
    onSelectSort: (PhoneLibrarySortOption) -> Unit,
    sortEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryTabRow(
            tabs = tabs,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
        SortMenu(
            options = sortOptions,
            selected = selectedSort,
            onSelect = onSelectSort,
            enabled = sortEnabled
        )
    }
}

@Composable
private fun LibraryTabRow(
    tabs: List<PhoneLibraryTab>,
    selected: PhoneLibraryTabKey,
    onSelect: (PhoneLibraryTabKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = tabs.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    SecondaryTabRow(selectedTabIndex = selectedIndex, modifier = modifier) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.key == selected,
                onClick = { onSelect(tab.key) },
                text = {
                    Text(
                        text = "${stringResource(tab.labelResId)} (${tab.count})",
                        maxLines = 1
                    )
                }
            )
        }
    }
}

@Composable
private fun SortMenu(
    options: List<PhoneLibrarySortOption>,
    selected: PhoneLibrarySortOption,
    onSelect: (PhoneLibrarySortOption) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.cd_library_sort)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Text(
                text = stringResource(R.string.library_sort_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelResId)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LibraryGrid(
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { "${it.apiType}:${it.id}" }) { item ->
            PosterCard(
                item = item,
                onClick = onItemClick,
                modifier = Modifier.fillMaxWidth()
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
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.library_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyTabState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.library_empty_tab_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.library_empty_tab_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
