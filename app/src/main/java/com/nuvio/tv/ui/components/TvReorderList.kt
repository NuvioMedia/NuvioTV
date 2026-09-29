@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as keyedItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MOVE_REPEAT_INITIAL_DELAY_MS = 280L
private const val MOVE_REPEAT_INTERVAL_MS = 140L

private val ReorderTravelSpec = tween<Float>(
    durationMillis = NuvioMotion.tokens.durations.medium,
    easing = NuvioMotion.tokens.easings.emphasized,
)

/**
 * TV-first vertical reorder list.
 *
 * Uses absolute slot positioning inside a scrollable [Box] instead of
 * [LazyColumn], so slot swaps only recompose the affected rows. Move mode is
 * entered via a per-row action; while active, DPAD up/down (including key
 * repeat) continuously shifts the row with a shared travel animation.
 */
@Composable
fun <T> TvReorderList(
    items: List<T>,
    key: (T) -> String,
    canMove: (T) -> Boolean,
    movingKey: String?,
    onStartMove: (String) -> Unit,
    onMoveBy: (String, direction: Int) -> Boolean,
    onConfirmMove: (String) -> Unit,
    onCancelMove: () -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (
        item: T,
        isMoving: Boolean,
        onStartMove: () -> Unit,
        onConfirmMove: () -> Unit,
    ) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rowSpacingPx = with(density) { NuvioTheme.spacing.lg.toPx() }

    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val slotStridePx = if (rowHeightPx > 0f) rowHeightPx + rowSpacingPx else 0f
    val totalHeightPx = if (slotStridePx > 0f && items.isNotEmpty()) {
        rowHeightPx + (items.size - 1) * slotStridePx
    } else {
        0f
    }

    fun indicesFrom(source: List<T>): Map<String, Int> {
        return source.mapIndexed { index, item -> key(item) to index }.toMap()
    }

    var animatedIndices by remember { mutableStateOf(indicesFrom(items)) }
    val dragOffsets = remember { mutableStateMapOf<String, Float>() }
    val offsetJobs = remember { mutableMapOf<String, Job>() }
    val moveFocusRequester = remember { FocusRequester() }

    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var neighborScrollJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            repeatJob?.cancel()
            neighborScrollJob?.cancel()
            offsetJobs.values.forEach { it.cancel() }
        }
    }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    fun clearOffsets() {
        offsetJobs.values.forEach { it.cancel() }
        offsetJobs.clear()
        dragOffsets.clear()
    }

    LaunchedEffect(movingKey, items) {
        if (movingKey == null) {
            clearOffsets()
            animatedIndices = indicesFrom(items)
        }
    }

    fun animateOffsetToZero(itemKey: String) {
        offsetJobs[itemKey]?.cancel()
        val start = dragOffsets[itemKey] ?: 0f
        if (start == 0f) return
        offsetJobs[itemKey] = scope.launch {
            animate(
                initialValue = start,
                targetValue = 0f,
                animationSpec = ReorderTravelSpec,
            ) { value, _ ->
                if (isActive) {
                    dragOffsets[itemKey] = value
                }
            }
        }
    }

    fun animateSwap(movedKey: String, direction: Int) {
        if (slotStridePx <= 0f) return
        val oldIndex = animatedIndices[movedKey] ?: return
        val newIndex = oldIndex + direction
        val neighborKey = animatedIndices.entries.firstOrNull { it.value == newIndex }?.key

        offsetJobs[movedKey]?.cancel()
        if (neighborKey != null) {
            offsetJobs[neighborKey]?.cancel()
        }

        // Keep the drawn position stable in this frame: update the logical slot
        // and compensate with an opposite offset, then ease the offset to 0.
        dragOffsets[movedKey] = (dragOffsets[movedKey] ?: 0f) - direction * slotStridePx
        if (neighborKey != null) {
            dragOffsets[neighborKey] = (dragOffsets[neighborKey] ?: 0f) + direction * slotStridePx
        }
        animatedIndices = animatedIndices.toMutableMap().apply {
            this[movedKey] = newIndex
            if (neighborKey != null) this[neighborKey] = oldIndex
        }
        animateOffsetToZero(movedKey)
        if (neighborKey != null) {
            animateOffsetToZero(neighborKey)
        }
    }

    fun keepNeighborVisible(newIndex: Int, direction: Int) {
        if (slotStridePx <= 0f || viewportHeightPx <= 0f) return
        val movingTop = newIndex * slotStridePx
        val movingBottom = movingTop + rowHeightPx
        val peek = slotStridePx
        val desiredTop = if (direction < 0) (movingTop - peek).coerceAtLeast(0f) else movingTop
        val desiredBottom = if (direction > 0) movingBottom + peek else movingBottom
        val current = scrollState.value.toFloat()
        val target = when {
            desiredTop < current -> desiredTop
            desiredBottom > current + viewportHeightPx -> desiredBottom - viewportHeightPx
            else -> return
        }.coerceIn(0f, scrollState.maxValue.toFloat())
        neighborScrollJob?.cancel()
        neighborScrollJob = scope.launch {
            scrollState.animateScrollTo(
                value = target.roundToInt(),
                animationSpec = ReorderTravelSpec,
            )
        }
    }

    fun performMove(direction: Int): Boolean {
        val activeKey = movingKey ?: return false
        val oldIndex = animatedIndices[activeKey]
            ?: items.indexOfFirst { key(it) == activeKey }.takeIf { it >= 0 }
            ?: return false
        val moved = onMoveBy(activeKey, direction)
        if (moved) {
            animateSwap(activeKey, direction)
            keepNeighborVisible(oldIndex + direction, direction)
        }
        return moved
    }

    fun handleMoveKey(nativeKeyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (movingKey == null) return false
        if (action == KeyEvent.ACTION_UP) {
            stopRepeat()
            return true
        }
        if (action != KeyEvent.ACTION_DOWN) return false

        val direction = when (nativeKeyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return false
        }

        if (repeatCount == 0) {
            performMove(direction)
            stopRepeat()
            repeatJob = scope.launch {
                delay(MOVE_REPEAT_INITIAL_DELAY_MS)
                while (isActive) {
                    if (!performMove(direction)) break
                    delay(MOVE_REPEAT_INTERVAL_MS)
                }
            }
        }
        return true
    }

    LaunchedEffect(movingKey) {
        if (movingKey != null) {
            delay(50)
            runCatching { moveFocusRequester.requestFocus() }
        } else {
            stopRepeat()
        }
    }

    BackHandler(enabled = movingKey != null) {
        onCancelMove()
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
            .verticalScroll(scrollState)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when {
                    movingKey != null && native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                        native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                        onConfirmMove(movingKey)
                        true
                    }

                    movingKey != null && native.keyCode == KeyEvent.KEYCODE_BACK &&
                        native.action == KeyEvent.ACTION_DOWN -> {
                        onCancelMove()
                        true
                    }

                    else -> handleMoveKey(native.keyCode, native.action, native.repeatCount)
                }
            }
            .focusGroup(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (totalHeightPx > 0f) {
                        Modifier.height(with(density) { totalHeightPx.toDp() })
                    } else {
                        Modifier
                    },
                ),
        ) {
            items.forEachIndexed { index, item ->
                val itemKey = key(item)
                keyedItem(itemKey) {
                    val isMoving = movingKey == itemKey
                    val slotIndex = animatedIndices[itemKey] ?: index
                    val dragOffsetPx = dragOffsets[itemKey] ?: 0f
                    val yOffsetPx = slotIndex * slotStridePx + dragOffsetPx
                    val scale by animateFloatAsState(
                        targetValue = if (isMoving) NuvioMotion.tokens.focusScale else 1f,
                        animationSpec = NuvioMotion.focusSpring(),
                        label = "tvReorderRowScale",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (rowHeightPx == 0f) {
                                    Modifier.onSizeChanged { size ->
                                        if (rowHeightPx == 0f && size.height > 0) {
                                            rowHeightPx = size.height.toFloat()
                                        }
                                    }
                                } else {
                                    Modifier.height(with(density) { rowHeightPx.toDp() })
                                },
                            )
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = if (slotStridePx > 0f) yOffsetPx.roundToInt() else 0,
                                )
                            }
                            .zIndex(if (isMoving) 1f else 0f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .then(
                                if (isMoving) {
                                    Modifier
                                        .focusRequester(moveFocusRequester)
                                        .focusable()
                                        .focusProperties { canFocus = true }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        itemContent(
                            item,
                            isMoving,
                            {
                                if (canMove(item)) onStartMove(itemKey)
                            },
                            { onConfirmMove(itemKey) },
                        )
                    }
                }
            }
        }
    }
}
