package com.nuvio.tv.updater.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.updater.UpdateUiState
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdatePromptDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onOpenUnknownSources: () -> Unit
) {
    if (!state.showDialog) return

    val closeFocusRequester = remember { FocusRequester() }
    val primaryFocusRequester = remember { FocusRequester() }
    val canDownload = state.isUpdateAvailable && state.update != null
    val showDownloadMode = state.isDownloading
    var installButtonEnabled by remember(state.downloadedApkPath) {
        mutableStateOf(state.downloadedApkPath == null)
    }
    val hasPrimaryAction = state.showUnknownSourcesDialog ||
        state.downloadedApkPath != null ||
        canDownload

    LaunchedEffect(state.downloadedApkPath) {
        if (state.downloadedApkPath != null) {
            // Prevent accidental install click from the same D-pad/OK press that started download.
            installButtonEnabled = false
            delay(700)
            installButtonEnabled = true
        } else {
            installButtonEnabled = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(NuvioColors.BackgroundCard, shape)
                .border(BorderStroke(2.dp, NuvioColors.FocusRing), shape)
                .padding(32.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.update_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                val subtitle = when {
                    state.errorMessage != null -> state.errorMessage
                    state.isChecking -> "Checking for updates…"
                    state.downloadedApkPath != null -> "Download complete. Ready to install."
                    state.update != null && state.isUpdateAvailable -> "New version: ${state.update.tag}"
                    state.update != null && !state.isUpdateAvailable -> "You're up to date."
                    else -> ""
                }

                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val rawNotes = state.update?.notes
                val displayNotes = remember(rawNotes) {
                    if (rawNotes.isNullOrBlank()) {
                        null
                    } else {
                        val lines = rawNotes.lines()
                        val limited = lines.take(10).joinToString("\n")
                        if (lines.size > 10) "$limited\n\n…" else limited
                    }
                }

                if (!showDownloadMode && displayNotes != null && state.isUpdateAvailable) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Markdown(
                            content = displayNotes,
                            modifier = Modifier.fillMaxWidth(),
                            colors = markdownColor(text = NuvioColors.TextSecondary),
                            typography = markdownTypography(
                                paragraph = MaterialTheme.typography.bodySmall,
                                h1 = MaterialTheme.typography.titleMedium,
                                h2 = MaterialTheme.typography.titleSmall,
                                h3 = MaterialTheme.typography.bodyLarge
                            )
                        )
                    }
                }

                if (showDownloadMode) {
                    val pct = ((state.downloadProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
                    val animatedProgress by animateFloatAsState(
                        targetValue = (state.downloadProgress ?: 0f).coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 180),
                        label = "downloadProgress"
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.update_downloading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextSecondary
                            )
                            Text(
                                text = String.format("%3d%%", pct),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        color = NuvioColors.Background.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(999.dp)
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedProgress)
                                    .background(
                                        color = NuvioColors.Primary,
                                        shape = RoundedCornerShape(999.dp)
                                    )
                            )
                        }
                    }
                }

                if (state.showUnknownSourcesDialog) {
                    Text(
                        text = stringResource(R.string.update_unknown_sources),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(closeFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.Background,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text(stringResource(R.string.update_close))
                    }

                    if (state.showUnknownSourcesDialog) {
                        Button(
                            onClick = onOpenUnknownSources,
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(stringResource(R.string.update_open_settings))
                        }
                    } else if (state.downloadedApkPath != null) {
                        Button(
                            onClick = onInstall,
                            enabled = installButtonEnabled,
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(stringResource(R.string.update_install))
                        }
                    } else if (canDownload) {
                        Button(
                            onClick = onDownload,
                            enabled = !state.isDownloading,
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(if (state.isDownloading) "Downloading…" else "Download")
                        }

                        Button(
                            onClick = onIgnore,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.Background,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(stringResource(R.string.update_ignore))
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(state.showDialog, hasPrimaryAction, state.downloadedApkPath, state.showUnknownSourcesDialog, state.isUpdateAvailable) {
        // Defer focus until after the dialog subtree has been committed.
        withFrameNanos { }

        runCatching {
            if (hasPrimaryAction) {
                primaryFocusRequester.requestFocus()
            } else {
                closeFocusRequester.requestFocus()
            }
        }
    }
}
