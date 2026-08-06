package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal fun reconcileConclusiveNextUpItems(
    currentItems: List<ContinueWatchingItem>,
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>
): List<ContinueWatchingItem> {
    val resolvedByContentId = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .associateBy { item -> item.info.contentId }
    val inProgressContentIds = currentItems.mapNotNullTo(mutableSetOf()) { item ->
        (item as? ContinueWatchingItem.InProgress)?.progress?.contentId
    }
    val retainedItems = currentItems.filterNot { item ->
        item is ContinueWatchingItem.NextUp &&
            item.info.contentId in conclusivelyProcessedContentIds
    }
    val acceptedResolvedItems = resolvedByContentId.values.filter { item ->
        item.info.contentId !in inProgressContentIds &&
            item.info.contentId in conclusivelyProcessedContentIds &&
            nextUpDismissKey(
                item.info.contentId,
                item.info.seedSeason,
                item.info.seedEpisode
            ) !in dismissedNextUpKeys
    }
    return retainedItems + acceptedResolvedItems
}

internal fun mergeConcurrentContinueWatchingEnrichment(
    currentItems: List<ContinueWatchingItem>,
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    val replacements = originalItems.zip(enrichedItems).associate { (original, enriched) ->
        original.reconciliationKey() to enriched
    }
    return currentItems.map { current ->
        replacements[current.reconciliationKey()] ?: current
    }
}

internal suspend fun HomeViewModel.applyConclusiveOlderNextUpResults(
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    sortMode: ContinueWatchingSortMode,
    pipelineProfileId: Int,
    persistSnapshot: Boolean
) {
    if (resolvedItems.isEmpty() && conclusivelyProcessedContentIds.isEmpty()) return

    val acceptedResolvedItems = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .filter { item ->
            item.info.contentId in conclusivelyProcessedContentIds &&
                nextUpDismissKey(
                    item.info.contentId,
                    item.info.seedSeason,
                    item.info.seedEpisode
                ) !in dismissedNextUpKeys
        }
    val reconciledContentIds = conclusivelyProcessedContentIds

    synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.removeAll { item ->
            item.info.contentId in reconciledContentIds
        }
        discoveredOlderNextUpItems.addAll(acceptedResolvedItems)
    }
    reconciledContentIds.forEach(cwEnrichedNextUpOverlay::remove)

    _uiState.update { state ->
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            resolvedItems = acceptedResolvedItems,
            conclusivelyProcessedContentIds = conclusivelyProcessedContentIds,
            dismissedNextUpKeys = dismissedNextUpKeys
        )
        val sorted = sortContinueWatchingItems(reconciled, sortMode)
        val (main, upcoming) = splitUpcomingItems(sorted, sortMode)
        if (state.continueWatchingItems == main && state.upcomingItems == upcoming) {
            state
        } else {
            state.copy(continueWatchingItems = main, upcomingItems = upcoming)
        }
    }

    if (!persistSnapshot) return
    withContext(Dispatchers.IO) {
        if (profileManager.activeProfileId.value != pipelineProfileId) return@withContext
        val snapshot = (_uiState.value.continueWatchingItems + _uiState.value.upcomingItems)
            .toCachedNextUpSnapshot(com.nuvio.tv.ui.components.brokenImageUrls)
        runCatching {
            cwEnrichmentCache.saveNextUpSnapshot(snapshot, force = true)
        }
    }
}

internal fun List<ContinueWatchingItem>.toCachedNextUpSnapshot(
    brokenImageUrls: Set<String>
): List<CachedNextUpItem> = mapNotNull { item ->
    val info = (item as? ContinueWatchingItem.NextUp)?.info ?: return@mapNotNull null
    CachedNextUpItem(
        contentId = info.contentId,
        contentType = info.contentType,
        name = info.name,
        poster = info.poster,
        backdrop = info.backdrop,
        logo = info.logo,
        videoId = info.videoId,
        season = info.season,
        episode = info.episode,
        episodeTitle = info.episodeTitle,
        episodeDescription = info.episodeDescription,
        thumbnail = info.thumbnail?.takeIf { imageUrl -> imageUrl !in brokenImageUrls },
        released = info.released,
        hasAired = info.hasAired,
        airDateLabel = info.airDateLabel,
        lastWatched = info.lastWatched,
        imdbRating = info.imdbRating,
        genres = info.genres,
        releaseInfo = info.releaseInfo,
        sortTimestamp = info.sortTimestamp,
        releaseTimestamp = info.releaseTimestamp,
        isReleaseAlert = info.isReleaseAlert,
        isNewSeasonRelease = info.isNewSeasonRelease,
        seedSeason = info.seedSeason,
        seedEpisode = info.seedEpisode,
        contentLanguage = info.contentLanguage
    )
}

private fun ContinueWatchingItem.reconciliationKey(): String = when (this) {
    is ContinueWatchingItem.InProgress -> listOf(
        "progress",
        progress.contentId,
        progress.season,
        progress.episode,
        progress.videoId
    ).joinToString("|")

    is ContinueWatchingItem.NextUp -> listOf(
        "next",
        info.contentId,
        info.season,
        info.episode,
        info.seedSeason,
        info.seedEpisode
    ).joinToString("|")
}
