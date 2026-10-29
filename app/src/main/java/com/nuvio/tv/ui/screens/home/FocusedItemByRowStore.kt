package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf

/**
 * Focused item index per home row.
 *
 * The store used to be a single [androidx.compose.runtime.snapshots.SnapshotStateMap]. A
 * snapshot state map is observed as a whole: reading one key subscribes the reader to every
 * key, so a single focus move invalidated the per-row derived state of *every* row and each of
 * them recomposed.
 *
 * Here every row owns its own [MutableIntState]. The map that holds them is a plain map (it is
 * never observed), so writing the focused index of one row only invalidates that row.
 */
internal class FocusedItemByRowStore {

    private val states = mutableMapOf<String, MutableIntState>()

    /**
     * The state object of [rowKey], created on first use.
     *
     * Rows call this while they are composed so their derived state always has a state object
     * to observe. Creating it is a plain map write, it does not trigger any recomposition.
     */
    fun stateFor(rowKey: String): MutableIntState =
        states.getOrPut(rowKey) { mutableIntStateOf(0) }

    /** The focused index of [rowKey], `0` when the row has no focus yet. */
    operator fun get(rowKey: String): Int = states[rowKey]?.intValue ?: 0

    /** Moves the focus of [rowKey] to [index]. Only that row is invalidated. */
    operator fun set(rowKey: String, index: Int) {
        stateFor(rowKey).intValue = index
    }

    /** True when [rowKey] already owns a focus state. */
    operator fun contains(rowKey: String): Boolean = states.containsKey(rowKey)

    /** True when no row owns a focus state yet. */
    fun isEmpty(): Boolean = states.isEmpty()

    /** The rows that own a focus state. */
    fun keys(): Set<String> = states.keys
}
