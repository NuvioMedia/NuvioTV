package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.wetrakr.WeTrakrUploadGroup
import com.nuvio.tv.data.wetrakr.WeTrakrUploadKind
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

internal object WeTrakrLocalUploadTestTags {
    const val WATCHED = "wetrakr_local_upload_watched"
    const val LIBRARY = "wetrakr_local_upload_library"
    const val UNMATCHED = "wetrakr_local_upload_unmatched"
    const val PROGRESS = "wetrakr_local_upload_progress"
}

@Composable
internal fun WeTrakrLocalUploadDialog(
    state: WeTrakrLocalUploadUiState,
    onToggle: (WeTrakrUploadKind) -> Unit,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var showUnmatched by remember { mutableStateOf(false) }
    if (showUnmatched) {
        WeTrakrUnmatchedDialog(state = state, onDismiss = { showUnmatched = false })
        return
    }
    val firstFocusRequester = remember { FocusRequester() }
    val actionFocusRequester = remember { FocusRequester() }
    val preview = state.preview
    val run = state.run
    LaunchedEffect(preview != null) {
        if (preview != null && run == null) firstFocusRequester.requestFocusAfterFrames()
    }
    LaunchedEffect(run?.status) {
        if (run != null) actionFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.wetrakr_local_upload_title),
        subtitle = stringResource(R.string.wetrakr_local_upload_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        when {
            run != null -> Text(
                text = state.runMessage.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.testTag(WeTrakrLocalUploadTestTags.PROGRESS)
            )
            preview != null -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                preview.groups.forEachIndexed { index, group ->
                    SettingsToggleRow(
                        title = stringResource(group.kind.titleRes()),
                        subtitle = group.summary(),
                        checked = group.kind in state.selected,
                        onToggle = { onToggle(group.kind) },
                        enabled = group.toUpload > 0,
                        modifier = Modifier
                            .testTag(group.kind.testTag())
                            .let { if (index == 0) it.focusRequester(firstFocusRequester) else it }
                    )
                }
                val unmatched = preview.unmatched.sumOf { it.count }
                if (unmatched > 0) {
                    SettingsActionRow(
                        title = stringResource(R.string.wetrakr_local_upload_unmatched, unmatched),
                        subtitle = stringResource(R.string.wetrakr_local_upload_unmatched_subtitle),
                        value = stringResource(R.string.wetrakr_local_upload_view),
                        onClick = { showUnmatched = true },
                        modifier = Modifier.testTag(WeTrakrLocalUploadTestTags.UNMATCHED)
                    )
                }
                Text(
                    text = if (state.uploadCount == 0) {
                        stringResource(R.string.wetrakr_local_upload_nothing)
                    } else {
                        pluralStringResource(R.plurals.wetrakr_local_upload_requests, state.requestCount, state.requestCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            state.isScanning -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(modifier = Modifier.padding(NuvioTheme.spacing.md).size(28.dp))
                    Text(
                        text = stringResource(R.string.wetrakr_local_upload_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            if (run?.isActive == true) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(actionFocusRequester)
                )
            } else if (run == null && preview != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.wetrakr_local_upload_sync),
                    onClick = onSync,
                    primary = true,
                    enabled = state.uploadCount > 0
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = if (run != null && !run.isActive) Modifier.focusRequester(actionFocusRequester) else Modifier
            )
        }
    }
}

@Composable
private fun WeTrakrUnmatchedDialog(
    state: WeTrakrLocalUploadUiState,
    onDismiss: () -> Unit
) {
    val titles = state.preview?.unmatched.orEmpty()
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(titles.isNotEmpty()) {
        if (titles.isNotEmpty()) firstFocusRequester.requestFocusAfterFrames()
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.wetrakr_local_upload_unmatched, titles.sumOf { it.count }),
        subtitle = stringResource(R.string.wetrakr_local_upload_unmatched_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
        ) {
            itemsIndexed(titles) { index, title ->
                SettingsActionRow(
                    title = title.title,
                    subtitle = stringResource(title.kind.titleRes()),
                    value = title.count.takeIf { it > 1 }?.let {
                        pluralStringResource(R.plurals.wetrakr_local_upload_entries, it, it)
                    },
                    onClick = {},
                    modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier
                )
            }
        }
    }
}

@Composable
private fun WeTrakrUploadGroup.summary(): String =
    stringResource(R.string.wetrakr_local_upload_counts, toUpload, alreadyOnWeTrakr)

private fun WeTrakrUploadKind.titleRes(): Int = when (this) {
    WeTrakrUploadKind.WATCHED -> R.string.wetrakr_local_upload_watched
    WeTrakrUploadKind.LIBRARY -> R.string.wetrakr_local_upload_library
}

private fun WeTrakrUploadKind.testTag(): String = when (this) {
    WeTrakrUploadKind.WATCHED -> WeTrakrLocalUploadTestTags.WATCHED
    WeTrakrUploadKind.LIBRARY -> WeTrakrLocalUploadTestTags.LIBRARY
}
