package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.trakt.TraktPublicListSearchResult
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TraktSourcePickerContent(
    uiState: CollectionEditorUiState,
    isEditing: Boolean,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onMediaBothChange: (Boolean) -> Unit,
    onSortChange: (String) -> Unit,
    onSortHowChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAddFromInput: () -> Unit,
    onAddResult: (TraktPublicListSearchResult) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    if (isEditing) R.string.collections_editor_edit_trakt_source else R.string.collections_editor_trakt_sources
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        uiState.traktSearchError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(error, color = NuvioColors.Error, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TraktSourceForm(
                    uiState = uiState,
                    isEditing = isEditing,
                    onInputChange = onInputChange,
                    onTitleChange = onTitleChange,
                    onMediaTypeChange = onMediaTypeChange,
                    onMediaBothChange = onMediaBothChange,
                    onSortChange = onSortChange,
                    onSortHowChange = onSortHowChange,
                    onSearch = onSearch,
                    onAddFromInput = onAddFromInput
                )
            }
            if (uiState.traktSearchResults.isNotEmpty()) {
                item { TraktSectionTitle(stringResource(R.string.collections_editor_trakt_search_results)) }
                items(uiState.traktSearchResults) { result ->
                    TraktResultCard(result = result, onClick = { onAddResult(result) })
                }
            }
            if (uiState.traktTrendingResults.isNotEmpty()) {
                item { TraktSectionTitle(stringResource(R.string.collections_editor_trakt_trending)) }
                items(uiState.traktTrendingResults) { result ->
                    TraktResultCard(result = result, onClick = { onAddResult(result) })
                }
            }
            if (uiState.traktPopularResults.isNotEmpty()) {
                item { TraktSectionTitle(stringResource(R.string.collections_editor_trakt_popular)) }
                items(uiState.traktPopularResults) { result ->
                    TraktResultCard(result = result, onClick = { onAddResult(result) })
                }
            }
        }
    }
}

@Composable
private fun TraktSourceForm(
    uiState: CollectionEditorUiState,
    isEditing: Boolean,
    onInputChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onMediaBothChange: (Boolean) -> Unit,
    onSortChange: (String) -> Unit,
    onSortHowChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAddFromInput: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TraktLabeledField(
            label = stringResource(R.string.collections_editor_trakt_list),
            value = uiState.traktInput,
            onValueChange = onInputChange,
            placeholder = "Search title, Trakt URL, or list ID"
        )
        TraktLabeledField(
            label = stringResource(R.string.collections_editor_tmdb_display_title),
            value = uiState.traktTitleInput,
            onValueChange = onTitleChange,
            placeholder = "Weekend Watch, Award Winners"
        )
        TraktMediaSortControls(
            mediaType = uiState.traktMediaType,
            bothSelected = uiState.traktMediaBoth,
            sortBy = uiState.traktSortBy,
            sortHow = uiState.traktSortHow,
            onMediaTypeChange = onMediaTypeChange,
            onBothChange = onMediaBothChange,
            onSortChange = onSortChange,
            onSortHowChange = onSortHowChange
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmdbActionButton(onClick = onSearch, primary = false) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.collections_editor_tmdb_search))
            }
            TmdbActionButton(onClick = onAddFromInput, primary = true) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(if (isEditing) R.string.collections_editor_save_source else R.string.collections_editor_add_source))
            }
        }
    }
}

@Composable
private fun TraktLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextPrimary)
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder
        )
    }
}

@Composable
private fun TraktMediaSortControls(
    mediaType: TmdbCollectionMediaType,
    bothSelected: Boolean,
    sortBy: String,
    sortHow: String,
    onMediaTypeChange: (TmdbCollectionMediaType) -> Unit,
    onBothChange: (Boolean) -> Unit,
    onSortChange: (String) -> Unit,
    onSortHowChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TmdbOptionRow(label = stringResource(R.string.library_filter_type)) {
            TmdbChoiceButton(
                label = stringResource(R.string.type_movie),
                selected = mediaType == TmdbCollectionMediaType.MOVIE && !bothSelected,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.MOVIE) }
            )
            TmdbChoiceButton(
                label = stringResource(R.string.type_series),
                selected = mediaType == TmdbCollectionMediaType.TV && !bothSelected,
                onClick = { onMediaTypeChange(TmdbCollectionMediaType.TV) }
            )
            TmdbChoiceButton(
                label = "Both",
                selected = bothSelected,
                onClick = { onBothChange(true) }
            )
        }
        TmdbOptionRow(label = stringResource(R.string.library_filter_sort)) {
            traktSortOptions().forEach { (value, label) ->
                TmdbChoiceButton(
                    label = label,
                    selected = sortBy == value,
                    onClick = { onSortChange(value) }
                )
            }
        }
        TmdbOptionRow(label = stringResource(R.string.collections_editor_trakt_direction)) {
            TmdbChoiceButton(
                label = stringResource(R.string.collections_editor_trakt_ascending),
                selected = sortHow == "asc",
                onClick = { onSortHowChange("asc") }
            )
            TmdbChoiceButton(
                label = stringResource(R.string.collections_editor_trakt_descending),
                selected = sortHow == "desc",
                onClick = { onSortHowChange("desc") }
            )
        }
    }
}

@Composable
private fun TraktSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
}

@Composable
private fun TraktResultCard(result: TraktPublicListSearchResult, onClick: () -> Unit) {
    TmdbPickerCard(
        title = result.title,
        subtitle = result.subtitle,
        onClick = onClick
    )
}

private fun traktSortOptions(): List<Pair<String, String>> {
    return listOf(
        "rank" to "List Order",
        "added" to "Recently Added",
        "title" to "Title",
        "released" to "Released",
        "popularity" to "Popular",
        "votes" to "Votes"
    )
}
