package com.nuvio.tv.data.mdblist

import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.LibraryListTab
import kotlinx.serialization.Serializable

internal const val MDBLIST_WATCHLIST_KEY = "mdblist:watchlist"
internal const val MDBLIST_LIST_KEY_PREFIX = "mdblist:list:"

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
data class MdbListLibraryItem(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: Long = 0,
    val rank: Int? = null
) {
    val key: String get() = "$type:${media.ids.key}"
    fun matches(other: MdbListLibraryItem): Boolean = type == other.type && media.ids.matches(other.media.ids)
}

@Serializable
data class MdbListLibrarySnapshot(
    val lists: List<MdbListLibraryList> = emptyList(),
    val itemsByList: Map<String, List<MdbListLibraryItem>> = emptyMap(),
    val checkedAtEpochMs: Long? = null,
    val invalidated: Boolean = false
) {
    fun tabs(): List<LibraryListTab> = listOf(
        LibraryListTab(
            MDBLIST_WATCHLIST_KEY, "Watchlist", LibraryListTab.Type.WATCHLIST,
            trackingProviderId = "mdblist", supportedContentTypes = setOf("movie", "series")
        )
    ) + lists.map(MdbListLibraryList::tab)
}
