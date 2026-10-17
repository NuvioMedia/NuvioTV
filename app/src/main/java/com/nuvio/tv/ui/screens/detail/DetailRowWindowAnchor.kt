package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

internal fun previewRowLazyKey(index: Int, id: String, name: String): String =
    "$id|$name|$index"

@Composable
fun LazyListState.keepDetailRowWindow(
    itemIds: List<String>,
    lazyKeyAt: (Int) -> Any?,
    resetKey: String? = null,
): String? {
    var anchorId by rememberSaveable(resetKey) { mutableStateOf<String?>(null) }
    var appliedReset by rememberSaveable { mutableStateOf(resetKey) }
    var seenIds by remember { mutableStateOf<List<String>?>(null) }
    var handledInitial by remember { mutableStateOf(false) }
    if (appliedReset != resetKey) {
        appliedReset = resetKey
        anchorId = null
        seenIds = null
        handledInitial = false
    }

    val idsState = rememberUpdatedState(itemIds)
    val lazyKeyAtState = rememberUpdatedState(lazyKeyAt)
    LaunchedEffect(this) {
        snapshotFlow {
            val ids = idsState.value
            val index = firstVisibleItemIndex
            val measured = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
            val matches = index in ids.indices &&
                measured != null &&
                measured == lazyKeyAtState.value(index)
            if (matches) ids[index] else null
        }.collect { id ->
            if (id != null && anchorId != id) anchorId = id
        }
    }

    if (itemIds.isNotEmpty()) {
        val index = firstVisibleItemIndex
        val idsChanged = handledInitial && seenIds != itemIds
        if (!handledInitial || idsChanged) {
            if (handledInitial || anchorId != null) {
                val target = anchorId?.let { id -> itemIds.indexOf(id).takeIf { it >= 0 } }
                if (target != null && target != index) {
                    requestScrollToItem(target)
                } else if (target == null) {
                    anchorId = null
                }
            }
            handledInitial = true
            seenIds = itemIds
        }
    }

    return anchorId?.takeIf { id -> itemIds.contains(id) }
}
