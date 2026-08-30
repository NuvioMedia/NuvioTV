@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

@Composable
fun SynopsisDescription(
    description: String,
    onShowFullDescription: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 8,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onTruncationChanged: (Boolean) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    var isTruncated by rememberSaveable(description, maxLines) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val highlightInset = 12.dp

    LaunchedEffect(isTruncated) {
        onTruncationChanged(isTruncated)
    }

    Column(
        modifier = modifier.then(
            if (isTruncated) {
                Modifier
                    .offset(x = -highlightInset)
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) onFocused()
                    }
                    .background(
                        color = if (isFocused) {
                            Color.White.copy(alpha = 0.10f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .then(
                        if (upFocusRequester != null || downFocusRequester != null) {
                            Modifier.focusProperties {
                                if (upFocusRequester != null) up = upFocusRequester
                                if (downFocusRequester != null) down = downFocusRequester
                            }
                        } else {
                            Modifier
                        }
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onShowFullDescription
                    )
                    .padding(horizontal = highlightInset, vertical = 8.dp)
            } else {
                Modifier
            }
        )
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (result.hasVisualOverflow != isTruncated) {
                    isTruncated = result.hasVisualOverflow
                }
            }
        )
        if (isTruncated) {
            Text(
                text = stringResource(R.string.hero_synopsis_read_more),
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) {
                    NuvioTheme.colors.TextPrimary
                } else {
                    NuvioTheme.extendedColors.textSecondary
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun SynopsisOverlay(
    title: String,
    description: String,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val contentFocusRequester = remember { FocusRequester() }
    var requestedScrollPosition by remember { mutableIntStateOf(0) }
    var scrollAnimationJob by remember { mutableStateOf<Job?>(null) }

    fun requestSmoothScroll(target: Int) {
        requestedScrollPosition = target.coerceIn(0, scrollState.maxValue)
        if (scrollAnimationJob?.isActive == true) return

        scrollAnimationJob = coroutineScope.launch {
            scrollState.scroll {
                var previousFrame = withFrameNanos { it }
                while (true) {
                    val frame = withFrameNanos { it }
                    val elapsedSeconds = ((frame - previousFrame) / 1_000_000_000f)
                        .coerceIn(0f, 0.05f)
                    previousFrame = frame

                    val targetPosition = requestedScrollPosition.toFloat()
                    val distance = targetPosition - scrollState.value.toFloat()
                    if (abs(distance) < 0.5f) {
                        scrollBy(distance)
                        if (requestedScrollPosition == targetPosition.toInt()) break
                        continue
                    }

                    val smoothing = 1f - exp(-12f * elapsedSeconds)
                    scrollBy(distance * smoothing)
                }
            }
        }
    }

    LaunchedEffect(description) {
        scrollAnimationJob?.cancel()
        requestedScrollPosition = 0
        scrollState.scrollTo(0)
        contentFocusRequester.requestFocusAfterFrames()
        scrollState.scrollTo(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF070707),
                            Color(0xFF101010),
                            Color(0xFF151515)
                        )
                    )
                )
                .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .weight(1f)
                        .drawWithContent {
                            drawContent()

                            val maxScroll = scrollState.maxValue.toFloat()
                            if (maxScroll > 0f) {
                                val viewportHeight = size.height
                                val trackInset = 8.dp.toPx()
                                val trackHeight = (viewportHeight - trackInset * 2f).coerceAtLeast(0f)
                                val contentHeight = viewportHeight + maxScroll
                                val thumbHeight = (trackHeight * viewportHeight / contentHeight)
                                    .coerceAtLeast(36.dp.toPx())
                                    .coerceAtMost(trackHeight)
                                val thumbTop = trackInset + (trackHeight - thumbHeight) *
                                    (scrollState.value.toFloat() / maxScroll)
                                val scrollbarX = size.width - 6.dp.toPx()

                                drawLine(
                                    color = Color.White.copy(alpha = 0.07f),
                                    start = Offset(scrollbarX, trackInset),
                                    end = Offset(scrollbarX, viewportHeight - trackInset),
                                    strokeWidth = 1.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.White.copy(alpha = 0.30f),
                                    start = Offset(scrollbarX, thumbTop),
                                    end = Offset(scrollbarX, thumbTop + thumbHeight),
                                    strokeWidth = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.DirectionDown && scrollState.value < scrollState.maxValue -> {
                                    requestSmoothScroll((
                                        maxOf(requestedScrollPosition, scrollState.value) + 260
                                    ).coerceAtMost(scrollState.maxValue))
                                    true
                                }
                                event.key == Key.DirectionUp && scrollState.value > 0 -> {
                                    requestSmoothScroll((
                                        minOf(requestedScrollPosition, scrollState.value) - 260
                                    ).coerceAtLeast(0))
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusRequester(contentFocusRequester)
                        .focusable()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = NuvioTheme.spacing.lg)
                    )
                }

                Text(
                    text = stringResource(R.string.hero_synopsis_dismiss_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}
