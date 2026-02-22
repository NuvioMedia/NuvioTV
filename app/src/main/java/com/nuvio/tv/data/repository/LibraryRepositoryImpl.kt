package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktLibraryService: TraktLibraryService,
    private val librarySyncService: LibrarySyncService,
    private val authManager: AuthManager
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            librarySyncService.pushToRemote()
        }
    }

    override val sourceMode: Flow<LibrarySourceMode> = traktAuthDataStore.isEffectivelyAuthenticated
        .map { isAuthenticated ->
            if (isAuthenticated) LibrarySourceMode.TRAKT else LibrarySourceMode.LOCAL
        }
        .distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = sourceMode
        .flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeIsRefreshing()
            } else {
                flowOf(false)
            }
        }
        .distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> = sourceMode
        .flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeAllItems()
            } else {
                libraryPreferences.libraryItems.map { items ->
                    items.map { saved ->
                        LibraryEntry(
                            id = saved.id,
                            type = saved.type,
                            name = saved.name,
                            poster = saved.poster,
                            posterShape = saved.posterShape,
                            background = saved.background,
                            logo = null,
                            description = saved.description,
                            releaseInfo = saved.releaseInfo,
                            imdbRating = saved.imdbRating,
                            genres = saved.genres,
                            addonBaseUrl = saved.addonBaseUrl
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = sourceMode
        .flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeListTabs()
            } else {
                flowOf(emptyList())
            }
        }
        .distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return sourceMode.flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeMembership(itemId, itemType)
                    .map { memberships -> memberships.isNotEmpty() }
            } else {
                libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            }
        }.distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return sourceMode.flatMapLatest { mode ->
            if (mode == LibrarySourceMode.TRAKT) {
                traktLibraryService.observeMembership(itemId, itemType)
                    .map { memberships -> memberships.contains(TraktLibraryService.WATCHLIST_KEY) }
            } else {
                libraryPreferences.isInLibrary(itemId = itemId, itemType = itemType)
            }
        }.distinctUntilChanged()
    }

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.toggleWatchlist(item)
            return
        }

        val isInLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        if (isInLocal) {
            libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
        } else {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        }
        triggerRemoteSync()
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            return traktLibraryService.getMembershipSnapshot(item)
        }
        val inLocal = libraryPreferences.isInLibrary(item.itemId, item.itemType).first()
        return ListMembershipSnapshot(listMembership = mapOf(LOCAL_LIST_KEY to inLocal))
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.applyMembershipChanges(item, changes)
            return
        }

        val shouldBeSaved = changes.desiredMembership.values.any { it }
        if (shouldBeSaved) {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        } else {
            libraryPreferences.removeItem(itemId = item.itemId, itemType = item.itemType)
        }
        triggerRemoteSync()
    }

    override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {
        requireTraktAuth()
        traktLibraryService.createPersonalList(name = name, description = description, privacy = privacy)
    }

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        requireTraktAuth()
        traktLibraryService.updatePersonalList(
            listId = listId,
            name = name,
            description = description,
            privacy = privacy
        )
    }

    override suspend fun deletePersonalList(listId: String) {
        requireTraktAuth()
        traktLibraryService.deletePersonalList(listId)
    }

    override suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        requireTraktAuth()
        traktLibraryService.reorderPersonalLists(orderedListIds)
    }

    override suspend fun refreshNow() {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.refreshNow()
        }
    }

    private suspend fun requireTraktAuth() {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            throw IllegalStateException("Trakt authentication required")
        }
    }

    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem {
        return SavedLibraryItem(
            id = itemId,
            type = itemType,
            name = title,
            poster = poster,
            posterShape = posterShape,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl
        )
    }

    companion object {
        private const val LOCAL_LIST_KEY = "local"
    }
}
