package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListMovieRequestItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListShowRequestItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibraryService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository
) {
    private data class LibraryMetadata(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: Float?,
        val genres: List<String>
    )

    private data class Snapshot(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
        val allEntries: List<LibraryEntry> = emptyList(),
        val membershipByContent: Map<String, Set<String>> = emptyMap(),
        val updatedAtMs: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotState = MutableStateFlow(Snapshot())
    private val metadataState = MutableStateFlow<Map<String, LibraryMetadata>>(emptyMap())
    private val refreshingState = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private var lastRefreshMs: Long = 0L

    private val cacheTtlMs = 60_000L
    private val metadataHydrationLimit = 250
    private val listFetchConcurrency = 3
    private val metadataFetchSemaphore = Semaphore(5)

    fun observeListTabs(): Flow<List<LibraryListTab>> {
        return snapshotState
            .map { it.listTabs }
            .distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    fun observeAllItems(): Flow<List<LibraryEntry>> {
        return combine(snapshotState, metadataState) { snapshot, metadata ->
            enrichEntries(snapshot.allEntries, metadata)
        }.distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> {
        val key = contentKey(itemId = itemId, itemType = itemType)
        return snapshotState
            .map { snapshot -> snapshot.membershipByContent[key].orEmpty() }
            .distinctUntilChanged()
            .onStart { ensureFresh() }
    }

    fun observeIsRefreshing(): Flow<Boolean> {
        return refreshingState
    }

    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        ensureFresh()
        val snapshot = snapshotState.value
        val key = contentKey(item.itemId, item.itemType)
        val memberships = snapshot.membershipByContent[key].orEmpty()
        val map = snapshot.listTabs.associate { tab ->
            tab.key to memberships.contains(tab.key)
        }
        return ListMembershipSnapshot(listMembership = map)
    }

    suspend fun toggleWatchlist(item: LibraryEntryInput) {
        ensureFresh()
        val key = contentKey(item.itemId, item.itemType)
        val currentMembership = snapshotState.value.membershipByContent[key].orEmpty()
        val isInWatchlist = currentMembership.contains(WATCHLIST_KEY)
        if (isInWatchlist) {
            performOptimisticMutation(
                optimistic = { snapshot -> removeItemFromList(snapshot, item, WATCHLIST_KEY) }
            ) {
                removeFromWatchlist(item)
            }
        } else {
            performOptimisticMutation(
                optimistic = { snapshot -> addItemToList(snapshot, item, WATCHLIST_KEY) }
            ) {
                addToWatchlist(item)
            }
        }
    }

    suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ) {
        ensureFresh()
        val current = getMembershipSnapshot(item).listMembership
        val desired = changes.desiredMembership
        val keys = (current.keys + desired.keys).distinct()

        keys.forEach { listKey ->
            val before = current[listKey] == true
            val after = desired[listKey] == true
            if (before == after) return@forEach

            if (listKey == WATCHLIST_KEY) {
                if (after) {
                    performOptimisticMutation(
                        optimistic = { snapshot -> addItemToList(snapshot, item, WATCHLIST_KEY) }
                    ) {
                        addToWatchlist(item)
                    }
                } else {
                    performOptimisticMutation(
                        optimistic = { snapshot -> removeItemFromList(snapshot, item, WATCHLIST_KEY) }
                    ) {
                        removeFromWatchlist(item)
                    }
                }
            } else {
                val listId = listIdFromKey(listKey) ?: return@forEach
                if (after) {
                    performOptimisticMutation(
                        optimistic = { snapshot -> addItemToList(snapshot, item, listKey) }
                    ) {
                        addToPersonalList(listId, item)
                    }
                } else {
                    performOptimisticMutation(
                        optimistic = { snapshot -> removeItemFromList(snapshot, item, listKey) }
                    ) {
                        removeFromPersonalList(listId, item)
                    }
                }
            }
        }
    }

    suspend fun createPersonalList(
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.createUserList(
                authorization = authHeader,
                id = ME_PATH,
                body = TraktCreateOrUpdateListRequestDto(
                    name = name,
                    description = description,
                    privacy = privacy.apiValue
                )
            )
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful) {
            throw IllegalStateException(errorMessageForCode(response.code(), "Failed to create list"))
        }

        val createdTab = response.body()?.let(::mapListTab)
        if (createdTab == null) {
            refresh(force = true)
            return
        }
        val current = snapshotState.value
        val existingTabKeys = current.listTabs.map { it.key }.toSet()
        val nonDuplicateTabs = current.listTabs.filterNot { it.key == createdTab.key }
        val updatedTabs = nonDuplicateTabs + createdTab
        val updatedEntries = if (createdTab.key in existingTabKeys) {
            current.entriesByList
        } else {
            current.entriesByList + (createdTab.key to emptyList())
        }
        snapshotState.value = rebuildSnapshot(updatedTabs, updatedEntries)
    }

    suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        performOptimisticMutation(
            optimistic = { snapshot ->
                val updatedTabs = snapshot.listTabs.map { tab ->
                    if (matchesPersonalListIdentifier(tab, listId)) {
                        tab.copy(title = name, description = description, privacy = privacy)
                    } else {
                        tab
                    }
                }
                rebuildSnapshot(updatedTabs, snapshot.entriesByList)
            }
        ) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.updateUserList(
                    authorization = authHeader,
                    id = ME_PATH,
                    listId = listId,
                    body = TraktCreateOrUpdateListRequestDto(
                        name = name,
                        description = description,
                        privacy = privacy.apiValue
                    )
                )
            } ?: throw IllegalStateException("Trakt request failed")

            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessageForCode(response.code(), "Failed to update list"))
            }
        }
    }

    suspend fun deletePersonalList(listId: String) {
        performOptimisticMutation(
            optimistic = { snapshot ->
                val removedKeys = snapshot.listTabs
                    .filter { matchesPersonalListIdentifier(it, listId) }
                    .map { it.key }
                    .toSet()
                val updatedTabs = snapshot.listTabs.filterNot { it.key in removedKeys }
                val updatedEntries = snapshot.entriesByList.filterKeys { it !in removedKeys }
                rebuildSnapshot(updatedTabs, updatedEntries)
            }
        ) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.deleteUserList(
                    authorization = authHeader,
                    id = ME_PATH,
                    listId = listId
                )
            } ?: throw IllegalStateException("Trakt request failed")

            if (!response.isSuccessful && response.code() != 204) {
                throw IllegalStateException(errorMessageForCode(response.code(), "Failed to delete list"))
            }
        }
    }

    suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        val rank = orderedListIds.mapNotNull { raw ->
            raw.removePrefix(PERSONAL_KEY_PREFIX).toLongOrNull()
        }
        if (rank.isEmpty()) return

        performOptimisticMutation(
            optimistic = { snapshot ->
                val personalTabs = snapshot.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
                val orderedTabs = orderedListIds.mapNotNull { id ->
                    personalTabs.firstOrNull { matchesPersonalListIdentifier(it, id) }
                }.distinctBy { it.key }
                val remainingTabs = snapshot.listTabs.filter { tab ->
                    tab.type != LibraryListTab.Type.PERSONAL || orderedTabs.none { it.key == tab.key }
                }
                rebuildSnapshot(
                    tabs = remainingTabs + orderedTabs,
                    rawEntriesByList = snapshot.entriesByList
                )
            }
        ) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.reorderUserLists(
                    authorization = authHeader,
                    id = ME_PATH,
                    body = TraktReorderListsRequestDto(rank = rank)
                )
            } ?: throw IllegalStateException("Trakt request failed")

            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessageForCode(response.code(), "Failed to reorder lists"))
            }
        }
    }

    suspend fun refreshNow() {
        refresh(force = true)
    }

    suspend fun ensureFresh() {
        refresh(force = false)
    }

    private suspend fun refresh(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        return refreshMutex.withLock {
            if (!force && now - lastRefreshMs <= cacheTtlMs && snapshotState.value.updatedAtMs > 0L) {
                return@withLock true
            }

            refreshingState.value = true
            try {
                val previous = snapshotState.value
                val refreshed = runCatching { fetchSnapshot() }.getOrNull()
                val snapshotToUse = refreshed ?: previous
                snapshotState.value = snapshotToUse
                lastRefreshMs = now
                hydrateMetadata(snapshotToUse.allEntries)
                refreshed != null
            } finally {
                refreshingState.value = false
            }
        }
    }

    private suspend fun performOptimisticMutation(
        optimistic: (Snapshot) -> Snapshot,
        mutation: suspend () -> Unit
    ) {
        val before = snapshotState.value
        val optimisticSnapshot = optimistic(before)
        snapshotState.value = optimisticSnapshot
        hydrateMetadata(optimisticSnapshot.allEntries)
        try {
            mutation()
        } catch (error: Throwable) {
            snapshotState.value = before
            throw error
        }
    }

    private fun addItemToList(snapshot: Snapshot, item: LibraryEntryInput, listKey: String): Snapshot {
        val key = contentKey(item.itemId, item.itemType)
        val normalizedType = normalizeItemType(item.itemType)
        val normalizedId = normalizeContentId(resolveIds(item), fallback = item.itemId.trim())
            .ifBlank { item.itemId.trim() }
        val existing = snapshot.allEntries.firstOrNull { contentKey(it.id, it.type) == key }
        val entry = (existing ?: LibraryEntry(
            id = normalizedId,
            type = normalizedType,
            name = item.title.ifBlank { normalizedId },
            poster = item.poster,
            posterShape = item.posterShape,
            background = item.background,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo ?: item.year?.toString(),
            imdbRating = item.imdbRating,
            genres = item.genres,
            addonBaseUrl = item.addonBaseUrl
        )).copy(
            listedAt = System.currentTimeMillis(),
            listKeys = existing?.listKeys.orEmpty() + listKey
        )

        val existingEntries = snapshot.entriesByList[listKey].orEmpty()
        val updatedListEntries = listOf(entry) + existingEntries.filterNot { contentKey(it.id, it.type) == key }
        val updatedEntriesByList = snapshot.entriesByList + (listKey to updatedListEntries)
        return rebuildSnapshot(snapshot.listTabs, updatedEntriesByList)
    }

    private fun removeItemFromList(snapshot: Snapshot, item: LibraryEntryInput, listKey: String): Snapshot {
        val key = contentKey(item.itemId, item.itemType)
        val updatedEntriesByList = snapshot.entriesByList + (
            listKey to snapshot.entriesByList[listKey].orEmpty()
                .filterNot { contentKey(it.id, it.type) == key }
        )
        return rebuildSnapshot(snapshot.listTabs, updatedEntriesByList)
    }

    private fun rebuildSnapshot(
        tabs: List<LibraryListTab>,
        rawEntriesByList: Map<String, List<LibraryEntry>>
    ): Snapshot {
        val membership = mutableMapOf<String, MutableSet<String>>()
        rawEntriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                membership.getOrPut(contentKey(entry.id, entry.type)) { mutableSetOf() }
                    .add(listKey)
            }
        }

        val allEntriesByContent = linkedMapOf<String, LibraryEntry>()
        rawEntriesByList.values.flatten()
            .sortedByDescending { it.listedAt }
            .forEach { entry ->
                val key = contentKey(entry.id, entry.type)
                allEntriesByContent[key] = entry.copy(listKeys = membership[key].orEmpty())
            }

        val entriesByList = rawEntriesByList.mapValues { (_, entries) ->
            entries.map { entry ->
                val key = contentKey(entry.id, entry.type)
                entry.copy(listKeys = membership[key].orEmpty())
            }
        }

        return Snapshot(
            listTabs = tabs,
            entriesByList = entriesByList,
            allEntries = allEntriesByContent.values.toList(),
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun fetchSnapshot(): Snapshot {
        val watchlistEntries = fetchWatchlistEntries()

        val personalLists = fetchPersonalLists()
        val personalTabs = personalLists.tabs
        val personalEntriesByList = personalLists.entriesByList

        val tabs = buildList {
            add(
                LibraryListTab(
                    key = WATCHLIST_KEY,
                    title = "Watchlist",
                    type = LibraryListTab.Type.WATCHLIST,
                    sortBy = "rank",
                    sortHow = "asc"
                )
            )
            addAll(personalTabs)
        }

        val rawEntriesByList = linkedMapOf<String, List<LibraryEntry>>().apply {
            put(WATCHLIST_KEY, watchlistEntries)
            personalTabs.forEach { tab ->
                put(tab.key, personalEntriesByList[tab.key].orEmpty())
            }
        }

        val membership = mutableMapOf<String, MutableSet<String>>()
        rawEntriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                val contentKey = contentKey(entry.id, entry.type)
                membership.getOrPut(contentKey) { mutableSetOf() }.add(listKey)
            }
        }

        val allEntriesByContent = linkedMapOf<String, LibraryEntry>()
        rawEntriesByList.values.flatten()
            .sortedByDescending { it.listedAt }
            .forEach { entry ->
                val key = contentKey(entry.id, entry.type)
                allEntriesByContent[key] = entry
            }

        val allEntries = allEntriesByContent.map { (key, entry) ->
            entry.copy(listKeys = membership[key].orEmpty())
        }.sortedByDescending { it.listedAt }

        val entriesByList = rawEntriesByList.mapValues { (_, entries) ->
            entries.map { entry ->
                entry.copy(listKeys = membership[contentKey(entry.id, entry.type)].orEmpty())
            }
        }

        return Snapshot(
            listTabs = tabs,
            entriesByList = entriesByList,
            allEntries = allEntries,
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun fetchWatchlistEntries(): List<LibraryEntry> {
        val moviesResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getWatchlist(
                authorization = authHeader,
                type = "movies"
            )
        } ?: throw IllegalStateException("Failed to fetch watchlist movies")

        val showsResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getWatchlist(
                authorization = authHeader,
                type = "shows"
            )
        } ?: throw IllegalStateException("Failed to fetch watchlist shows")

        if (!moviesResponse.isSuccessful || !showsResponse.isSuccessful) {
            throw IllegalStateException("Failed to fetch watchlist")
        }

        return (moviesResponse.body().orEmpty() + showsResponse.body().orEmpty())
            .mapNotNull { mapListItem(listKey = WATCHLIST_KEY, item = it) }
            .sortedWith(
                compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.listedAt }
            )
    }

    private data class PersonalListFetchResult(
        val tabs: List<LibraryListTab>,
        val entriesByList: Map<String, List<LibraryEntry>>
    )

    private suspend fun fetchPersonalLists(): PersonalListFetchResult {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserLists(
                authorization = authHeader,
                id = ME_PATH
            )
        } ?: throw IllegalStateException("Failed to fetch personal lists")

        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch personal lists (${response.code()})")
        }

        val personal = response.body().orEmpty()
            .filter { it.type.equals("personal", ignoreCase = true) }

        val tabs = personal.mapNotNull { mapListTab(it) }
        val entriesByList = coroutineScope {
            val semaphore = Semaphore(listFetchConcurrency)
            tabs.map { tab ->
                async {
                    semaphore.withPermit {
                        val listIdPath = tab.traktListId?.toString() ?: tab.slug
                        if (listIdPath.isNullOrBlank()) {
                            tab.key to emptyList()
                        } else {
                            val movies = fetchPersonalListItems(listIdPath, "movie", tab.key)
                            val shows = fetchPersonalListItems(listIdPath, "show", tab.key)
                            tab.key to (movies + shows).sortedWith(
                                compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                                    .thenByDescending { it.listedAt }
                            )
                        }
                    }
                }
            }.map { it.await() }.toMap()
        }

        return PersonalListFetchResult(
            tabs = tabs,
            entriesByList = entriesByList
        )
    }

    private suspend fun fetchPersonalListItems(
        listIdPath: String,
        type: String,
        listKey: String
    ): List<LibraryEntry> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserListItems(
                authorization = authHeader,
                id = ME_PATH,
                listId = listIdPath,
                type = type
            )
        } ?: throw IllegalStateException("Failed to fetch list items")

        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch list items (${response.code()})")
        }
        return response.body().orEmpty()
            .mapNotNull { mapListItem(listKey = listKey, item = it) }
    }

    private fun mapListTab(dto: TraktListSummaryDto): LibraryListTab? {
        val traktId = dto.ids?.trakt
        val slug = dto.ids?.slug
        val listIdPath = traktId?.toString() ?: slug ?: return null

        return LibraryListTab(
            key = PERSONAL_KEY_PREFIX + listIdPath,
            title = dto.name?.takeIf { it.isNotBlank() } ?: "List",
            type = LibraryListTab.Type.PERSONAL,
            traktListId = traktId,
            slug = slug,
            description = dto.description,
            privacy = TraktListPrivacy.fromApi(dto.privacy),
            sortBy = dto.sortBy,
            sortHow = dto.sortHow
        )
    }

    private fun mapListItem(listKey: String, item: TraktListItemDto): LibraryEntry? {
        val normalizedType = when (item.type?.lowercase()) {
            "movie" -> "movie"
            "show" -> "series"
            else -> return null
        }

        val mediaTitle = when (normalizedType) {
            "movie" -> item.movie?.title
            else -> item.show?.title
        }

        val mediaYear = when (normalizedType) {
            "movie" -> item.movie?.year
            else -> item.show?.year
        }

        val ids = when (normalizedType) {
            "movie" -> item.movie?.ids
            else -> item.show?.ids
        }

        val fallbackId = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            item.id != null -> "trakt-item:${item.id}"
            !mediaTitle.isNullOrBlank() -> "${normalizedType}:${mediaTitle.lowercase()}:${mediaYear ?: 0}"
            else -> null
        } ?: return null

        val contentId = normalizeContentId(ids, fallback = fallbackId)
        if (contentId.isBlank()) return null

        return LibraryEntry(
            id = contentId,
            type = normalizedType,
            name = mediaTitle ?: contentId,
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = mediaYear?.toString(),
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(listKey),
            listedAt = parseIsoToMillis(item.listedAt),
            traktRank = item.rank
        )
    }

    private suspend fun addToWatchlist(item: LibraryEntryInput) {
        val body = buildMutationBody(item)
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.addToWatchlist(
                authorization = authHeader,
                body = body
            )
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful || !isSuccessfulAddResponse(response.body())) {
            throw IllegalStateException(errorMessageForCode(response.code(), "Failed to add to watchlist"))
        }
    }

    private suspend fun removeFromWatchlist(item: LibraryEntryInput) {
        val body = buildMutationBody(item)
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.removeFromWatchlist(
                authorization = authHeader,
                body = body
            )
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful) {
            throw IllegalStateException(errorMessageForCode(response.code(), "Failed to remove from watchlist"))
        }
    }

    private suspend fun addToPersonalList(listId: String, item: LibraryEntryInput) {
        val body = buildMutationBody(item)
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.addUserListItems(
                authorization = authHeader,
                id = ME_PATH,
                listId = listId,
                body = body
            )
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful || !isSuccessfulAddResponse(response.body())) {
            throw IllegalStateException(errorMessageForCode(response.code(), "Failed to add to list"))
        }
    }

    private suspend fun removeFromPersonalList(listId: String, item: LibraryEntryInput) {
        val body = buildMutationBody(item)
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.removeUserListItems(
                authorization = authHeader,
                id = ME_PATH,
                listId = listId,
                body = body
            )
        } ?: throw IllegalStateException("Trakt request failed")

        if (!response.isSuccessful) {
            throw IllegalStateException(errorMessageForCode(response.code(), "Failed to remove from list"))
        }
    }

    private fun buildMutationBody(item: LibraryEntryInput): TraktListItemsMutationRequestDto {
        val ids = resolveIds(item)
        if (!ids.hasAnyId()) {
            throw IllegalStateException("Missing compatible IDs for Trakt list operation")
        }

        val normalizedType = normalizeItemType(item.itemType)
        return if (normalizedType == "movie") {
            TraktListItemsMutationRequestDto(
                movies = listOf(
                    TraktListMovieRequestItemDto(
                        title = item.title,
                        year = item.year,
                        ids = ids
                    )
                )
            )
        } else {
            TraktListItemsMutationRequestDto(
                shows = listOf(
                    TraktListShowRequestItemDto(
                        title = item.title,
                        year = item.year,
                        ids = ids
                    )
                )
            )
        }
    }

    private fun resolveIds(item: LibraryEntryInput): TraktIdsDto {
        val parsed = parseContentIds(item.itemId)
        return TraktIdsDto(
            trakt = item.traktId ?: parsed.trakt,
            imdb = item.imdbId ?: parsed.imdb,
            tmdb = item.tmdbId ?: parsed.tmdb
        )
    }

    private fun isSuccessfulAddResponse(body: TraktListItemsMutationResponseDto?): Boolean {
        val added = body?.added
        val existing = body?.existing
        val addCount = (added?.movies ?: 0) + (added?.shows ?: 0) + (added?.seasons ?: 0) + (added?.episodes ?: 0)
        val existingCount = (existing?.movies ?: 0) + (existing?.shows ?: 0) + (existing?.seasons ?: 0) + (existing?.episodes ?: 0)
        return addCount > 0 || existingCount > 0
    }

    private fun errorMessageForCode(code: Int, defaultMessage: String): String {
        return when (code) {
            401, 403 -> "Trakt authentication expired"
            404 -> "Trakt list not found"
            420 -> "Trakt list limit reached. Upgrade required."
            else -> "$defaultMessage ($code)"
        }
    }

    private fun listIdFromKey(key: String): String? {
        if (!key.startsWith(PERSONAL_KEY_PREFIX)) return null
        return key.removePrefix(PERSONAL_KEY_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun matchesPersonalListIdentifier(tab: LibraryListTab, identifier: String): Boolean {
        if (tab.type != LibraryListTab.Type.PERSONAL) return false
        val normalized = identifier.removePrefix(PERSONAL_KEY_PREFIX)
        val tabKeySuffix = tab.key.removePrefix(PERSONAL_KEY_PREFIX)
        return tab.key == "$PERSONAL_KEY_PREFIX$normalized" ||
            tabKeySuffix == normalized ||
            tab.traktListId?.toString() == normalized ||
            tab.slug == normalized
    }

    private fun contentKey(itemId: String, itemType: String): String {
        val normalizedType = normalizeItemType(itemType)
        val parsed = parseContentIds(itemId)
        val normalizedId = normalizeContentId(toTraktIds(parsed), fallback = itemId.trim())
        val stableId = normalizedId.ifBlank { itemId.trim() }
        return "$normalizedType:$stableId"
    }

    private fun normalizeItemType(itemType: String): String {
        return when (itemType.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> itemType.lowercase()
        }
    }

    private fun enrichEntries(
        entries: List<LibraryEntry>,
        metadataMap: Map<String, LibraryMetadata>
    ): List<LibraryEntry> {
        return entries.map { entry ->
            val metadata = metadataMap[contentKey(entry.id, entry.type)] ?: return@map entry
            val shouldOverrideName = entry.name.isBlank() || entry.name == entry.id
            entry.copy(
                name = if (shouldOverrideName) metadata.name ?: entry.name else entry.name,
                poster = entry.poster ?: metadata.poster,
                background = entry.background ?: metadata.background,
                logo = entry.logo ?: metadata.logo,
                description = entry.description ?: metadata.description,
                releaseInfo = entry.releaseInfo ?: metadata.releaseInfo,
                imdbRating = entry.imdbRating ?: metadata.imdbRating,
                genres = if (entry.genres.isEmpty()) metadata.genres else entry.genres
            )
        }
    }

    private fun hydrateMetadata(entries: List<LibraryEntry>) {
        entries.take(metadataHydrationLimit).forEach { entry ->
            val key = contentKey(entry.id, entry.type)
            if (metadataState.value.containsKey(key)) return@forEach

            scope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(key)) return@withLock false
                    if (inFlightMetadataKeys.contains(key)) return@withLock false
                    inFlightMetadataKeys.add(key)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    metadataFetchSemaphore.withPermit {
                        val metadata = fetchMetadata(entry) ?: return@launch
                        metadataState.update { current ->
                            current + (key to metadata)
                        }
                    }
                } finally {
                    metadataMutex.withLock { inFlightMetadataKeys.remove(key) }
                }
            }
        }
    }

    private suspend fun fetchMetadata(entry: LibraryEntry): LibraryMetadata? {
        val typeCandidates = if (entry.type == "movie") {
            listOf("movie")
        } else {
            listOf("series", "tv")
        }

        val idCandidates = buildList {
            add(entry.id)
            if (entry.id.startsWith("tmdb:")) add(entry.id.substringAfter(':'))
            if (entry.id.startsWith("trakt:")) add(entry.id.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (id in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = id)
                        .first { it !is NetworkResult.Loading }
                } ?: continue
                val meta = (result as? NetworkResult.Success)?.data ?: continue
                return LibraryMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating,
                    genres = meta.genres
                )
            }
        }

        return null
    }

    companion object {
        const val WATCHLIST_KEY = "watchlist"
        const val PERSONAL_KEY_PREFIX = "personal:"
        private const val ME_PATH = "me"
    }
}
