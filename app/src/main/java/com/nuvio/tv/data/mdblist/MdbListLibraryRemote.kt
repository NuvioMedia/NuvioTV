package com.nuvio.tv.data.mdblist

internal class MdbListLibraryRemote(private val api: MdbListApiClient, private val scope: MdbListAuthScope) {
    suspend fun synchronize(previous: MdbListLibrarySnapshot?, accountId: Long, now: Long): MdbListLibrarySnapshot {
        val lists = decodeMdbListLibraryLists(api.get("/lists/user", mapOf("unified" to "false", "sort" to "ranked"), scope).body, accountId)
        val items = linkedMapOf(MDBLIST_WATCHLIST_KEY to items(MDBLIST_WATCHLIST_KEY))
        val previousLists = previous?.lists.orEmpty().associateBy { it.id }
        val addedOrders = previous?.addedOrders.orEmpty().toMutableMap().apply { remove(MDBLIST_WATCHLIST_KEY) }
        val hidden = previous?.hiddenListKeys.orEmpty()
        for (list in lists) {
            // Hidden lists are not shown anywhere, so skip downloading their items.
            if (list.key in hidden) {
                addedOrders.remove(list.key)
                continue
            }
            val cached = previous?.itemsByList?.get(list.key)
            val unchanged = previous?.invalidated != true && list.updatedAt != null &&
                previousLists[list.id]?.updatedAt == list.updatedAt
            items[list.key] = if (unchanged && cached != null) cached else items(list.key)
            if (!unchanged || cached == null) addedOrders.remove(list.key)
        }
        val snapshot = MdbListLibrarySnapshot(lists, items, now, addedOrders = addedOrders.filterKeys { it in items })
        // Drop hidden keys of lists that were deleted in MDBList.
        return snapshot.copy(hiddenListKeys = hidden intersect snapshot.tabs().mapTo(mutableSetOf()) { it.key })
    }

    suspend fun items(key: String, addedOrder: String? = null): List<MdbListLibraryItem> {
        val path = mdbListLibraryItemsPath(key)
        val initial = mapOf("limit" to "1000", "sort" to if (addedOrder == null) "rank" else "added",
            "order" to (addedOrder ?: "asc"), "unified" to "true") +
            if (addedOrder == null) mapOf("append_to_response" to "poster,description,genres") else emptyMap()
        var query = initial
        val visited = mutableSetOf<Map<String, String>>()
        val items = mutableListOf<MdbListLibraryItem>()
        repeat(1_000) {
            if (!visited.add(query)) throw MdbListDecodingException()
            val response = api.get(path, query, scope)
            val page = decodeMdbListLibraryPage(response.body)
            items += page.items
            val nextCursor = page.nextCursor ?: response.header("X-Next-Cursor")?.takeIf { it.isNotBlank() }
            query = when {
                nextCursor != null -> initial + ("cursor" to nextCursor)
                page.nextOffset != null -> initial + ("offset" to page.nextOffset.toString())
                response.header("X-Has-More").equals("true", ignoreCase = true) -> throw MdbListDecodingException()
                else -> return items.distinctBy { it.key }
            }
        }
        throw MdbListDecodingException()
    }
}

internal fun mdbListLibraryItemsPath(key: String): String =
    if (key == MDBLIST_WATCHLIST_KEY) "/watchlist/items" else "/lists/${mdbListPersonalListId(key)}/items"

internal fun mdbListPersonalListId(key: String): Long {
    require(key.startsWith(MDBLIST_LIST_KEY_PREFIX)) { "Invalid MDBList list" }
    return requireNotNull(key.removePrefix(MDBLIST_LIST_KEY_PREFIX).toLongOrNull()?.takeIf { it > 0 }) { "Invalid MDBList list" }
}
