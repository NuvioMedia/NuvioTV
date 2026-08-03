package com.nuvio.tv.domain.model

object LibrarySyncReducer {
    fun sanitize(state: LibrarySyncState): LibrarySyncState {
        val items = state.items.toItemMap()
        val pendingUpserts = state.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = state.pendingDeleteKeys.toKeyMap()

        pendingUpserts.keys.retainAll(items.keys)
        pendingUpserts.keys.forEach(pendingDeletes::remove)
        pendingDeletes.keys.forEach(items::remove)

        return state.copy(
            items = items.values.toList(),
            deltaCursorEventId = state.deltaCursorEventId.coerceAtLeast(0L),
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = state.mutationRevision.coerceAtLeast(0L)
        )
    }

    fun upsertLocal(
        state: LibrarySyncState,
        item: SavedLibraryItem,
        nowEpochMs: Long
    ): LibrarySyncState {
        val current = sanitize(state)
        if (item.id.isBlank() || item.type.isBlank()) return current

        val storedItem = if (item.addedAt == 0L) {
            item.copy(addedAt = nowEpochMs)
        } else {
            item
        }
        val identity = librarySyncIdentity(storedItem.id, storedItem.type)
        val items = current.items.toItemMap()
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()

        items[identity] = storedItem
        pendingUpserts[identity] = storedItem.toLibrarySyncKey()
        pendingDeletes.remove(identity)

        return current.copy(
            items = items.values.toList(),
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun deleteLocal(
        state: LibrarySyncState,
        contentId: String,
        contentType: String
    ): LibrarySyncState {
        val current = sanitize(state)
        val identity = librarySyncIdentity(contentId, contentType)
        val items = current.items.toItemMap()
        val removed = items.remove(identity) ?: return current
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()

        pendingUpserts.remove(identity)
        pendingDeletes[identity] = removed.toLibrarySyncKey()

        return current.copy(
            items = items.values.toList(),
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun queueAllItemsForPush(state: LibrarySyncState): LibrarySyncState {
        val current = sanitize(state)
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        val beforeUpserts = pendingUpserts.toMap()
        val beforeDeletes = pendingDeletes.toMap()

        current.items.forEach { item ->
            val identity = librarySyncIdentity(item.id, item.type)
            pendingUpserts[identity] = item.toLibrarySyncKey()
            pendingDeletes.remove(identity)
        }

        if (pendingUpserts == beforeUpserts && pendingDeletes == beforeDeletes) {
            return current
        }

        return current.copy(
            pendingUpsertKeys = pendingUpserts.values.toList(),
            pendingDeleteKeys = pendingDeletes.values.toList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun applySnapshot(
        state: LibrarySyncState,
        remoteItems: kotlin.collections.Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult {
        val current = sanitize(state)
        val localItems = current.items.toItemMap()
        val remoteItemsByKey = linkedMapOf<String, SavedLibraryItem>()
        remoteItems.forEach { remoteItem ->
            if (remoteItem.id.isBlank() || remoteItem.type.isBlank()) return@forEach
            val identity = librarySyncIdentity(remoteItem.id, remoteItem.type)
            remoteItemsByKey[identity] = remoteItem.withPreservedLocalMetadata(localItems[identity])
        }
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        val beforeUpserts = pendingUpserts.toMap()
        val migrateLegacyLocal =
            !current.deltaInitialized &&
                remoteItemsByKey.isEmpty() &&
                localItems.isNotEmpty()

        val reconciledItems = if (migrateLegacyLocal) {
            localItems
        } else {
            pendingDeletes.keys.forEach(remoteItemsByKey::remove)
            pendingUpserts.keys.forEach { identity ->
                localItems[identity]?.let { localItem ->
                    remoteItemsByKey[identity] = localItem
                }
            }
            remoteItemsByKey
        }

        if (migrateLegacyLocal) {
            localItems.forEach { (identity, item) ->
                pendingUpserts[identity] = item.toLibrarySyncKey()
            }
        }

        val pendingChanged = pendingUpserts != beforeUpserts
        return LibrarySnapshotApplyResult(
            state = current.copy(
                items = reconciledItems.values.toList(),
                deltaCursorEventId = cursorEventId.coerceAtLeast(0L),
                deltaInitialized = true,
                pendingUpsertKeys = pendingUpserts.values.toList(),
                pendingDeleteKeys = pendingDeletes.values.toList(),
                mutationRevision = current.mutationRevision + if (pendingChanged) 1L else 0L
            ),
            preservedLocalItems =
                migrateLegacyLocal ||
                    pendingUpserts.isNotEmpty() ||
                    pendingDeletes.isNotEmpty()
        )
    }

    fun applyDelta(
        state: LibrarySyncState,
        events: kotlin.collections.Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult {
        val current = sanitize(state)
        val items = current.items.toItemMap()
        val pendingUpserts = current.pendingUpsertKeys.toKeyMap()
        val pendingDeletes = current.pendingDeleteKeys.toKeyMap()
        var appliedUpserts = 0
        var appliedDeletes = 0

        events.sortedBy(LibraryDeltaEvent::eventId).forEach { event ->
            val identity = librarySyncIdentity(event.item.id, event.item.type)
            if (identity in pendingUpserts || identity in pendingDeletes) return@forEach

            when (event.operation.trim().lowercase()) {
                "upsert" -> {
                    items[identity] = event.item.withPreservedLocalMetadata(items[identity])
                    appliedUpserts += 1
                }
                "delete" -> {
                    items.remove(identity)
                    appliedDeletes += 1
                }
            }
        }

        val cursor = maxOf(
            current.deltaCursorEventId,
            events.maxOfOrNull(LibraryDeltaEvent::eventId) ?: current.deltaCursorEventId
        )
        return LibraryDeltaApplyResult(
            state = current.copy(
                items = items.values.toList(),
                deltaCursorEventId = cursor,
                deltaInitialized = true
            ),
            appliedUpserts = appliedUpserts,
            appliedDeletes = appliedDeletes
        )
    }

    fun acknowledgePush(
        state: LibrarySyncState,
        expectedMutationRevision: Long
    ): LibrarySyncState? {
        val current = sanitize(state)
        if (current.mutationRevision != expectedMutationRevision) return null

        return current.copy(
            pendingUpsertKeys = emptyList(),
            pendingDeleteKeys = emptyList(),
            mutationRevision = current.mutationRevision + 1L
        )
    }

    fun pendingUpsertItems(state: LibrarySyncState): List<SavedLibraryItem> {
        val current = sanitize(state)
        val items = current.items.toItemMap()
        return current.pendingUpsertKeys.mapNotNull { key ->
            items[librarySyncIdentity(key.contentId, key.contentType)]
        }
    }
}

private fun kotlin.collections.Collection<SavedLibraryItem>.toItemMap(): LinkedHashMap<String, SavedLibraryItem> {
    val result = linkedMapOf<String, SavedLibraryItem>()
    forEach { item ->
        if (item.id.isNotBlank() && item.type.isNotBlank()) {
            result[librarySyncIdentity(item.id, item.type)] = item
        }
    }
    return result
}

private fun kotlin.collections.Collection<LibrarySyncKey>.toKeyMap(): LinkedHashMap<String, LibrarySyncKey> {
    val result = linkedMapOf<String, LibrarySyncKey>()
    forEach { key ->
        if (key.contentId.isNotBlank() && key.contentType.isNotBlank()) {
            result[librarySyncIdentity(key.contentId, key.contentType)] = key
        }
    }
    return result
}

/**
 * When cloud/remote rows are sparse (common for older library records), keep richer
 * local fields so year/genre filters and logos do not regress after a pull.
 */
internal fun SavedLibraryItem.withPreservedLocalMetadata(localItem: SavedLibraryItem?): SavedLibraryItem {
    if (localItem == null) return this
    val preserveLogo = logo == null && localItem.logo != null
    val preserveReleaseInfo = releaseInfo.isNullOrBlank() && !localItem.releaseInfo.isNullOrBlank()
    val preserveGenres = genres.isEmpty() && localItem.genres.isNotEmpty()
    val preserveImdbRating = imdbRating == null && localItem.imdbRating != null
    val preserveDescription = description.isNullOrBlank() && !localItem.description.isNullOrBlank()
    if (!preserveLogo && !preserveReleaseInfo && !preserveGenres && !preserveImdbRating && !preserveDescription) {
        return this
    }
    return copy(
        logo = if (preserveLogo) localItem.logo else logo,
        releaseInfo = if (preserveReleaseInfo) localItem.releaseInfo else releaseInfo,
        genres = if (preserveGenres) localItem.genres else genres,
        imdbRating = if (preserveImdbRating) localItem.imdbRating else imdbRating,
        description = if (preserveDescription) localItem.description else description
    )
}
