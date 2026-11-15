package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROW_FOCUS_DEBOUNCE_MS = 250L

internal fun HomeViewModel.onFocusedRowChangedForMdbBatch(rowKey: String?) {
    if (rowKey == null) return
    if (!isMdbBatchPrefetchEnabled()) return

    val isFirst = !mdbBatchHasFired
    mdbBatchRowFocusJob?.cancel()
    mdbBatchRowFocusJob = viewModelScope.launch(Dispatchers.IO) {
        if (!isFirst) delay(ROW_FOCUS_DEBOUNCE_MS)
        mdbBatchHasFired = true
        batchFetchRatingsForRowAndNext(rowKey)
    }
}

internal fun HomeViewModel.onCatalogRowItemsChanged(rowKey: String) {
    if (!isMdbBatchPrefetchEnabled()) return

    viewModelScope.launch(Dispatchers.IO) {
        val row = readCatalogRow(rowKey) ?: return@launch
        val newItems = row.items.filter { it.mdbListRatings == null && it.id !in mdbBatchNegativeIds }
        if (newItems.isEmpty()) return@launch
        batchFetchRatingsForItems(newItems)
    }
}

private suspend fun HomeViewModel.batchFetchRatingsForRowAndNext(focusedRowKey: String) {
    if (focusedRowKey == MODERN_CONTINUE_WATCHING_ROW_KEY ||
        focusedRowKey == MODERN_UPCOMING_ROW_KEY
    ) return

    val allRows = _modernHomePresentation.value.rows.list
    val focusedIdx = allRows.indexOfFirst { it.key == focusedRowKey }
    if (focusedIdx < 0) return

    val targetRowKeys = mutableListOf(focusedRowKey)
    for (i in (focusedIdx + 1) until allRows.size) {
        val nextRow = allRows[i]
        if (nextRow.key != MODERN_CONTINUE_WATCHING_ROW_KEY &&
            nextRow.key != MODERN_UPCOMING_ROW_KEY
        ) {
            targetRowKeys.add(nextRow.key)
            break
        }
    }

    val itemsToFetch = mutableListOf<MetaPreview>()
    for (rk in targetRowKeys) {
        val carouselRow = allRows.firstOrNull { it.key == rk } ?: continue
        for (carouselItem in carouselRow.items.list) {
            val meta = carouselItem.metaPreview ?: continue
            if (meta.id in mdbBatchNegativeIds) continue
            val current = findCatalogItemById(meta.id)
            if (current?.mdbListRatings != null) continue
            itemsToFetch.add(current ?: meta)
        }
    }

    if (itemsToFetch.isEmpty()) return
    batchFetchRatingsForItems(itemsToFetch)
}

private suspend fun HomeViewModel.batchFetchRatingsForItems(items: List<MetaPreview>) {
    if (items.isEmpty()) return

    try {
        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val result = mdbListRepository.getRatingsForMeta(
                            meta = item.toMdbMeta(),
                            fallbackItemId = item.id,
                            fallbackItemType = item.apiType
                        )
                        if (result != null) {
                            updateCatalogItemMdbListRatings(item.id, result.ratings)
                        } else {
                            mdbBatchNegativeIds.add(item.id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { }
}

private fun HomeViewModel.isMdbBatchPrefetchEnabled(): Boolean {
    val settings = currentMdbListSettings
    return mdbListRepository.isAvailable(settings) && settings.showOnHero
}

private fun MetaPreview.toMdbMeta(): Meta = Meta(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    runtime = runtime,
    director = director,
    cast = emptyList(),
    videos = emptyList(),
    country = country,
    awards = null,
    language = language,
    links = links
)
