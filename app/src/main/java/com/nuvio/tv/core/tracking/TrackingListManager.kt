package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.LibraryListPrivacy

data class TrackingListManagementCapabilities(
    val privacyOptions: List<LibraryListPrivacy>,
    val supportsDescription: Boolean = false,
    val supportsReordering: Boolean = false
)

interface TrackingListManager {
    val capabilities: TrackingListManagementCapabilities
    suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy)
    suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy)
    suspend fun deleteList(key: String)
    suspend fun reorderLists(keys: List<String>) {
        throw UnsupportedOperationException("This provider does not support list reordering")
    }
}
