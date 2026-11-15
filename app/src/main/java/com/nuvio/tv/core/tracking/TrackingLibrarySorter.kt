package com.nuvio.tv.core.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TrackingLibrarySorter {
    fun observeAddedOrder(listKey: String, descending: Boolean): Flow<List<String>?>

    /** Provider order by release date; null falls back to sorting by release year. */
    fun observeReleaseOrder(listKey: String, descending: Boolean): Flow<List<String>?> = flowOf(null)
}
