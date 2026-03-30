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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val textFocusRequester = remember { FocusRequester() }
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var isVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            isVisible = true
        }

        val scale by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0.8f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "dialogScale"
        )
        val alpha by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = tween(durationMillis = 300, easing = LinearEasing),
            label = "dialogAlpha"
        )

        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .fillMaxWidth(0.85f)
                .background(NuvioColors.BackgroundCard, shape)
                .border(BorderStroke(2.dp, NuvioColors.FocusRing), shape)
                .padding(28.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update_title),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = NuvioColors.TextPrimary
                            )

                            if (state.update != null && state.isUpdateAvailable && !showDownloadMode && state.downloadedApkPath == null) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    NuvioColors.Primary.copy(alpha = 0.25f),
                                                    NuvioColors.Primary.copy(alpha = 0.05f)
                                                )
                                            ),
                                            shape = RoundedCornerShape(percent = 50)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = NuvioColors.Primary.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(percent = 50)
                                        )
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = state.update.tag,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = NuvioColors.Primary
                                    )
                                }
                            }
                        }

                        val subtitle = when {
                            state.errorMessage != null -> state.errorMessage
                            state.isChecking -> stringResource(R.string.update_checking)
                            state.downloadedApkPath != null -> stringResource(R.string.update_download_complete)
                            state.update != null && !state.isUpdateAvailable -> stringResource(R.string.update_up_to_date)
                            else -> ""
                        }

                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val rawNotes = state.update?.notes
                val displayNotes = remember(rawNotes) {
                    if (rawNotes.isNullOrBlank()) {
                        null
                    } else {
                        rawNotes
                    }
                }

                if (!showDownloadMode && displayNotes != null) {
                    val scrollState = rememberScrollState()
                    val isTextContainerFocused = remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    val focusRingColor = NuvioColors.FocusRing
                    val bgColorsStart = NuvioColors.BackgroundCard
                    val focusBgColor = NuvioColors.FocusBackground.copy(alpha = 0.2f)
                    
                    val textContainerBg = remember { Animatable(Color.Transparent) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .drawBehind {
                                drawRect(textContainerBg.value)
                            }
                            .onFocusChanged { state -> 
                                isTextContainerFocused.value = state.isFocused
                                coroutineScope.launch {
                                    textContainerBg.animateTo(
                                        targetValue = if (state.isFocused) focusBgColor else Color.Transparent,
                                        animationSpec = tween(durationMillis = 150)
                                    )
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            if (scrollState.value < scrollState.maxValue) {
                                                coroutineScope.launch { scrollState.animateScrollBy(150f) }
                                                true
                                            } else false
                                        }
                                        Key.DirectionUp -> {
                                            if (scrollState.value > 0) {
                                                coroutineScope.launch { scrollState.animateScrollBy(-150f) }
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .focusable()
                            .focusRequester(textFocusRequester)
                            .drawWithContent {
                                drawContent()
                                
                                val scrollMax = scrollState.maxValue.toFloat()
                                val scrollValue = scrollState.value.toFloat()

                                // Fading Edges (Top & Bottom gradient)
                                if (scrollValue > 0f) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(bgColorsStart, Color.Transparent),
                                            startY = 0f,
                                            endY = 24.dp.toPx()
                                        ),
                                        topLeft = Offset.Zero,
                                        size = Size(size.width, 24.dp.toPx())
                                    )
                                }
                                
                                if (scrollValue < scrollMax) {
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, bgColorsStart),
                                            startY = size.height - 24.dp.toPx(),
                                            endY = size.height
                                        ),
                                        topLeft = Offset(0f, size.height - 24.dp.toPx()),
                                        size = Size(size.width, 24.dp.toPx())
                                    )
                                }

                                // Scrollbar
                                if (scrollMax > 0f) {
                                    val scrollBarHeight = (size.height / (scrollMax + size.height)) * size.height
                                    val offsetY = (scrollValue / scrollMax) * (size.height - scrollBarHeight)
                                    val scrollbarAlpha = if (isTextContainerFocused.value) 0.8f else 0.3f
                                    
                                    drawRoundRect(
                                        color = focusRingColor.copy(alpha = scrollbarAlpha),
                                        topLeft = Offset(size.width - 6.dp.toPx(), offsetY),
                                        size = Size(3.dp.toPx(), scrollBarHeight),
                                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                    )
                                }

                                // Focus Ring
                                if (isTextContainerFocused.value) {
                                    drawRoundRect(
                                        color = focusRingColor,
                                        style = Stroke(width = 2.dp.toPx()),
                                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp) // Added horizontal padding for scrollbar clearance
                            .verticalScroll(scrollState)
                    ) {
                        Markdown(
                            content = displayNotes,
                            modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                            colors = markdownColor(text = NuvioColors.TextSecondary),
                            typography = markdownTypography(
                                paragraph = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                                h1 = MaterialTheme.typography.titleLarge.copy(color = NuvioColors.TextPrimary),
                                h2 = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary),
                                h3 = MaterialTheme.typography.bodyLarge.copy(color = NuvioColors.TextPrimary)
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
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
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
                                containerColor = NuvioColors.Background,
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
                                containerColor = NuvioColors.Background,
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
                                containerColor = NuvioColors.Background,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary,
                                disabledContainerColor = NuvioColors.Background.copy(alpha = 0.5f),
                                disabledContentColor = NuvioColors.TextPrimary.copy(alpha = 0.5f)
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(if (state.isDownloading) stringResource(R.string.update_downloading_ellipsis) else stringResource(R.string.update_download))
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
        } // end centering Box
    }

    LaunchedEffect(state.showDialog, hasPrimaryAction, state.downloadedApkPath, state.showUnknownSourcesDialog, state.isUpdateAvailable, state.update?.notes) {
        // Defer focus until after the dialog subtree has been committed.
        withFrameNanos { }

        runCatching {
            val hasNotes = !state.update?.notes.isNullOrBlank()
            if (!state.isDownloading && hasNotes) {
                textFocusRequester.requestFocus()
            } else if (hasPrimaryAction) {
                primaryFocusRequester.requestFocus()
            } else {
                closeFocusRequester.requestFocus()
            }
        }
    }
}
