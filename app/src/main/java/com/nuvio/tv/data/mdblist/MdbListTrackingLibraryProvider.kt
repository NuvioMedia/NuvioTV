package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MdbListTrackingLibraryProvider @Inject constructor(
    private val service: MdbListLibraryService,
    tracking: MdbListTrackingProvider
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val listManager = service.listManager
    override val isAuthenticated = tracking.isAuthenticated
    override val isRefreshing = service.isRefreshing
    override val items = service.items
    override val tabs = service.tabs

    override fun recognizesListKey(key: String): Boolean =
        key == MDBLIST_WATCHLIST_KEY || key.startsWith(MDBLIST_LIST_KEY_PREFIX)

    override fun observeMembership(itemId: String, itemType: String) = service.observeMembership(itemId, itemType)

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership + (MDBLIST_WATCHLIST_KEY to (currentMembership[MDBLIST_WATCHLIST_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput) = service.getMembershipSnapshot(item)

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges, destructiveRemovalConfirmed: Boolean) =
        service.applyMembershipChanges(item, changes)

    override suspend fun refresh(intent: TrackingRefreshIntent) = service.refresh(intent)
}
