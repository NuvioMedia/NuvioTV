package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class TraktTrackingLibraryProvider @Inject constructor(
    private val service: TraktLibraryService,
    authDataStore: TraktAuthDataStore
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.TRAKT
    override val isAuthenticated = authDataStore.isEffectivelyAuthenticated
    override val isRefreshing = service.observeIsRefreshing()
    override val items = service.observeAllItems()
    override val tabs = service.observeListTabs().map { tabs ->
        tabs.map { tab -> tab.copy(trackingProviderId = providerId.storageId) }
    }

    override fun recognizesListKey(key: String): Boolean =
        key == TraktLibraryService.WATCHLIST_KEY || key.startsWith(TraktLibraryService.PERSONAL_KEY_PREFIX)

    override fun observeMembership(itemId: String, itemType: String) =
        service.observeMembership(itemId, itemType)

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> {
        val watchlistKey = TraktLibraryService.WATCHLIST_KEY
        return currentMembership + (watchlistKey to (currentMembership[watchlistKey] != true))
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput) =
        service.getMembershipSnapshot(item)

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) = service.applyMembershipChanges(item, changes)

    override suspend fun refresh(intent: TrackingRefreshIntent) = service.refreshNow()
}
