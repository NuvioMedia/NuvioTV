package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.PosterShape

const val SIMKL_STATUS_SELECTION_GROUP = "simkl:status"

data class SimklLibraryStatusDefinition(
    val status: SimklListStatus,
    val key: String,
    val title: String,
    val trackingStatus: TrackingListStatus,
    val supportedContentTypes: Set<String>,
    val isMembershipDestination: Boolean = true
)

data class SimklLibraryProjection(
    val items: List<LibraryEntry>,
    val itemsByStatus: Map<String, List<LibraryEntry>>,
    val tabs: List<LibraryListTab>
)

val simklLibraryStatusDefinitions = listOf(
    SimklLibraryStatusDefinition(
        SimklListStatus.WATCHING,
        "simkl:status:watching",
        "Watching",
        TrackingListStatus.WATCHING,
        setOf("series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.PLAN_TO_WATCH,
        "simkl:status:plantowatch",
        "Plan to Watch",
        TrackingListStatus.PLAN_TO_WATCH,
        setOf("movie", "series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.ON_HOLD,
        "simkl:status:hold",
        "On Hold",
        TrackingListStatus.ON_HOLD,
        setOf("series", "anime")
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.COMPLETED,
        "simkl:status:completed",
        "Completed",
        TrackingListStatus.COMPLETED,
        setOf("movie", "series", "anime"),
        isMembershipDestination = false
    ),
    SimklLibraryStatusDefinition(
        SimklListStatus.DROPPED,
        "simkl:status:dropped",
        "Dropped",
        TrackingListStatus.DROPPED,
        setOf("movie", "series", "anime")
    )
)

fun SimklSyncSnapshot.toSimklLibraryProjection(): SimklLibraryProjection {
    val itemsByStatus = simklLibraryStatusDefinitions.associate { definition ->
        definition.key to entries.asSequence()
            .filter { entry -> entry.status == definition.status }
            .mapNotNull { entry -> entry.toLibraryEntry(definition.key, lastSyncedAtEpochMs) }
            .distinctBy { item -> "${item.type}:${item.id}" }
            .sortedByDescending(LibraryEntry::listedAt)
            .toList()
    }
    val tabs = simklLibraryStatusDefinitions.map { definition ->
        LibraryListTab(
            key = definition.key,
            title = definition.title,
            type = if (definition.status == SimklListStatus.PLAN_TO_WATCH) {
                LibraryListTab.Type.WATCHLIST
            } else {
                LibraryListTab.Type.STATUS
            },
            trackingProviderId = TrackingProviderId.SIMKL.storageId,
            selectionGroup = SIMKL_STATUS_SELECTION_GROUP,
            supportedContentTypes = definition.supportedContentTypes,
            isMembershipDestination = definition.isMembershipDestination
        )
    }
    return SimklLibraryProjection(
        items = itemsByStatus.values.flatten()
            .distinctBy { item -> "${item.type}:${item.id}" }
            .sortedByDescending(LibraryEntry::listedAt),
        itemsByStatus = itemsByStatus,
        tabs = tabs
    )
}

fun simklLibraryStatusDefinition(key: String): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.key == key }

fun simklLibraryStatusDefinition(status: TrackingListStatus): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.trackingStatus == status }

private fun SimklLibraryEntry.toLibraryEntry(
    listKey: String,
    lastSyncedAtEpochMs: Long?
): LibraryEntry? {
    val media = media ?: return null
    val contentId = media.canonicalContentId() ?: return null
    val simklId = media.ids.simklIdValue()?.toLongOrNull()
    val entryType = when (mediaType) {
        SimklMediaType.MOVIES -> "movie"
        SimklMediaType.ANIME -> if (animeType == "movie") "movie" else "series"
        SimklMediaType.SHOWS -> "series"
    }
    return LibraryEntry(
        id = contentId,
        type = if (entryType == "tv") "series" else entryType,
        name = media.title?.takeIf(String::isNotBlank) ?: contentId,
        poster = resolvedPosterUrl(),
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = media.year?.toString(),
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(listKey),
        listedAt = parseSimklUtcEpochMs(addedToWatchlistAt)
            ?: parseSimklUtcEpochMs(lastWatchedAt)
            ?: lastSyncedAtEpochMs
            ?: 0L,
        imdbId = media.ids.idValue("imdb"),
        tmdbId = media.ids.idValue("tmdb")?.toIntOrNull(),
        simklId = simklId,
        mediaCategory = if (mediaType == SimklMediaType.ANIME) "anime" else null,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = simklId?.let { "simkl:$it" },
        trackingSourceUrl = buildSimklSourceUrl(mediaType, media)
    )
}
