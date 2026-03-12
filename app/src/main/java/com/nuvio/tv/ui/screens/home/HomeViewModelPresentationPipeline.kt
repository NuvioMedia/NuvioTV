package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean
)

private data class FocusedBackdropPrefs(
    val expandEnabled: Boolean,
    val expandDelaySeconds: Int,
    val trailerEnabled: Boolean,
    val trailerMuted: Boolean,
    val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
)

private data class LayoutUiPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val focusedBackdropExpandEnabled: Boolean,
    val focusedBackdropExpandDelaySeconds: Int,
    val focusedBackdropTrailerEnabled: Boolean,
    val focusedBackdropTrailerMuted: Boolean,
    val focusedBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    val posterCardWidthDp: Int,
    val posterCardHeightDp: Int,
    val posterCardCornerRadiusDp: Int
)

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    val coreLayoutPrefsFlow = combine(
        combine(
            layoutPreferenceDataStore.selectedLayout,
            layoutPreferenceDataStore.heroCatalogSelections,
            layoutPreferenceDataStore.heroSectionEnabled,
            layoutPreferenceDataStore.posterLabelsEnabled,
            layoutPreferenceDataStore.catalogAddonNameEnabled
        ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
            CoreLayoutPrefs(
                layout = layout,
                heroCatalogKeys = heroCatalogKeys,
                heroSectionEnabled = heroSectionEnabled,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogAddonNameEnabled = catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = true,
                hideUnreleasedContent = false
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent
    ) { corePrefs, catalogTypeSuffixEnabled, hideUnreleasedContent ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            hideUnreleasedContent = hideUnreleasedContent
        )
    }

    val focusedBackdropPrefsFlow = combine(
        layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
    ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted, trailerPlaybackTarget ->
        FocusedBackdropPrefs(
            expandEnabled = expandEnabled,
            expandDelaySeconds = expandDelaySeconds,
            trailerEnabled = trailerEnabled,
            trailerMuted = trailerMuted,
            trailerPlaybackTarget = trailerPlaybackTarget
        )
    }

    val modernLayoutPrefsFlow = layoutPreferenceDataStore.modernLandscapePostersEnabled

    val baseLayoutUiPrefsFlow = combine(
        coreLayoutPrefsFlow,
        focusedBackdropPrefsFlow,
        layoutPreferenceDataStore.posterCardWidthDp,
        layoutPreferenceDataStore.posterCardHeightDp,
        layoutPreferenceDataStore.posterCardCornerRadiusDp
    ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
        LayoutUiPrefs(
            layout = corePrefs.layout,
            heroCatalogKeys = corePrefs.heroCatalogKeys,
            heroSectionEnabled = corePrefs.heroSectionEnabled,
            posterLabelsEnabled = corePrefs.posterLabelsEnabled,
            catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
            catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
            hideUnreleasedContent = corePrefs.hideUnreleasedContent,
            modernLandscapePostersEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
            focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
            focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
            focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
            posterCardWidthDp = posterCardWidthDp,
            posterCardHeightDp = posterCardHeightDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    viewModelScope.launch {
        combine(
            baseLayoutUiPrefsFlow,
            modernLayoutPrefsFlow
        ) { basePrefs, modernPrefs ->
            basePrefs.copy(
                modernLandscapePostersEnabled = modernPrefs
            )
        }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { prefs ->
                val effectivePosterLabelsEnabled = if (prefs.layout == HomeLayout.MODERN) {
                    false
                } else {
                    prefs.posterLabelsEnabled
                }
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    currentHeroCatalogKeys != prefs.heroCatalogKeys ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        focusedPosterBackdropTrailerPlaybackTarget = prefs.focusedBackdropTrailerPlaybackTarget,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    scheduleUpdateCatalogRows()
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .distinctUntilChanged()
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                if (!enabled) {
                    externalMetaPrefetchJob?.cancel()
                    pendingExternalMetaPrefetchItemId = null
                    externalMetaPrefetchInFlightIds.clear()
                }
            }
    }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = item.trailerYtIds.firstOrNull()
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null
) {
    if (startupGracePeriodActive) return
    if (activeTrailerPreviewItemId != itemId) {
        activeTrailerPreviewItemId = itemId
        trailerPreviewRequestVersion++
    }

    if (trailerPreviewNegativeCache.contains(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId)) return
    if (!trailerPreviewLoadingIds.add(itemId)) return

    val requestVersion = trailerPreviewRequestVersion

    viewModelScope.launch {
        val tmdbId = try {
            tmdbService.ensureTmdbId(itemId, apiType)
        } catch (_: Exception) {
            null
        }

        val trailerSource = trailerService.getTrailerPlaybackSource(
            title = title,
            year = extractYear(releaseInfo),
            tmdbId = tmdbId,
            type = apiType
        )

        val isLatestFocusedItem =
            activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
        if (!isLatestFocusedItem) {
            trailerPreviewLoadingIds.remove(itemId)
            return@launch
        }

        if (trailerSource?.videoUrl.isNullOrBlank()) {
            val fallbackSource = fallbackYtId?.let { ytId ->
                trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                    title = title,
                    year = extractYear(releaseInfo)
                )
            }
            if (fallbackSource?.videoUrl != null) {
                if (trailerPreviewUrlsState[itemId] != fallbackSource.videoUrl) {
                    trailerPreviewUrlsState[itemId] = fallbackSource.videoUrl
                }
                val fallbackAudio = fallbackSource.audioUrl
                if (fallbackAudio.isNullOrBlank()) {
                    trailerPreviewAudioUrlsState.remove(itemId)
                } else if (trailerPreviewAudioUrlsState[itemId] != fallbackAudio) {
                    trailerPreviewAudioUrlsState[itemId] = fallbackAudio
                }
            } else {
                trailerPreviewNegativeCache.add(itemId)
                trailerPreviewUrlsState.remove(itemId)
                trailerPreviewAudioUrlsState.remove(itemId)
            }
        } else {
            val videoUrl = trailerSource.videoUrl
            if (trailerPreviewUrlsState[itemId] != videoUrl) {
                trailerPreviewUrlsState[itemId] = videoUrl
            }
            val audioUrl = trailerSource.audioUrl
            if (audioUrl.isNullOrBlank()) {
                trailerPreviewAudioUrlsState.remove(itemId)
            } else if (trailerPreviewAudioUrlsState[itemId] != audioUrl) {
                trailerPreviewAudioUrlsState[itemId] = audioUrl
            }
        }

        trailerPreviewLoadingIds.remove(itemId)
    }
}

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id) return

    // Clear enriching for previous item immediately when focus moves away
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val willEnrich = currentTmdbSettings.enabled || externalMetaPrefetchEnabled
    if (willEnrich) setEnrichingItemId(item.id)

    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingTmdbEnrichItemId != item.id) {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.enabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val enrichment = if (tmdbId != null) runCatching {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = currentTmdbSettings.language
                    )
                }.getOrNull() else null
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
                    tmdbEnriched = true
                }
            }
            if (!tmdbEnriched && externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)) {
                try {
                    val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                        .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                    if (pendingTmdbEnrichItemId == item.id) pendingTmdbEnrichItemId = null
                }
            }
        } finally {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
        }
    }
}

internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return

    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (pendingAdjacentPrefetchItemId != item.id) return@launch
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return@launch

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.enabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val enrichment = if (tmdbId != null) runCatching {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = currentTmdbSettings.language
                    )
                }.getOrNull() else null
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
                    tmdbEnriched = true
                }
            }
            if (!tmdbEnriched &&
                externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                try {
                    val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                        .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                }
            }
        } finally {
            if (pendingAdjacentPrefetchItemId == item.id) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

private fun HomeViewModel.updateCatalogItemWithTmdb(itemId: String, enrichment: TmdbEnrichment) {
    fun mergeItem(currentItem: MetaPreview): MetaPreview {
        var merged = currentItem
        if (currentTmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = enrichment.localizedTitle ?: merged.name,
                description = enrichment.description ?: merged.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else merged.genres
            )
        }
        if (currentTmdbSettings.useArtwork) {
            merged = merged.copy(
                logo = enrichment.logo ?: merged.logo
            )
        }
        if (currentTmdbSettings.useDetails) {
            merged = merged.copy(
                ageRating = enrichment.ageRating ?: merged.ageRating,
                status = enrichment.status ?: merged.status
            )
        }
        return merged
    }

    catalogsMap.forEach { (key, row) ->
        val idx = row.items.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val merged = mergeItem(row.items[idx])
            if (merged != row.items[idx]) {
                val mutableItems = row.items.toMutableList()
                mutableItems[idx] = merged
                catalogsMap[key] = row.copy(items = mutableItems)
                truncatedRowCache.remove(key)
            }
        }
    }

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val mergedItem = mergeItem(row.items[idx])
                if (mergedItem == row.items[idx]) row
                else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[idx] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    val incomingTrailerYtIds = meta.trailerYtIds

    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = meta.backdropUrl ?: currentItem.backdropUrl,
        logo = meta.logo ?: currentItem.logo,
        description = meta.description ?: currentItem.description,
        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres,
        runtime = meta.runtime ?: currentItem.runtime,
        status = meta.status ?: currentItem.status,
        ageRating = meta.ageRating ?: currentItem.ageRating,
        language = meta.language ?: currentItem.language,
        country = meta.country ?: currentItem.country,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    catalogsMap.forEach { (key, row) ->
        val itemIndex = row.items.indexOfFirst { it.id == itemId }
        if (itemIndex >= 0) {
            val merged = mergeItem(row.items[itemIndex])
            if (merged != row.items[itemIndex]) {
                val mutableItems = row.items.toMutableList()
                mutableItems[itemIndex] = merged
                catalogsMap[key] = row.copy(items = mutableItems)
                truncatedRowCache.remove(key)
            }
        }
    }

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) {
                row
            } else {
                val mergedItem = mergeItem(row.items[itemIndex])
                if (mergedItem == row.items[itemIndex]) {
                    row
                } else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Covers: (a) item was in negative cache, (b) pipeline finished without result but wasn't
    // cached as negative (e.g. focus changed mid-flight), (c) pipeline still in-flight.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId)) {
        trailerPreviewNegativeCache.remove(itemId)
        trailerPreviewLoadingIds.remove(itemId)
        // Bump version so any in-flight pipeline for this item treats itself as stale
        // and won't overwrite the retry result with a negative cache entry.
        if (activeTrailerPreviewItemId == itemId) trailerPreviewRequestVersion++
        val currentItem = catalogsMap.values.firstNotNullOfOrNull { row ->
            row.items.firstOrNull { it.id == itemId }
        } ?: return
        requestTrailerPreviewPipeline(currentItem)
    }
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items

    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async item
                    val enrichment = tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = settings.language
                    ) ?: return@async item

                    var enriched = item

                    if (settings.useArtwork) {
                        enriched = enriched.copy(
                            background = enrichment.backdrop ?: enriched.background,
                            logo = enrichment.logo ?: enriched.logo,
                            poster = enrichment.poster ?: enriched.poster
                        )
                    }

                    if (settings.useBasicInfo) {
                        enriched = enriched.copy(
                            name = enrichment.localizedTitle ?: enriched.name,
                            description = enrichment.description ?: enriched.description,
                            genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                            imdbRating = enrichment.rating?.toFloat() ?: enriched.imdbRating
                        )
                    }

                    if (settings.useDetails) {
                        enriched = enriched.copy(
                            runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
                            releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo,
                            status = enrichment.status ?: enriched.status,
                            ageRating = enrichment.ageRating ?: enriched.ageRating,
                            country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                            language = enrichment.language ?: enriched.language
                        )
                    }

                    enriched
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                    item
                }
            }
        }.awaitAll()
    }
}

internal fun HomeViewModel.replaceGridHeroItemsPipeline(
    gridItems: List<GridItem>,
    heroItems: List<MetaPreview>
): List<GridItem> {
    if (gridItems.isEmpty()) return gridItems
    return gridItems.map { item ->
        if (item is GridItem.Hero) {
            item.copy(items = heroItems)
        } else {
            item
        }
    }
}

internal fun HomeViewModel.heroEnrichmentSignaturePipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): String {
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.backdropUrl}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(settings.enabled)
        append(':')
        append(settings.language)
        append(':')
        append(settings.useArtwork)
        append(':')
        append(settings.useBasicInfo)
        append(':')
        append(settings.useDetails)
        append("::")
        append(itemSignature)
    }
}
