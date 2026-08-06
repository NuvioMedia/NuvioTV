@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.coroutines.launch

@Composable
internal fun UpdateReleaseNotesDialog(
    update: AppUpdate,
    onDismiss: () -> Unit
) {
    val notesFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val focusRingColor = NuvioTheme.colors.FocusRing

    LaunchedEffect(update.tag) {
        notesFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.update_release_notes),
        subtitle = update.title,
        width = 680.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .onFocusChanged { isFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (scrollState.value < scrollState.maxValue) {
                                    coroutineScope.launch { scrollState.animateScrollBy(160f) }
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (scrollState.value > 0) {
                                    coroutineScope.launch { scrollState.animateScrollBy(-160f) }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
                .focusRequester(notesFocusRequester)
                .focusable()
                .drawWithContent {
                    drawContent()
                    val maxScroll = scrollState.maxValue.toFloat()
                    if (maxScroll > 0f) {
                        val thumbHeight = (size.height / (maxScroll + size.height) * size.height)
                            .coerceAtLeast(32.dp.toPx())
                        val thumbOffset = scrollState.value / maxScroll * (size.height - thumbHeight)
                        drawRoundRect(
                            color = focusRingColor.copy(alpha = if (isFocused) 0.85f else 0.35f),
                            topLeft = Offset(size.width - 6.dp.toPx(), thumbOffset),
                            size = Size(3.dp.toPx(), thumbHeight),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
                .verticalScroll(scrollState)
        ) {
            Markdown(
                content = update.notes.ifBlank { stringResource(R.string.update_no_release_notes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = NuvioTheme.spacing.sm),
                colors = markdownColor(text = NuvioTheme.colors.TextSecondary),
                typography = markdownTypography(
                    paragraph = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    h1 = MaterialTheme.typography.titleLarge.copy(color = NuvioTheme.colors.TextPrimary),
                    h2 = MaterialTheme.typography.titleMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    h3 = MaterialTheme.typography.bodyLarge.copy(color = NuvioTheme.colors.TextPrimary)
                )
            )
        }
    }
}

@Composable
internal fun UpdateUnknownSourcesDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val settingsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        settingsFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.update_title),
        subtitle = stringResource(R.string.update_unknown_sources),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(settingsFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.update_open_settings))
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.update_close))
            }
        }
    }
}
