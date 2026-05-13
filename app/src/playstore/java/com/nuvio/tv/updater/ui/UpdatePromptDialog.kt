package com.nuvio.tv.updater.ui

import androidx.compose.runtime.Composable
import com.nuvio.tv.updater.UpdateUiState

@Composable
fun UpdatePromptDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onOpenUnknownSources: () -> Unit
) = Unit
