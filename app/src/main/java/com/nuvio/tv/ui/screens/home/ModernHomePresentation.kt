package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow

@Immutable
internal data class ModernHomePresentationInput(
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    val visibleCatalogRows = input.catalogRows.filter { it.items.isNotEmpty() }
    val catalogRowsToRender = maxCatalogRows
        ?.coerceAtLeast(0)
        ?.let(visibleCatalogRows::take)
        ?: visibleCatalogRows
    val strContinueWatching = context.getString(R.string.continue_watching)
    val strAirsDate = context.getString(R.string.cw_airs_date)
    val strUpcoming = context.getString(R.string.cw_upcoming)
    val strTypeMovie = context.getString(R.string.type_movie)
    val strTypeSeries = context.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>(catalogRowsToRender.size)

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == strContinueWatching &&
                    cache.continueWatchingAirsDateTemplate == strAirsDate &&
                    cache.continueWatchingUpcomingLabel == strUpcoming &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters
            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = "continue_watching",
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = context
                        )
                    }
                )
            }
            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = strContinueWatching
            cache.continueWatchingAirsDateTemplate = strAirsDate
            cache.continueWatchingUpcomingLabel = strUpcoming
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
        }

        catalogRowsToRender.forEachIndexed { index, row ->
            val rowKey = catalogRowKey(row)
            activeCatalogKeys += rowKey
            val cached = cache.catalogRows[rowKey]
            val canReuseMappedRow =
                cached != null &&
                    cached.source == row &&
                    cached.useLandscapePosters == input.useLandscapePosters &&
                    cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix

            val mappedRow = if (canReuseMappedRow) {
                val cachedMappedRow = checkNotNull(cached).mappedRow
                if (cachedMappedRow.globalRowIndex == index) {
                    cachedMappedRow
                } else {
                    cachedMappedRow.copy(globalRowIndex = index)
                }
            } else {
                val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                HeroCarouselRow(
                    key = rowKey,
                    title = catalogRowTitle(
                        row = row,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        strTypeMovie = strTypeMovie,
                        strTypeSeries = strTypeSeries
                    ),
                    globalRowIndex = index,
                    catalogId = row.catalogId,
                    addonId = row.addonId,
                    apiType = row.apiType,
                    supportsSkip = row.supportsSkip,
                    hasMore = row.hasMore,
                    isLoading = row.isLoading,
                    items = row.items.map { item ->
                        val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                        rowItemOccurrenceCounts[item.id] = occurrence + 1
                        val cacheKey = "${item.id}_$occurrence"
                        val cachedItem = rowItemCache[cacheKey]
                        if (cachedItem != null &&
                            cachedItem.source == item &&
                            cachedItem.useLandscapePosters == input.useLandscapePosters &&
                            cachedItem.showFullReleaseDate == input.showFullReleaseDate
                        ) {
                            cachedItem.carouselItem
                        } else {
                            val built = buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = input.useLandscapePosters,
                                occurrence = occurrence,
                                strTypeMovie = strTypeMovie,
                                strTypeSeries = strTypeSeries,
                                showFullReleaseDate = input.showFullReleaseDate,
                                previousCachedItem = cachedItem?.carouselItem
                            )
                            rowItemCache[cacheKey] = CachedCarouselItem(
                                source = item,
                                useLandscapePosters = input.useLandscapePosters,
                                showFullReleaseDate = input.showFullReleaseDate,
                                carouselItem = built
                            )
                            built
                        }
                    }
                )
            }

            cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                source = row,
                useLandscapePosters = input.useLandscapePosters,
                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                mappedRow = mappedRow
            )
            add(mappedRow)
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
    }

    return ModernHomePresentationState(
        rows = rows,
        lookups = buildCarouselRowLookups(rows)
    )
}

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val firstHeroPreviewByRow = LinkedHashMap<String, HeroPreview>(carouselRows.size)
    val fallbackBackdropByRow = LinkedHashMap<String, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        row.items.firstOrNull()?.heroPreview?.let { firstHeroPreviewByRow[row.key] = it }
        row.items.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
        }?.let { fallbackBackdropByRow[row.key] = it }
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.size)
        row.items.forEach { item ->
            itemKeys += item.key
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey,
        rowByKey = rowByKey,
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex,
        firstHeroPreviewByRow = firstHeroPreviewByRow,
        fallbackBackdropByRow = fallbackBackdropByRow,
        activeRowKeys = activeRowKeys,
        activeItemKeysByRow = activeItemKeysByRow,
        activeCatalogItemIds = activeCatalogItemIds
    )
}
