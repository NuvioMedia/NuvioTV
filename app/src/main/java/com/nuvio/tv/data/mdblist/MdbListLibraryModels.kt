package com.nuvio.tv.data.mdblist

import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.LibraryListTab
import kotlinx.serialization.Serializable

internal const val MDBLIST_WATCHLIST_KEY = "mdblist:watchlist"
internal const val MDBLIST_LIST_KEY_PREFIX = "mdblist:list:"
internal const val MDBLIST_EXTERNAL_LIST_KEY_PREFIX = "mdblist:external:"

/** Bumped when list items are requested in a different order, so cached lists are downloaded again. */
internal const val MDBLIST_ITEMS_ORDER = 1

@Serializable
data class MdbListLibraryList(
    val id: Long,
    val name: String,
    val private: Boolean,
    val description: String? = null,
    val mediaType: MdbListItemType? = null,
    val updatedAt: String? = null
) {
    val key: String get() = "$MDBLIST_LIST_KEY_PREFIX$id"

    fun tab() = LibraryListTab(
        key = key,
        title = name,
        type = LibraryListTab.Type.PERSONAL,
        privacy = if (private) LibraryListPrivacy.PRIVATE else LibraryListPrivacy.PUBLIC,
        description = description,
        trackingProviderId = "mdblist",
        supportedContentTypes = when (mediaType) {
            MdbListItemType.MOVIE -> setOf("movie")
            MdbListItemType.SHOW -> setOf("series")
            else -> setOf("movie", "series")
        }
    )
}

@Serializable
data class MdbListExternalList(
    val id: Long,
    val name: String,
    val source: String? = null,
    val mediaType: MdbListItemType? = null,
    val updatedAt: String? = null
) {
    val key: String get() = "$MDBLIST_EXTERNAL_LIST_KEY_PREFIX$id"

    // External lists mirror another service, so MDBList only allows reading them.
    fun tab() = LibraryListTab(
        key = key,
        title = name,
        type = LibraryListTab.Type.EXTERNAL,
        description = source,
        trackingProviderId = "mdblist",
        supportedContentTypes = when (mediaType) {
            MdbListItemType.MOVIE -> setOf("movie")
            MdbListItemType.SHOW -> setOf("series")
            else -> setOf("movie", "series")
        },
        isMembershipDestination = false
    )
}

@Serializable
data class MdbListLibraryItem(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: Long = 0,
    val rank: Int? = null,
    val releaseDate: String? = null
) {
    val key: String get() = "$type:${media.ids.key}"
    fun matches(other: MdbListLibraryItem): Boolean = type == other.type && media.ids.matches(other.media.ids)
}

@Serializable
data class MdbListLibraryOrderItem(
    val type: MdbListItemType,
    val ids: MdbListIds
)

@Serializable
data class MdbListLibrarySnapshot(
    val lists: List<MdbListLibraryList> = emptyList(),
    val itemsByList: Map<String, List<MdbListLibraryItem>> = emptyMap(),
    val checkedAtEpochMs: Long? = null,
    val invalidated: Boolean = false,
    val addedOrders: Map<String, Map<String, List<MdbListLibraryOrderItem>>> = emptyMap(),
    val hiddenListKeys: Set<String> = emptySet(),
    val itemsOrder: Int = 0,
    val externalLists: List<MdbListExternalList> = emptyList()
) {
    /** Tabs shown in Nuvio: lists the user hid in MDBList settings are left out. */
    fun visibleTabs(): List<LibraryListTab> = tabs().filterNot { it.key in hiddenListKeys }

    /** Items of the visible lists only. */
    fun visible(): MdbListLibrarySnapshot = if (hiddenListKeys.isEmpty()) this else copy(itemsByList = itemsByList - hiddenListKeys)

    /** Every list except the Watchlist can be hidden. */
    fun listOptions(): List<MdbListLibraryListOption> = tabs()
        .filter { it.type != LibraryListTab.Type.WATCHLIST }
        .map { MdbListLibraryListOption(it.key, it.title, it.key !in hiddenListKeys) }

    fun tabs(): List<LibraryListTab> = listOf(
        LibraryListTab(
            MDBLIST_WATCHLIST_KEY, "Watchlist", LibraryListTab.Type.WATCHLIST,
            trackingProviderId = "mdblist", supportedContentTypes = setOf("movie", "series")
        )
    ) + lists.map(MdbListLibraryList::tab) + externalLists.map(MdbListExternalList::tab)
}

data class MdbListLibraryListOption(
    val key: String,
    val name: String,
    val visible: Boolean
)
