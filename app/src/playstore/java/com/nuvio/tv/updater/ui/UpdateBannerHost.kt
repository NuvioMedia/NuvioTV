package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable
import com.nuvio.tv.updater.UpdateUiState

@Composable
fun UpdateBannerHost(
    state: UpdateUiState,
    onDismissBanner: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismissUnknownSources: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onFeedbackShown: () -> Unit,
    content: @Composable () -> Unit
) {
    content()
}
