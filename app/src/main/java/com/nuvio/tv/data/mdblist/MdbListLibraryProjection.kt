package com.nuvio.tv.data.mdblist

import com.nuvio.tv.domain.model.LibraryEntry

internal class MdbListLibraryProjection(snapshot: MdbListLibrarySnapshot) {
    val entries: List<LibraryEntry>
    private val memberships = mutableMapOf<Pair<MdbListItemType, String>, Set<String>>()

    init {
        val index = MdbListMediaIndex(MdbListSyncSnapshot(0))
        snapshot.itemsByList.values.flatten().forEach { index.add(it.type, it.media) }
        val combined = linkedMapOf<String, LibraryEntry>()
        snapshot.itemsByList.forEach { (listKey, items) ->
            items.forEach { item ->
                val media = index.resolve(item.type, item.media.ids)
                val key = "${item.type}:${media.ids.key}"
                val previous = combined[key]
                val listKeys = previous?.listKeys.orEmpty() + listKey
                val ranks = previous?.listRanks.orEmpty() + listOfNotNull(item.rank?.let { listKey to it }).toMap()
                combined[key] = LibraryEntry(
                    id = media.ids.contentId,
                    type = if (item.type == MdbListItemType.MOVIE) "movie" else "series",
                    name = media.title ?: media.ids.contentId,
                    poster = media.poster,
                    background = media.backdrop,
                    logo = null,
                    description = previous?.description ?: item.description,
                    releaseInfo = media.year?.toString(),
                    imdbRating = null,
                    genres = (previous?.genres.orEmpty() + item.genres).distinct(),
                    addonBaseUrl = null,
                    listKeys = listKeys,
                    listRanks = ranks,
                    listedAt = maxOf(previous?.listedAt ?: 0, item.listedAt),
                    imdbId = media.ids.imdb,
                    tmdbId = media.ids.tmdb?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                    traktId = media.ids.trakt?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                    trackingProviderId = "mdblist",
                    trackingProviderItemId = media.ids.mdblist,
                    trackingSourceUrl = media.ids.mdblist?.let { "https://mdblist.com/${if (item.type == MdbListItemType.MOVIE) "movie" else "show"}/$it" }
                )
                media.ids.aliases().forEach { alias -> memberships[item.type to alias] = listKeys }
            }
        }
        entries = combined.values.toList()
    }

    fun membership(id: String, type: String): Set<String> = memberships[mdbListLibraryType(type) to id].orEmpty()
}
