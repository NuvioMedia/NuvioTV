@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MOVE_REPEAT_INITIAL_DELAY_MS = 280L
private const val MOVE_REPEAT_INTERVAL_MS = 120L

/**
 * TV-first vertical reorder list.
 *
 * Uses absolute slot positioning inside a scrollable [Box] instead of
 * [LazyColumn], so slot swaps only recompose the affected rows. Move mode is
 * entered via a per-row action; while active, DPAD up/down (including key
 * repeat) continuously shifts the row with spring animation.
 */
@OptIn(ExperimentalFoundationApi::class)
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

    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val slotStridePx = if (rowHeightPx > 0f) rowHeightPx + rowSpacingPx else 0f
    val totalHeightPx = if (slotStridePx > 0f && items.isNotEmpty()) {
        rowHeightPx + (items.size - 1) * slotStridePx
    } else {
        0f
    }

    val visualIndices = remember(items, key) {
        items.mapIndexed { index, item -> key(item) to index }.toMap()
    }
    var animatedIndices by remember(items, key) {
        mutableStateOf(visualIndices)
    }
    LaunchedEffect(items, key) {
        animatedIndices = items.mapIndexed { index, item -> key(item) to index }.toMap()
    }

    val dragOffsets = remember { mutableMapOf<String, Animatable<Float, *>>() }
    items.forEach { item ->
        val itemKey = key(item)
        if (dragOffsets[itemKey] == null) {
            dragOffsets[itemKey] = Animatable(0f)
        }
    }

    val bringIntoViewRequesters = remember(items, key) {
        items.associate { key(it) to BringIntoViewRequester() }
    }
    val moveFocusRequester = remember { FocusRequester() }

    var repeatJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { repeatJob?.cancel() }
    }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    fun animateSwap(movedKey: String, direction: Int) {
        if (slotStridePx <= 0f) return
        val animatable = dragOffsets[movedKey] ?: return
        scope.launch {
            animatable.snapTo(-direction * slotStridePx)
            animatable.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
        val currentIndex = animatedIndices[movedKey] ?: return
        val neighborIndex = currentIndex + direction
        val neighborKey = animatedIndices.entries.firstOrNull { it.value == neighborIndex }?.key
        if (neighborKey != null) {
            val neighborAnimatable = dragOffsets[neighborKey] ?: return
            scope.launch {
                neighborAnimatable.snapTo(direction * slotStridePx)
                neighborAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
    }

    fun performMove(direction: Int): Boolean {
        val activeKey = movingKey ?: return false
        val moved = onMoveBy(activeKey, direction)
        if (moved) {
            animatedIndices = items.mapIndexed { index, item -> key(item) to index }.toMap()
            animateSwap(activeKey, direction)
            scope.launch {
                bringIntoViewRequesters[activeKey]?.bringIntoView()
            }
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
            bringIntoViewRequesters[movingKey]?.bringIntoView()
        } else {
            stopRepeat()
        }
    }

    BackHandler(enabled = movingKey != null) {
        onCancelMove()
    }

    Box(
        modifier = modifier
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
                val isMoving = movingKey == itemKey
                val slotIndex = animatedIndices[itemKey] ?: index
                val dragOffsetPx = dragOffsets[itemKey]?.value ?: 0f
                val yOffsetPx = slotIndex * slotStridePx + dragOffsetPx

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
                            val scale = if (isMoving) 1.02f else 1f
                            scaleX = scale
                            scaleY = scale
                        }
                        .bringIntoViewRequester(
                            bringIntoViewRequesters[itemKey] ?: BringIntoViewRequester(),
                        )
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
