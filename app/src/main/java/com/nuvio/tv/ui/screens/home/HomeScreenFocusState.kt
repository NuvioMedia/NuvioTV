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
     * Key of the row that currently has focus.
     */
    val focusedRowKey: String? = null,

    /**
     * Map of row keys to the content ID of the item that was last focused in that row.
     */
    val focusedItemKeyByRow: Map<String, String> = emptyMap(),

    /**
     * Index of the catalog row that had focus when navigating away.
     * @deprecated Use [focusedRowKey] instead.
     */
    val focusedRowIndex: Int = 0,

    /**
     * Index of the item within the focused catalog row.
     * @deprecated Use [focusedItemKeyByRow] instead.
     */
    val focusedItemIndex: Int = 0,

    /**
     * Map of catalog row keys to their horizontal scroll positions.
     * Key format: "${addonId}_${type}_${catalogId}"
     */
    val catalogRowScrollStates: Map<String, Int> = emptyMap(),

    /**
     * Optional stable key for the currently focused card in grid-style layouts.
     */
    val focusedItemKey: String? = null,

    /**
     * Whether focus state has been explicitly saved (vs still at defaults).
     */
    val hasSavedFocus: Boolean = false
)
