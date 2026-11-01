package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.wetrakr.WeTrakrUploadGroup
import com.nuvio.tv.data.wetrakr.WeTrakrUploadKind
import com.nuvio.tv.data.wetrakr.WeTrakrUploadStatus
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberRawSvgPainter

internal object WeTrakrLocalUploadTestTags {
    const val WATCHED = "wetrakr_local_upload_watched"
    const val LIBRARY = "wetrakr_local_upload_library"
    const val UNMATCHED = "wetrakr_local_upload_unmatched"
    const val NOT_FOUND = "wetrakr_local_upload_not_found"
    const val PROGRESS = "wetrakr_local_upload_progress"
}

private enum class WeTrakrUploadTitleList { UNMATCHED, NOT_FOUND }

@Composable
internal fun WeTrakrLocalUploadDialog(
    state: WeTrakrLocalUploadUiState,
    onToggle: (WeTrakrUploadKind) -> Unit,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var titleList by remember { mutableStateOf<WeTrakrUploadTitleList?>(null) }
    titleList?.let { list ->
        WeTrakrUploadTitlesDialog(state = state, list = list, onDismiss = { titleList = null })
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

    WeTrakrBrandDialog(
        title = stringResource(
            if (run?.status == WeTrakrUploadStatus.DONE) R.string.wetrakr_local_upload_summary else R.string.wetrakr_local_upload_title
        ),
        subtitle = stringResource(R.string.wetrakr_local_upload_subtitle).takeIf { run == null },
        onDismiss = onDismiss
    ) {
        when {
            run != null -> Box(modifier = Modifier.testTag(WeTrakrLocalUploadTestTags.PROGRESS)) {
                TrackingBrandMessage(
                    text = state.runMessage.orEmpty(),
                    isError = run.status == WeTrakrUploadStatus.FAILED
                )
            }
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
                        onClick = { titleList = WeTrakrUploadTitleList.UNMATCHED },
                        modifier = Modifier.testTag(WeTrakrLocalUploadTestTags.UNMATCHED)
                    )
                }
                val notFound = preview.notFound.sumOf { it.count }
                if (notFound > 0) {
                    SettingsActionRow(
                        title = stringResource(R.string.wetrakr_local_upload_not_found, notFound),
                        subtitle = stringResource(R.string.wetrakr_local_upload_not_found_subtitle),
                        value = stringResource(R.string.wetrakr_local_upload_view),
                        onClick = { titleList = WeTrakrUploadTitleList.NOT_FOUND },
                        modifier = Modifier.testTag(WeTrakrLocalUploadTestTags.NOT_FOUND)
                    )
                }
                Text(
                    text = if (state.uploadCount == 0) {
                        stringResource(R.string.wetrakr_local_upload_nothing)
                    } else {
                        pluralStringResource(R.plurals.wetrakr_local_upload_requests, state.requestCount, state.requestCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.76f)
                )
            }
            state.isScanning -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(modifier = Modifier.padding(NuvioTheme.spacing.md).size(28.dp), color = Color.White)
                    Text(
                        text = stringResource(R.string.wetrakr_local_upload_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.76f)
                    )
                }
            }
        }
        state.errorMessage?.let { message -> TrackingBrandMessage(text = message, isError = true) }
        if (run == null && preview != null) {
            TrackingBrandPrimaryButton(
                label = stringResource(R.string.wetrakr_local_upload_sync),
                loading = false,
                enabled = state.uploadCount > 0,
                onClick = onSync
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (run?.isActive == true) {
                TrackingBrandFooterButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(actionFocusRequester)
                )
            }
            TrackingBrandFooterButton(
                text = stringResource(R.string.action_close),
                onClick = onDismiss,
                modifier = if (run != null && !run.isActive) Modifier.focusRequester(actionFocusRequester) else Modifier
            )
        }
    }
}

@Composable
private fun WeTrakrUploadTitlesDialog(
    state: WeTrakrLocalUploadUiState,
    list: WeTrakrUploadTitleList,
    onDismiss: () -> Unit
) {
    val titles = when (list) {
        WeTrakrUploadTitleList.UNMATCHED -> state.preview?.unmatched
        WeTrakrUploadTitleList.NOT_FOUND -> state.preview?.notFound
    }.orEmpty()
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(titles.isNotEmpty()) {
        if (titles.isNotEmpty()) firstFocusRequester.requestFocusAfterFrames()
    }
    val count = titles.sumOf { it.count }
    WeTrakrBrandDialog(
        title = when (list) {
            WeTrakrUploadTitleList.UNMATCHED -> stringResource(R.string.wetrakr_local_upload_unmatched, count)
            WeTrakrUploadTitleList.NOT_FOUND -> stringResource(R.string.wetrakr_local_upload_not_found, count)
        },
        subtitle = when (list) {
            WeTrakrUploadTitleList.UNMATCHED -> stringResource(R.string.wetrakr_local_upload_unmatched_subtitle)
            WeTrakrUploadTitleList.NOT_FOUND -> stringResource(R.string.wetrakr_local_upload_not_found_subtitle)
        },
        onDismiss = onDismiss
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
        TrackingBrandFooterButton(text = stringResource(R.string.action_close), onClick = onDismiss)
    }
}

@Composable
private fun WeTrakrBrandDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val glyph = rememberRawSvgPainter(R.raw.wetrakr_icon, 150.dp)
    val logo = rememberRawSvgPainter(R.raw.wetrakr_logo, 160.dp)
    ConnectedTrackingAccountDialog(TrackingDialogBrand.WETRAKR, glyph, onDismiss) {
        TrackingConnectedWordmark(
            brand = TrackingDialogBrand.WETRAKR,
            logo = logo,
            contentDescription = stringResource(R.string.wetrakr_name)
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.76f)
                )
            }
        }
        content()
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
