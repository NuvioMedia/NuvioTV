package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mdblist.MdbListLibraryListOption
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MdbListLibraryListsDialog(
    state: MdbListLibraryListsUiState,
    onToggle: (MdbListLibraryListOption) -> Unit,
    onDismiss: () -> Unit
) {
    val firstListFocusRequester = remember { FocusRequester() }
    val hasLists = state.lists.isNotEmpty()
    LaunchedEffect(hasLists) {
        if (hasLists) firstListFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.mdblist_library_lists),
        subtitle = stringResource(R.string.mdblist_library_lists_description),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        when {
            hasLists -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                itemsIndexed(state.lists, key = { _, option -> option.key }) { index, option ->
                    // Taps are ignored while a change is saving; the row stays focusable so focus never jumps.
                    SettingsToggleRow(
                        title = option.name,
                        subtitle = null,
                        checked = option.visible,
                        onToggle = { onToggle(option) },
                        modifier = if (index == 0) Modifier.focusRequester(firstListFocusRequester) else Modifier
                    )
                }
            }
            state.isLoading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.padding(NuvioTheme.spacing.md).size(28.dp))
            }
            else -> Text(
                text = stringResource(R.string.mdblist_library_lists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
    }
}
