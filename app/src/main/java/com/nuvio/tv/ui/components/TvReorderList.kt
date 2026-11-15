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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
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
private const val LIST_TAG = "CatalogOrder"

private const val RETAINED_ROW_SLOTS = 24

private val ReorderTravelSpec = tween<Float>(
    durationMillis = NuvioMotion.tokens.durations.medium,
    easing = NuvioMotion.tokens.easings.emphasized,
)

@Composable
fun <T> TvReorderList(
    items: List<T>,
    key: (T) -> String,
    canMove: (T) -> Boolean,
    movingKey: String?,
    onStartMove: (String) -> Unit,
    onMoveBy: (String, direction: Int) -> Int?,
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

    val rowHeightPx = remember { FloatArray(1) }
    val headerHeightPx = remember { FloatArray(1) }
    val viewportHeightPx = remember { FloatArray(1) }
    val measuredRowHeights = remember { mutableStateMapOf<String, Float>() }

    fun slotStridePx(): Float {
        val height = rowHeightPx[0]
        return if (height > 0f) height + rowSpacingPx else 0f
    }

    fun defaultRowHeightPx(): Float {
        return if (rowHeightPx[0] > 0f) rowHeightPx[0] else 1f
    }

    fun heightForItemKey(itemKey: String): Float {
        return measuredRowHeights[itemKey] ?: defaultRowHeightPx()
    }

    fun slotTopsFor(orderKeys: List<String>): FloatArray {
        return computeSlotTops(
            slotCount = orderKeys.size,
            heightAt = { index -> heightForItemKey(orderKeys[index]) },
            spacingPx = rowSpacingPx,
        )
    }

    fun indicesFrom(source: List<T>): Map<String, Int> {
        return source.mapIndexed { index, item -> key(item) to index }.toMap()
    }

    val animatedIndices = remember {
        mutableStateMapOf<String, Int>().apply { putAll(indicesFrom(items)) }
    }
    val dragOffsets = remember { mutableStateMapOf<String, Float>() }
    val offsetJobs = remember { mutableMapOf<String, Job>() }
    val rowContentCache = remember(movingKey) {
        mutableMapOf<String, CachedRowContent<T>>()
    }
    val subcomposeLayoutState = remember {
        SubcomposeLayoutState(SubcomposeSlotReusePolicy(RETAINED_ROW_SLOTS))
    }
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
                if (headerHeightPx[0] <= 0f || rowHeightPx[0] <= 0f) {
                    return inheritedBringIntoViewSpec.calculateScrollDistance(
                        offset, size, containerSize,
                    )
                }
                val itemTopInContent = scrollState.value + offset
                val firstRowBottom = headerHeightPx[0] + if (rowHeightPx[0] > 0f) rowHeightPx[0] else 1f
                if (headerHeightPx[0] > 0f && itemTopInContent < firstRowBottom) {
                    return -scrollState.value.toFloat()
                }
                return inheritedBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }

    val runtime = remember { TvReorderListRuntime(items.map(key), movingKey) }
    var suppressStartMove by remember { mutableStateOf(false) }
    var pinnedScrollPx by remember { mutableIntStateOf(-1) }
    runtime.latestMovingKey = movingKey
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
            "scroll=${scrollState.value}/${scrollState.maxValue} rowH=${rowHeightPx[0]} stride=${slotStridePx()} " +
            "viewport=${viewportHeightPx[0]} items=${itemKeys.take(6)} slots=${slotOrderSample()} " +
            "indicesMatchItems=${itemKeys == animatedOrder} liveOffsets=[$offsetSample]"
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

    val itemKeys = remember(items) { items.map(key) }

    SideEffect {
        val movingChanged = runtime.previousMovingKey != movingKey
        val itemsChanged = runtime.previousItemKeys != itemKeys
        if (itemsChanged && movingKey == null) {
            clearOffsets()
            val nextIndices = indicesFrom(items)
            if (nextIndices != animatedIndices) {
                Log.d(LIST_TAG, "list syncSlots ${visualSnapshot("itemsReorder")}")
                animatedIndices.clear()
                animatedIndices.putAll(nextIndices)
            }
        }
        if (movingChanged || itemsChanged) {
            Log.d(
                LIST_TAG,
                "list JUMP? composition ${visualSnapshot(
                    "moving ${runtime.previousMovingKey}→$movingKey itemsChanged=$itemsChanged",
                )}",
            )
            runtime.previousMovingKey = movingKey
            runtime.previousItemKeys = itemKeys
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runtime.repeatJob?.cancel()
            runtime.neighborScrollJob?.cancel()
            offsetJobs.values.forEach { it.cancel() }
        }
    }

    fun stopRepeat() {
        runtime.repeatJob?.cancel()
        runtime.repeatJob = null
    }

    fun animateOffsetToZero(itemKey: String) {
        offsetJobs[itemKey]?.cancel()
        val start = dragOffsets[itemKey] ?: 0f
        if (start == 0f) return
        val job = scope.launch {
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
        offsetJobs[itemKey] = job
        job.invokeOnCompletion { offsetJobs.remove(itemKey, job) }
    }

    fun animateMove(movedKey: String, oldIndex: Int, newIndex: Int) {
        if (oldIndex == newIndex) return
        val orderKeys = items.map(key)
        if (movedKey !in orderKeys) return
        val clampedOld = oldIndex.coerceIn(0, orderKeys.lastIndex)
        val clampedNew = newIndex.coerceIn(0, orderKeys.lastIndex)
        val oldOrderKeys = orderKeys.toMutableList().apply {
            remove(movedKey)
            add(clampedOld, movedKey)
        }
        val oldTops = slotTopsFor(oldOrderKeys)
        val newTops = slotTopsFor(orderKeys)

        val shiftRange: IntRange
        val slotShift: Int
        if (clampedNew > clampedOld) {
            shiftRange = (clampedOld + 1)..clampedNew
            slotShift = -1
        } else {
            shiftRange = clampedNew until clampedOld
            slotShift = 1
        }
        val shifting = animatedIndices.mapNotNull { (itemKey, slot) ->
            if (itemKey != movedKey && slot in shiftRange) itemKey to slot else null
        }
        val movedDeltaSlots = clampedNew - clampedOld
        Log.d(
            LIST_TAG,
            "list animateMove key=$movedKey $oldIndex->$newIndex delta=$movedDeltaSlots " +
                "shifting=${shifting.size}",
        )

        fun visualDelta(itemKey: String, oldSlot: Int, newSlot: Int): Float {
            return (oldTops[oldSlot] - newTops[newSlot]) + (dragOffsets[itemKey] ?: 0f)
        }

        offsetJobs[movedKey]?.cancel()
        shifting.forEach { (itemKey, _) -> offsetJobs[itemKey]?.cancel() }

        dragOffsets[movedKey] = visualDelta(movedKey, clampedOld, clampedNew)
        shifting.forEach { (itemKey, slot) ->
            dragOffsets[itemKey] = visualDelta(itemKey, slot, slot + slotShift)
        }
        animatedIndices[movedKey] = clampedNew
        shifting.forEach { (itemKey, slot) ->
            animatedIndices[itemKey] = slot + slotShift
        }
        animateOffsetToZero(movedKey)
        shifting.forEach { (itemKey, _) -> animateOffsetToZero(itemKey) }
    }

    fun keepNeighborVisible(newIndex: Int, direction: Int) {
        val viewport = viewportHeightPx[0]
        if (viewport <= 0f) return
        val orderKeys = items.map(key)
        if (orderKeys.isEmpty()) return
        val clampedIndex = newIndex.coerceIn(0, orderKeys.lastIndex)
        val tops = slotTopsFor(orderKeys)
        val movingTop = headerHeightPx[0] + tops[clampedIndex]
        val movingHeight = heightForItemKey(orderKeys[clampedIndex])
        val peek = movingHeight
        if (movingHeight <= 0f) return
        if (newIndex <= 0) {
            runtime.neighborScrollJob?.cancel()
            Log.d(
                LIST_TAG,
                "list scrollTo keepNeighbor header dir=$direction newIndex=$newIndex " +
                    "from=${scrollState.value} to=0 ${visualSnapshot("keepNeighbor")}",
            )
            runtime.neighborScrollJob = scope.launch {
                scrollState.animateScrollTo(0, ReorderTravelSpec)
            }
            return
        }
        val movingBottom = movingTop + movingHeight
        val desiredTop = if (direction < 0) (movingTop - peek).coerceAtLeast(0f) else movingTop
        val desiredBottom = if (direction > 0) movingBottom + peek else movingBottom
        val current = scrollState.value.toFloat()
        val target = when {
            desiredTop < current -> desiredTop
            desiredBottom > current + viewport -> desiredBottom - viewport
            else -> return
        }.coerceIn(0f, scrollState.maxValue.toFloat())
        runtime.neighborScrollJob?.cancel()
        Log.d(
            LIST_TAG,
            "list scrollTo keepNeighbor dir=$direction newIndex=$newIndex " +
                "from=${current.toInt()} to=${target.toInt()} ${visualSnapshot("keepNeighbor")}",
        )
        runtime.neighborScrollJob = scope.launch {
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
        val newIndex = onMoveBy(activeKey, direction)
        Log.d(
            LIST_TAG,
            "list performMove key=$activeKey dir=$direction oldIndex=$oldIndex " +
                "newIndex=$newIndex ${visualSnapshot("performMove")}",
        )
        if (newIndex == null || newIndex == oldIndex) return false
        animateMove(activeKey, oldIndex, newIndex)
        keepNeighborVisible(newIndex, direction)
        return true
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
        val direction = when (nativeKeyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> return false
        }
        if (action == KeyEvent.ACTION_UP) {
            stopRepeat()
            return true
        }
        if (action != KeyEvent.ACTION_DOWN) return false

        if (repeatCount == 0) {
            performMove(direction)
            stopRepeat()
            runtime.repeatJob = scope.launch {
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
            runtime.hadMoveSession = true
            val movingIndex = animatedIndices[movingKey]
            if (movingIndex == 0 && scrollState.value != 0) {
                runtime.neighborScrollJob?.cancel()
                scrollState.animateScrollTo(0, ReorderTravelSpec)
            }
            return@LaunchedEffect
        }
        if (!runtime.hadMoveSession) {
            stopRepeat()
            return@LaunchedEffect
        }
        delay(1)
        if (runtime.latestMovingKey != null) {
            Log.d(
                LIST_TAG,
                "list skip stale exitMove latest=$runtime.latestMovingKey ${visualSnapshot("stale-exit")}",
            )
            return@LaunchedEffect
        }
        runtime.neighborScrollJob?.cancel()
        stopRepeat()
        restorePinnedScroll("exitMove")
        val itemKeys = items.map(key)
        val animatedOrder = animatedIndices.entries.sortedBy { it.value }.map { it.key }
        if (itemKeys != animatedOrder || dragOffsets.values.any { kotlin.math.abs(it) > 0.5f }) {
            Log.d(LIST_TAG, "list JUMP? exitMove beforeReset ${visualSnapshot("exitMove-before")}")
            clearOffsets()
            animatedIndices.clear()
            animatedIndices.putAll(indicesFrom(items))
            Log.d(LIST_TAG, "list JUMP? exitMove afterReset ${visualSnapshot("exitMove-after")}")
        } else {
            offsetJobs.values.forEach { it.cancel() }
            offsetJobs.clear()
            Log.d(LIST_TAG, "list exitMove keepSlots ${visualSnapshot("exitMove-keep")}")
        }
        restorePinnedScroll("exitMove-afterLayout")
        delay(32)
        restorePinnedScroll("exitMove-late")
        if (runtime.latestMovingKey == null) {
            pinnedScrollPx = -1
        }
    }

    BackHandler(enabled = movingKey != null) {
        pinScroll("cancel")
        onCancelMove()
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
        BoxWithConstraints(modifier = modifier) {
            val viewportPx = if (constraints.hasBoundedHeight) {
                constraints.maxHeight.toFloat()
            } else {
                0f
            }
            viewportHeightPx[0] = viewportPx
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                                .onSizeChanged { size ->
                                    val measured = size.height.toFloat()
                                    if (headerHeightPx[0] != measured) {
                                        headerHeightPx[0] = measured
                                    }
                                },
                        ) {
                            header()
                        }
                    }
                    SubcomposeLayout(
                        state = subcomposeLayoutState,
                        modifier = Modifier.fillMaxWidth(),
                    ) { layoutConstraints ->
                        if (items.isEmpty()) {
                            val emptyWidth = if (layoutConstraints.hasBoundedWidth) {
                                layoutConstraints.maxWidth
                            } else {
                                0
                            }
                            return@SubcomposeLayout layout(emptyWidth, 0) {}
                        }
                        val itemConstraints = layoutConstraints.copy(
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                        val placeables = mutableMapOf<Int, Placeable>()
                        fun measureSlot(index: Int): Placeable {
                            placeables[index]?.let { return it }
                            val item = items[index]
                            val itemKey = key(item)
                            val isMoving = movingKey == itemKey
                            val cached = rowContentCache[itemKey]
                            val content: @Composable () -> Unit
                            if (cached != null && cached.item == item && cached.isMoving == isMoving) {
                                content = cached.content
                            } else {
                                content = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .offset {
                                                val dragOffsetPx = dragOffsets[itemKey] ?: 0f
                                                IntOffset(x = 0, y = dragOffsetPx.roundToInt())
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
                                rowContentCache[itemKey] = CachedRowContent(item, isMoving, content)
                            }
                            val placeable = subcompose(itemKey, content).first().measure(itemConstraints)
                            placeables[index] = placeable
                            val measuredHeight = placeable.height.toFloat()
                            if (measuredRowHeights[itemKey] != measuredHeight) {
                                measuredRowHeights[itemKey] = measuredHeight
                            }
                            if (rowHeightPx[0] == 0f && !isMoving && measuredHeight > 0f) {
                                rowHeightPx[0] = measuredHeight
                            }
                            return placeable
                        }

                        val sampledRowHeight = if (rowHeightPx[0] > 0f) {
                            rowHeightPx[0]
                        } else {
                            measureSlot(0).height.toFloat()
                        }
                        val movingSlot = movingKey
                            ?.let { animatedIndices[it] }
                            ?.coerceIn(0, items.lastIndex)
                        val visibleRange = if (viewportPx <= 0f) {
                            0..items.lastIndex
                        } else {
                            visibleReorderSlotRange(
                                slotCount = items.size,
                                rowHeightPx = sampledRowHeight,
                                rowSpacingPx = rowSpacingPx,
                                viewportHeightPx = viewportPx,
                                headerHeightPx = headerHeightPx[0],
                                scrollPx = scrollState.value,
                            )
                        }
                        val extraSlot = movingSlot?.takeIf { it !in visibleRange }
                        for (index in visibleRange) {
                            measureSlot(index)
                        }
                        if (extraSlot != null) {
                            measureSlot(extraSlot)
                        }
                        val orderKeys = items.map(key)
                        val slotTops = slotTopsFor(orderKeys)
                        val lastSlot = orderKeys.lastIndex
                        val totalHeight = (
                            slotTops[lastSlot] + heightForItemKey(orderKeys[lastSlot])
                            ).roundToInt()
                        val layoutWidth = if (layoutConstraints.hasBoundedWidth) {
                            layoutConstraints.maxWidth
                        } else {
                            placeables.values.maxOfOrNull { it.width } ?: 0
                        }
                        layout(layoutWidth, totalHeight) {
                            fun placeRow(index: Int) {
                                val placeable = placeables[index] ?: return
                                val itemKey = key(items[index])
                                val slotIndex = animatedIndices[itemKey] ?: index
                                placeable.place(
                                    x = 0,
                                    y = slotTops[slotIndex.coerceIn(0, lastSlot)].roundToInt(),
                                    zIndex = if (itemKey == movingKey) 1f else 0f,
                                )
                            }
                            for (index in visibleRange) {
                                placeRow(index)
                            }
                            if (extraSlot != null) {
                                placeRow(extraSlot)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class TvReorderListRuntime(
    initialItemKeys: List<String>,
    initialMovingKey: String?,
) {
    var repeatJob: Job? = null
    var neighborScrollJob: Job? = null
    var previousItemKeys: List<String> = initialItemKeys
    var previousMovingKey: String? = initialMovingKey
    var hadMoveSession: Boolean = false
    var latestMovingKey: String? = initialMovingKey
}

private class CachedRowContent<T>(
    val item: T,
    val isMoving: Boolean,
    val content: @Composable () -> Unit,
)

internal fun computeSlotTops(
    slotCount: Int,
    heightAt: (Int) -> Float,
    spacingPx: Float,
): FloatArray {
    val tops = FloatArray(slotCount)
    var acc = 0f
    for (i in 0 until slotCount) {
        tops[i] = acc
        acc += heightAt(i).coerceAtLeast(0f) + spacingPx
    }
    return tops
}

internal fun visibleReorderSlotRange(
    slotCount: Int,
    rowHeightPx: Float,
    rowSpacingPx: Float,
    viewportHeightPx: Float,
    headerHeightPx: Float,
    scrollPx: Int,
): IntRange {
    val lastSlot = slotCount - 1
    if (lastSlot < 0) return IntRange.EMPTY
    val stride = if (rowHeightPx > 0f) rowHeightPx + rowSpacingPx else 0f
    if (stride <= 0f || viewportHeightPx <= 0f) {
        return 0..lastSlot
    }
    val overscanPx = viewportHeightPx
    val topEdge = scrollPx - headerHeightPx - overscanPx
    val bottomEdge = scrollPx + viewportHeightPx - headerHeightPx + overscanPx
    val first = ((topEdge - rowHeightPx) / stride).toInt()
        .coerceIn(0, lastSlot)
    val last = (bottomEdge / stride).toInt()
        .coerceIn(0, lastSlot)
    return first..last.coerceAtLeast(first)
}
