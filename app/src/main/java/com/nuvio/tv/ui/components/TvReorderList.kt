@file:OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as keyedItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlin.math.min
import kotlin.math.roundToInt

private const val MOVE_REPEAT_INITIAL_DELAY_MS = 280L
private const val MOVE_REPEAT_INTERVAL_MS = 140L
private const val LIST_TAG = "CatalogOrder"

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
    header: @Composable (() -> Unit)? = null,
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
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    val slotStridePx = if (rowHeightPx > 0f) rowHeightPx + rowSpacingPx else 0f
    val itemsHeightPx = if (slotStridePx > 0f && items.isNotEmpty()) {
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
    val inheritedBringIntoViewSpec = LocalBringIntoViewSpec.current
    val freezeScrollBringIntoView = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = 0f
        }
    }
    val headerAwareBringIntoView = remember(inheritedBringIntoViewSpec, scrollState) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = inheritedBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (containerSize <= 0f || size <= 0f) return 0f
                val itemTopInContent = scrollState.value + offset
                val firstRowBottom = headerHeightPx + if (rowHeightPx > 0f) rowHeightPx else 1f
                if (headerHeightPx > 0f && itemTopInContent < firstRowBottom) {
                    return -scrollState.value.toFloat()
                }
                return inheritedBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }

    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var neighborScrollJob by remember { mutableStateOf<Job?>(null) }
    val previousItemKeys = remember { mutableStateOf(items.map(key)) }
    val previousMovingKey = remember { mutableStateOf(movingKey) }
    var hadMoveSession by remember { mutableStateOf(false) }
    var latestMovingKey by remember { mutableStateOf(movingKey) }
    var suppressStartMove by remember { mutableStateOf(false) }
    var pinnedScrollPx by remember { mutableIntStateOf(-1) }
    latestMovingKey = movingKey
    val bringIntoViewSpec = if (movingKey != null || pinnedScrollPx >= 0) {
        freezeScrollBringIntoView
    } else {
        headerAwareBringIntoView
    }

    fun pinScroll(reason: String) {
        val movingIndex = movingKey?.let { animatedIndices[it] }
        pinnedScrollPx = if (movingIndex == 0) 0 else scrollState.value
        Log.d(LIST_TAG, "list pinScroll reason=$reason value=$pinnedScrollPx")
    }

    suspend fun restorePinnedScroll(reason: String) {
        val pin = pinnedScrollPx
        if (pin < 0 || scrollState.value == pin) return
        Log.d(LIST_TAG, "list JUMP? restoreScroll $reason ${scrollState.value}→$pin")
        scrollState.scrollTo(pin)
    }

    fun slotOrderSample(): String {
        return animatedIndices.entries
            .sortedBy { it.value }
            .take(8)
            .joinToString { "${it.value}:${it.key}" }
    }

    fun visualSnapshot(reason: String): String {
        val itemKeys = items.map(key)
        val animatedOrder = animatedIndices.entries.sortedBy { it.value }.map { it.key }
        val offsetSample = dragOffsets.entries
            .filter { kotlin.math.abs(it.value) > 0.5f }
            .take(6)
            .joinToString { "${it.key}=${it.value.toInt()}" }
        return "reason=$reason t=${SystemClock.elapsedRealtime()} movingKey=$movingKey " +
            "scroll=${scrollState.value}/${scrollState.maxValue} rowH=$rowHeightPx stride=$slotStridePx " +
            "viewport=$viewportHeightPx items=${itemKeys.take(6)} slots=${slotOrderSample()} " +
            "indicesMatchItems=${itemKeys == animatedOrder} liveOffsets=[$offsetSample]"
    }

    SideEffect {
        val itemKeys = items.map(key)
        val movingChanged = previousMovingKey.value != movingKey
        val itemsChanged = previousItemKeys.value != itemKeys
        if (itemsChanged && movingKey == null) {
            val nextIndices = indicesFrom(items)
            if (nextIndices != animatedIndices) {
                Log.d(LIST_TAG, "list syncSlots ${visualSnapshot("itemsReorder")}")
                animatedIndices = nextIndices
                offsetJobs.values.forEach { it.cancel() }
                offsetJobs.clear()
                dragOffsets.clear()
            }
        }
        if (movingChanged || itemsChanged) {
            Log.d(
                LIST_TAG,
                "list JUMP? composition ${visualSnapshot(
                    "moving ${previousMovingKey.value}→$movingKey itemsChanged=$itemsChanged",
                )}",
            )
            previousMovingKey.value = movingKey
            previousItemKeys.value = itemKeys
        }
    }

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
        val hadOffsets = dragOffsets.values.any { kotlin.math.abs(it) > 0.5f }
        if (hadOffsets) {
            Log.d(LIST_TAG, "list JUMP? snapOffsets ${visualSnapshot("clearOffsets")}")
        }
        offsetJobs.values.forEach { it.cancel() }
        offsetJobs.clear()
        dragOffsets.clear()
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
        if (newIndex <= 0) {
            neighborScrollJob?.cancel()
            Log.d(
                LIST_TAG,
                "list scrollTo keepNeighbor header dir=$direction newIndex=$newIndex " +
                    "from=${scrollState.value} to=0 ${visualSnapshot("keepNeighbor")}",
            )
            neighborScrollJob = scope.launch {
                scrollState.animateScrollTo(0, ReorderTravelSpec)
            }
            return
        }
        val movingTop = headerHeightPx + newIndex * slotStridePx
        val movingBottom = movingTop + rowHeightPx
        val peek = rowHeightPx
        val desiredTop = if (direction < 0) (movingTop - peek).coerceAtLeast(0f) else movingTop
        val desiredBottom = if (direction > 0) movingBottom + peek else movingBottom
        val current = scrollState.value.toFloat()
        val target = when {
            desiredTop < current -> desiredTop
            desiredBottom > current + viewportHeightPx -> desiredBottom - viewportHeightPx
            else -> return
        }.coerceIn(0f, scrollState.maxValue.toFloat())
        neighborScrollJob?.cancel()
        Log.d(
            LIST_TAG,
            "list scrollTo keepNeighbor dir=$direction newIndex=$newIndex " +
                "from=${current.toInt()} to=${target.toInt()} ${visualSnapshot("keepNeighbor")}",
        )
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
        Log.d(
            LIST_TAG,
            "list performMove key=$activeKey dir=$direction oldIndex=$oldIndex moved=$moved " +
                visualSnapshot("performMove"),
        )
        if (moved) {
            animateSwap(activeKey, direction)
            keepNeighborVisible(oldIndex + direction, direction)
        }
        return moved
    }

    fun confirmFromUi(source: String) {
        val activeKey = movingKey ?: return
        pinScroll(source)
        suppressStartMove = true
        Log.d(LIST_TAG, "list confirm source=$source suppressStartMove ${visualSnapshot(source)}")
        onConfirmMove(activeKey)
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

    LaunchedEffect(suppressStartMove) {
        if (!suppressStartMove) return@LaunchedEffect
        delay(400)
        if (suppressStartMove) {
            Log.d(LIST_TAG, "list clear stale suppressStartMove")
            suppressStartMove = false
        }
    }

    LaunchedEffect(movingKey) {
        if (movingKey != null) {
            hadMoveSession = true
            val movingIndex = animatedIndices[movingKey]
            if (movingIndex == 0 && scrollState.value != 0) {
                neighborScrollJob?.cancel()
                scrollState.animateScrollTo(0, ReorderTravelSpec)
            }
            return@LaunchedEffect
        }
        if (!hadMoveSession) {
            stopRepeat()
            return@LaunchedEffect
        }
        delay(1)
        if (latestMovingKey != null) {
            Log.d(
                LIST_TAG,
                "list skip stale exitMove latest=$latestMovingKey ${visualSnapshot("stale-exit")}",
            )
            return@LaunchedEffect
        }
        neighborScrollJob?.cancel()
        stopRepeat()
        restorePinnedScroll("exitMove")
        val itemKeys = items.map(key)
        val animatedOrder = animatedIndices.entries.sortedBy { it.value }.map { it.key }
        if (itemKeys != animatedOrder || dragOffsets.values.any { kotlin.math.abs(it) > 0.5f }) {
            Log.d(LIST_TAG, "list JUMP? exitMove beforeReset ${visualSnapshot("exitMove-before")}")
            clearOffsets()
            animatedIndices = indicesFrom(items)
            Log.d(LIST_TAG, "list JUMP? exitMove afterReset ${visualSnapshot("exitMove-after")}")
        } else {
            offsetJobs.values.forEach { it.cancel() }
            offsetJobs.clear()
            Log.d(LIST_TAG, "list exitMove keepSlots ${visualSnapshot("exitMove-keep")}")
        }
        restorePinnedScroll("exitMove-afterLayout")
        delay(32)
        restorePinnedScroll("exitMove-late")
        if (latestMovingKey == null) {
            pinnedScrollPx = -1
        }
    }

    BackHandler(enabled = movingKey != null) {
        pinScroll("cancel")
        onCancelMove()
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
    Box(
        modifier = modifier
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
            .verticalScroll(scrollState)
            .then(
                if (movingKey != null || suppressStartMove) {
                    Modifier.onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val isConfirmKey = native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == KeyEvent.KEYCODE_ENTER ||
                            native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        when {
                            isConfirmKey && native.action == KeyEvent.ACTION_UP -> {
                                if (suppressStartMove) {
                                    Log.d(LIST_TAG, "list swallow OK up after confirm")
                                    suppressStartMove = false
                                    true
                                } else {
                                    false
                                }
                            }

                            movingKey != null && isConfirmKey &&
                                native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                                confirmFromUi("ok-confirm")
                                true
                            }

                            movingKey != null && native.keyCode == KeyEvent.KEYCODE_BACK &&
                                native.action == KeyEvent.ACTION_DOWN -> {
                                pinScroll("back-cancel")
                                Log.d(LIST_TAG, "list BACK cancel ${visualSnapshot("back-cancel")}")
                                onCancelMove()
                                true
                            }

                            else -> handleMoveKey(native.keyCode, native.action, native.repeatCount)
                        }
                    }
                } else {
                    Modifier
                },
            )
            .focusGroup(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NuvioTheme.spacing.xl),
        ) {
            if (header != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { headerHeightPx = it.height.toFloat() },
                ) {
                    header()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (itemsHeightPx > 0f) {
                            Modifier.height(with(density) { itemsHeightPx.toDp() })
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

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (!isMoving && size.height > 0) {
                                    val measured = size.height.toFloat()
                                    rowHeightPx = if (rowHeightPx == 0f) {
                                        measured
                                    } else {
                                        min(rowHeightPx, measured)
                                    }
                                }
                            }
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = if (slotStridePx > 0f) yOffsetPx.roundToInt() else 0,
                                )
                            }
                            .zIndex(if (isMoving) 1f else 0f),
                    ) {
                        itemContent(
                            item,
                            isMoving,
                            {
                                if (suppressStartMove) {
                                    Log.d(LIST_TAG, "list ignore startMove suppressed key=$itemKey")
                                } else if (canMove(item)) {
                                    onStartMove(itemKey)
                                }
                            },
                            { confirmFromUi("done-button") },
                        )
                    }
                }
            }
        }
        }
    }
    }
}
