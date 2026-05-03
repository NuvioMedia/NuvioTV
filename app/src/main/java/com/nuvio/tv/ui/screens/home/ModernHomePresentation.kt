package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import java.util.Locale

@Immutable
internal data class ModernHomePresentationInput(
    val homeRows: List<HomeRow>,
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val showFullReleaseDate: Boolean,
    val localeTag: String
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null
): ModernHomePresentationState {
    val visibleHomeRows = resolveVisibleHomeRows(input)
    val localizedContext = getLocalizedContext(context)
    val strContinueWatching = localizedContext.getString(R.string.continue_watching)
    val strAirsDate = localizedContext.getString(R.string.cw_airs_date)
    val strUpcoming = localizedContext.getString(R.string.cw_upcoming)
    val strTypeMovie = localizedContext.getString(R.string.type_movie)
    val strTypeSeries = localizedContext.getString(R.string.type_series)

    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>()
        val activeCollectionKeys = LinkedHashSet<String>()
        val catalogRowLimit = maxCatalogRows?.coerceAtLeast(0)
        var renderedCatalogRows = 0

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
                    key = MODERN_CONTINUE_WATCHING_ROW_KEY,
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming,
                            context = localizedContext
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

        visibleHomeRows.forEachIndexed { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val row = homeRow.row
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val rowKey = catalogRowKey(row)
                    activeCatalogKeys += rowKey
                    val cached = cache.catalogRows[rowKey]
                    val currentLocaleTag = LocaleCache.localeTag
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == row &&
                            cached.useLandscapePosters == input.useLandscapePosters &&
                            cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                            cached.localeTag == currentLocaleTag

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
                        localeTag = currentLocaleTag,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.CollectionRow -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    val collection = homeRow.collection
                    val rowKey = collectionRowKey(collection)
                    activeCollectionKeys += rowKey
                    val cached = cache.collectionRows[rowKey]
                    val canReuseMappedRow =
                        cached != null &&
                            cached.source == collection

                    val mappedRow = if (canReuseMappedRow) {
                        val cachedMappedRow = checkNotNull(cached).mappedRow
                        if (cachedMappedRow.globalRowIndex == index) {
                            cachedMappedRow
                        } else {
                            cachedMappedRow.copy(globalRowIndex = index)
                        }
                    } else {
                        val occurrenceCounts = mutableMapOf<String, Int>()
                        HeroCarouselRow(
                            key = rowKey,
                            title = collection.title,
                            globalRowIndex = index,
                            items = collection.folders.map { folder ->
                                val occurrence = occurrenceCounts.getOrDefault(folder.id, 0)
                                occurrenceCounts[folder.id] = occurrence + 1
                                buildCollectionFolderItem(
                                    collection = collection,
                                    folder = folder,
                                    occurrence = occurrence
                                )
                            }
                        )
                    }

                    cache.collectionRows[rowKey] = ModernCollectionRowBuildCacheEntry(
                        source = collection,
                        mappedRow = mappedRow
                    )
                    add(mappedRow)
                }

                is HomeRow.PlaceholderCatalog -> {
                    if (catalogRowLimit != null && renderedCatalogRows >= catalogRowLimit) {
                        return@forEachIndexed
                    }
                    renderedCatalogRows++
                    val fakeItemCount = 8
                    val fakeItems = (0 until fakeItemCount).map { i ->
                        val fakeId = "__placeholder_${homeRow.catalogKey}_$i"
                        ModernCarouselItem(
                            key = "placeholder_${homeRow.catalogKey}_$i",
                            title = "",
                            subtitle = null,
                            // Dummy URL triggers shimmer instead of MonochromePosterPlaceholder
                            imageUrl = "placeholder://empty",
                            heroPreview = HeroPreview(
                                title = "", logo = null, description = null,
                                contentTypeText = null, yearText = null, imdbText = null,
                                genres = emptyList(), poster = null, backdrop = null,
                                imageUrl = "placeholder://empty"
                            ),
                            payload = ModernPayload.Catalog(
                                focusKey = "placeholder_${homeRow.catalogKey}_$i",
                                itemId = fakeId,
                                itemType = homeRow.apiType,
                                addonBaseUrl = homeRow.addonBaseUrl,
                                trailerTitle = "",
                                trailerReleaseInfo = null,
                                trailerApiType = homeRow.apiType
                            )
                        )
                    }
                    val placeholderRow = HeroCarouselRow(
                        key = homeRow.catalogKey,
                        title = homeRow.displayTitle,
                        globalRowIndex = index,
                        catalogId = homeRow.catalogId,
                        addonId = homeRow.addonId,
                        apiType = homeRow.apiType,
                        items = fakeItems,
                        isLoading = true
                    )
                    add(placeholderRow)
                }
            }
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        cache.collectionRows.keys.retainAll(activeCollectionKeys)
    }

    val lookups = buildCarouselRowLookups(rows)
    return ModernHomePresentationState(
        rows = rows,
        lookups = lookups
    )
}

private fun resolveVisibleHomeRows(input: ModernHomePresentationInput): List<HomeRow> {
    if (input.homeRows.isNotEmpty()) {
        val latestCatalogByKey = input.catalogRows.associateBy(::catalogRowKey)
        return input.homeRows.mapNotNull { homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val latest = latestCatalogByKey[catalogRowKey(homeRow.row)] ?: homeRow.row
                    latest.takeIf { it.items.isNotEmpty() }?.let(HomeRow::Catalog)
                }
                is HomeRow.CollectionRow -> {
                    homeRow.collection.takeIf(Collection::hasVisibleFolders)?.let(HomeRow::CollectionRow)
                }
                is HomeRow.PlaceholderCatalog -> {
                    // Keep placeholder rows as-is — they'll be rendered as shimmer skeletons
                    homeRow
                }
            }
        }
    }

    return input.catalogRows
        .filter { it.items.isNotEmpty() }
        .map(HomeRow::Catalog)
}

private fun collectionRowKey(collection: Collection): String {
    return "collection_${collection.id}"
}

private fun getLocalizedContext(context: Context): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context
    val locale = Locale.forLanguageTag(tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

private fun Collection.hasVisibleFolders(): Boolean {
    return folders.isNotEmpty()
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
