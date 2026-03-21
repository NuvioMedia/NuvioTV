package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable

/**
 * Stores focus and scroll state for the HomeScreen to enable proper state restoration
 * when navigating back from detail screens.
 */
@Immutable
data class HomeScreenFocusState(
    /**
     * The index of the first visible item in the main vertical LazyColumn.
     */
    val verticalScrollIndex: Int = 0,

    /**
     * The pixel offset of the first visible item in the vertical scroll.
     */
    val verticalScrollOffset: Int = 0,

    /**
     * Index of the catalog row that had focus when navigating away.
     * -1 means continue watching section.
     */
    val focusedRowIndex: Int = 0,

    /**
     * Index of the item within the focused catalog row.
     */
    val focusedItemIndex: Int = 0,

    /**
     * Stable row key for the focused row when available.
     */
    val focusedRowKey: String? = null,

    /**
     * Map of catalog row keys to their horizontal scroll positions.
     * Key format: "${addonId}_${type}_${catalogId}"
     */
    val catalogRowScrollStates: Map<String, Int> = emptyMap(),

    /**
     * Whether focus state has been explicitly saved (vs still at defaults).
     */
    val hasSavedFocus: Boolean = false
)
