package com.nuvio.tv.ui.screens.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.toAddonQueryIds
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TvdbSettings
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val classicFocusGradientEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean
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
    val catalogTypeSuffixEnabled: Boolean,
    val classicFocusGradientEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val modernHeroFullScreenBackdropEnabled: Boolean,
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
            layoutPreferenceDataStore.posterLabelsEnabled
        ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled ->
            CoreLayoutPrefs(
                layout = layout,
                heroCatalogKeys = heroCatalogKeys,
                heroSectionEnabled = heroSectionEnabled,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogTypeSuffixEnabled = true,
                classicFocusGradientEnabled = false,
                hideUnreleasedContent = false,
                showFullReleaseDate = true
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent,
        layoutPreferenceDataStore.showFullReleaseDate,
        layoutPreferenceDataStore.classicFocusGradientEnabled
    ) { corePrefs, catalogTypeSuffixEnabled, hideUnreleasedContent, showFullReleaseDate, classicFocusGradientEnabled ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            classicFocusGradientEnabled = classicFocusGradientEnabled,
            hideUnreleasedContent = hideUnreleasedContent,
            showFullReleaseDate = showFullReleaseDate
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

    val modernLayoutPrefsFlow = combine(
        layoutPreferenceDataStore.modernLandscapePostersEnabled,
        layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled
    ) { landscapePosters, fullScreenBackdrop ->
        landscapePosters to fullScreenBackdrop
    }

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
            catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
            classicFocusGradientEnabled = corePrefs.classicFocusGradientEnabled,
            hideUnreleasedContent = corePrefs.hideUnreleasedContent,
            showFullReleaseDate = corePrefs.showFullReleaseDate,
            modernLandscapePostersEnabled = false,
            modernHeroFullScreenBackdropEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
            focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled &&
                AppFeaturePolicy.inAppTrailerPlaybackEnabled,
            focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
            focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
            posterCardWidthDp = posterCardWidthDp,
            posterCardHeightDp = posterCardHeightDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    // "Ritardo trailer": lo slider delle impostazioni decide quanto aspettare
    // prima di far partire l'anteprima in hero.
    viewModelScope.launch {
        trailerSettingsDataStore.settings
            .distinctUntilChanged()
            .collect { settings ->
                _uiState.update { it.copy(trailerPreviewDelaySeconds = settings.delaySeconds) }
            }
    }

    viewModelScope.launch {
        combine(
            baseLayoutUiPrefsFlow,
            modernLayoutPrefsFlow
        ) { basePrefs, modernPrefs ->
            basePrefs.copy(
                modernLandscapePostersEnabled = modernPrefs.first,
                modernHeroFullScreenBackdropEnabled = modernPrefs.second
            )
        }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { prefs ->
                val effectivePosterLabelsEnabled = prefs.posterLabelsEnabled
                val previousState = _uiState.value
                val heroKeysChanged = currentHeroCatalogKeys != prefs.heroCatalogKeys
                val shouldRefreshCatalogPresentation =
                    heroKeysChanged ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent ||
                        previousState.posterCardWidthDp != prefs.posterCardWidthDp
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                // Reset focus state when layout changes so the outgoing
                // layout's onDispose doesn't poison the incoming layout
                // (e.g., Modern dispose saves hasSavedFocus=true right
                // before Classic composes, preventing hero initial focus).
                if (previousState.layoutPreferencesReady && previousState.homeLayout != prefs.layout) {
                    // Suppress the outgoing layout's onDispose from saving
                    // stale focus state before the incoming layout composes.
                    suppressFocusSave = true
                    clearFocusState()
                }
                _uiState.update {
                    it.copy(
                        layoutPreferencesReady = true,
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        classicFocusGradientEnabled = prefs.classicFocusGradientEnabled && prefs.layout == HomeLayout.CLASSIC,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        showFullReleaseDate = prefs.showFullReleaseDate,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        modernHeroFullScreenBackdropEnabled = prefs.modernHeroFullScreenBackdropEnabled,
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
                    // When switching to GRID layout, load all pending lazy catalogs
                    // since grid doesn't support placeholder shimmer rows.
                    if (prefs.layout == HomeLayout.GRID) {
                        loadAllPendingLazyCatalogs()
                    }
                    // When hero catalog keys change, load any hero catalogs
                    // not yet in catalogsMap (e.g., after startup race or
                    // when user changes hero selection in settings).
                    if (heroKeysChanged && prefs.heroCatalogKeys.isNotEmpty()) {
                        loadHeroCatalogsPipeline()
                    } else {
                        scheduleUpdateCatalogRows()
                    }
                }
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        combine(uiState, _currentLocaleTag) { state, localeTag ->
                ModernHomePresentationInput(
                    homeRows = state.homeRows,
                    catalogRows = state.catalogRows,
                    continueWatchingItems = state.continueWatchingItems,
                    upcomingItems = state.upcomingItems,
                    useLandscapePosters = state.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                    showFullReleaseDate = state.showFullReleaseDate,
                    localeTag = localeTag
                )
            }
            // Compare by row structure only (keys + item counts), not by
            // item content.  TMDB/meta enrichment changes item fields but
            // not the row structure — the hero section reads enriched data
            // via lastEnrichedPreview instead.
            .distinctUntilChanged { old, new ->
                old.homeRows === new.homeRows
                    && old.continueWatchingItems == new.continueWatchingItems
                    && old.upcomingItems == new.upcomingItems
                    && old.useLandscapePosters == new.useLandscapePosters
                    && old.showCatalogTypeSuffix == new.showCatalogTypeSuffix
                    && old.showFullReleaseDate == new.showFullReleaseDate
                    && old.localeTag == new.localeTag
                    && old.catalogRows.size == new.catalogRows.size
            }
            .debounce {
                // Use a longer debounce while catalogs are still loading to
                // avoid repeated expensive presentation builds during the
                // initial burst of catalog arrivals.
                if (catalogsLoadInProgress) 300L else 80L
            }
            .collectLatest { input ->
                val shouldWarmStart = uiState.value.modernHomePresentation.rows.list.isEmpty()
                val visibleCatalogRowCount = input.catalogRows.count { it.items.isNotEmpty() }
                val warmStartCatalogRowCount = if (input.continueWatchingItems.isNotEmpty()) 2 else 3

                if (shouldWarmStart && visibleCatalogRowCount > warmStartCatalogRowCount) {
                    val warmStartPresentation = withContext(Dispatchers.Default) {
                        buildModernHomePresentation(
                            input = input,
                            cache = modernCarouselRowBuildCache,
                            context = appContext,
                            maxCatalogRows = warmStartCatalogRowCount
                        )
                    }
                    _uiState.update { state ->
                        if (state.modernHomePresentation == warmStartPresentation) {
                            state
                        } else {
                            state.copy(modernHomePresentation = warmStartPresentation)
                        }
                    }
                }

                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache,
                        context = appContext
                    )
                }
                _uiState.update { state ->
                    if (state.modernHomePresentation == presentation) {
                        state
                    } else {
                        state.copy(modernHomePresentation = presentation)
                    }
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
    if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
    if (startupGracePeriodActive) return

    // Resolve fallbackYtId from catalog item if not provided
    val resolvedFallbackYtId = fallbackYtId ?: findCatalogItemById(itemId)?.trailerYtIds?.firstOrNull()

    // Always bump version — only the latest request (highest version) will proceed after debounce
    activeTrailerPreviewItemId = itemId
    trailerPreviewRequestVersion++
    val requestVersion = trailerPreviewRequestVersion

    if (trailerPreviewNegativeCache.contains(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId)) return
    if (!trailerPreviewLoadingIds.add(itemId)) return

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Debounce: wait for focus to settle before hitting network
            delay(180)

            // Only the LATEST request proceeds — all earlier ones are stale
            if (trailerPreviewRequestVersion != requestVersion) {
                return@launch
            }

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

            withContext(Dispatchers.Main) {
                if (trailerSource?.videoUrl.isNullOrBlank()) {
                    val fallbackSource = resolvedFallbackYtId?.let { ytId ->
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
                        // Only remember a miss when we actually had something to
                        // resolve: a TMDB id that failed to resolve (rate limit,
                        // cold start) is transient and must be retried on focus.
                        if (tmdbId != null || resolvedFallbackYtId != null) {
                            trailerPreviewNegativeCache.add(itemId)
                        }
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
            }
        } finally {
            trailerPreviewLoadingIds.remove(itemId)
        }
    }
}

// Attesa prima di partire con un prefetch: lascia che sia il focus reale a
// guidare la richiesta quando l'utente sta già navigando.
private const val TRAILER_PREFETCH_DEBOUNCE_MS = 400L

/**
 * Prefetch del trailer per i titoli in focus o vicini al focus: risolve l'URL mentre
 * il focus è ancora da qualche altra parte, così quando la card diventa infocus il
 * video parte subito invece di aspettare l'estrazione YouTube.
 *
 * A differenza del percorso focalizzato qui non c'è il cancello di versione: ogni
 * voce vive per conto proprio e finisce negli stessi cache (URL / negative / in
 * flight, quest'ultimo che impedisce i doppi lavori col percorso focalizzato).
 * Un fallimento non finisce nella negative cache: al momento del focus decide il
 * percorso rete, qui non si vogliono rovinare retry transitori.
 */
internal fun HomeViewModel.preloadTrailerPreviewPipeline(item: MetaPreview) {
    if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
    if (startupGracePeriodActive) return
    val prefs = _uiState.value
    if (!prefs.focusedPosterBackdropTrailerEnabled) return
    if (prefs.focusedPosterBackdropTrailerPlaybackTarget != FocusedPosterTrailerPlaybackTarget.HERO_MEDIA) return
    if (trailerPreviewUrlsState.containsKey(item.id)) return
    if (trailerPreviewNegativeCache.contains(item.id)) return
    if (!trailerPreviewLoadingIds.add(item.id)) return

    viewModelScope.launch(Dispatchers.IO) {
        try {
            delay(TRAILER_PREFETCH_DEBOUNCE_MS)
            val tmdbId = try {
                tmdbService.ensureTmdbId(item.id, item.apiType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val source = trailerService.getTrailerPlaybackSource(
                title = item.name,
                year = extractYear(item.releaseInfo),
                tmdbId = tmdbId,
                type = item.apiType
            )
            val videoUrl = source?.videoUrl
            if (!videoUrl.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    if (trailerPreviewUrlsState[item.id] != videoUrl) {
                        trailerPreviewUrlsState[item.id] = videoUrl
                    }
                    val audioUrl = source?.audioUrl
                    if (audioUrl.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(item.id)
                    } else if (trailerPreviewAudioUrlsState[item.id] != audioUrl) {
                        trailerPreviewAudioUrlsState[item.id] = audioUrl
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(HomeViewModel.TAG, "Trailer prefetch failed for ${item.id}: ${e.message}")
        } finally {
            trailerPreviewLoadingIds.remove(item.id)
        }
    }
}

/**
 * What an external meta prefetch produced. A failed fetch must be distinguishable from an addon
 * that answered with nothing to add: the first is retried on the next focus, the second is not.
 * Both used to collapse to null, so one unreachable addon suppressed enrichment for the session.
 *
 * Callers own the in-flight id: they claim it before launching and release it on completion.
 */
private sealed interface ExternalMetaOutcome {
    data class Resolved(val meta: Meta) : ExternalMetaOutcome
    /**
     * The addons answered and there is nothing more to fetch: either the catalog item is already
     * sufficient, or no addon carries this item. Both are final, so neither is retried.
     */
    object Final : ExternalMetaOutcome
    object Failed : ExternalMetaOutcome
}

/**
 * Whether an external meta fetch is still outstanding. A TMDB success must not stand in for one
 * that has not resolved, and both gates ask this rather than keeping their own copy of the rule.
 */
private fun HomeViewModel.externalEnrichmentOutstanding(itemId: String): Boolean =
    externalMetaPrefetchEnabled && itemId !in prefetchedExternalMetaIds

private suspend fun HomeViewModel.fetchExternalMetaOutcome(item: MetaPreview): ExternalMetaOutcome =
    try {
        val (queryType, queryId) = toAddonQueryIds(item.id, item.apiType) ?: (item.apiType to item.id)
        val result = metaRepository.getMetaFromAllAddons(queryType, queryId, item.sourceAddonBaseUrl)
            .first { it is NetworkResult.Success || it is NetworkResult.Error }
        when {
            result is NetworkResult.Success -> ExternalMetaOutcome.Resolved(result.data)
            result is NetworkResult.Error &&
                (result.code == NetworkResult.SOURCE_SUFFICIENT_CODE ||
                    result.code == NetworkResult.META_NOT_FOUND_CODE) ->
                ExternalMetaOutcome.Final
            else -> ExternalMetaOutcome.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(HomeViewModel.TAG, "External meta fetch threw for ${item.id}: ${e.message}")
        ExternalMetaOutcome.Failed
    }

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) {
        deferredEnrichItem = item
        return
    }
    maybeFetchMdbListRatingForItem(item)
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
        // Only external enrichment re-opens this gate, so a cached external result still shuts out
        // an unresolved TMDB fetch. That is unchanged from before and deliberate: TMDB is never
        // marked prefetched when an item has no TMDB match, so gating on it too would re-enter on
        // every focus and call ensureTmdbId each time. Fixing it needs a terminal marker for TMDB
        // first. The fetches below are gated per source, so re-entering issues only the external
        // request.
        if (!externalEnrichmentOutstanding(item.id)) {
            // Ensure enrichedPreviews contains this item so the UI can display
            // hero data immediately (e.g. when adjacent prefetch resolved it
            // before the user focused on it).
            val existing = _enrichedPreviews.value[item.id]
            if (existing == null) {
                val enriched = findCatalogItemById(item.id) ?: item
                addEnrichedPreview(item.id, enriched)
            } else {
                // A first translation attempt can fail transiently (ML Kit model
                // still downloading, detection timeout) and leave the raw English
                // description in the map: without re-arming here, no later focus
                // would ever retry it and the hero stayed in English forever.
                schedulePreviewDescriptionTranslation(item.id, existing)
            }
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            // Un arricchimento rimasto appeso a un altro item è ormai stale: questo
            // focus ha preso il ramo breve, per cui il cancel del percorso normale
            // (più sotto) non è mai partito. Rilasciarlo qui, o una fetch bloccata
            // tiene aperto il gate della hero (e blocca il self-heal del titolo)
            // per tutti i focus successivi.
            if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
                tmdbEnrichFocusJob?.cancel()
                setEnrichingItemId(null)
                if (pendingTmdbEnrichItemId != null && pendingTmdbEnrichItemId != item.id) {
                    pendingTmdbEnrichItemId = null
                }
            }
            // Still prefetch full meta in background for instant detail screen.
            prefetchBackgroundMetaForItem(item)
            return
        }
    }
    // Un job cancellato durante il debounce (prima del try/finally) lascia
    // `pendingTmdbEnrichItemId` appeso: senza questo controllo ogni focus
    // futuro su questo item tornerebbe qui in silenzio e l'arricchimento
    // (e quindi la traduzione della descrizione) non ripartirebbe mai.
    if (pendingTmdbEnrichItemId == item.id) {
        if (tmdbEnrichFocusJob?.isActive == true) return
        pendingTmdbEnrichItemId = null
    }

    // Clear enriching for previous item immediately when focus moves away
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val tmdbEnabledForCurrentLayout = currentTmdbSettings.enabled &&
        (_uiState.value.homeLayout != HomeLayout.MODERN || currentTmdbSettings.modernHomeEnabled)
    val tvdbEnabledForCurrentLayout = currentTvdbSettings.enabled && currentTvdbSettings.hasApiKey &&
        item.apiType in listOf("series", "tv")
    val willEnrich = tmdbEnabledForCurrentLayout || tvdbEnabledForCurrentLayout || externalMetaPrefetchEnabled

    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingTmdbEnrichItemId != item.id) {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }
        // Traduzione subito, in parallelo con le fetch di rete: l'hero mostra
        // l'item focused e la sua descrizione va italiana appena pronta, invece
        // di aspettare TMDB/OMDb più la traduzione in serie (era il ritardo
        // percepito "la traduzione arriva in ritardo" durante lo scroll).
        // Pubblicare l'entry solo quando non esiste: sovrascrivere un entry
        // già arricchito e tradotto col testo grezzo rimetterebbe l'inglese.
        val existingPreview = _enrichedPreviews.value[item.id]
        if (existingPreview == null) {
            addEnrichedPreview(item.id, item, priority = true)
        } else {
            schedulePreviewDescriptionTranslation(item.id, existingPreview, priority = true)
        }
        if (willEnrich) {
            setEnrichingItemId(item.id)
            android.util.Log.i("HomePipeline", "enrich start item=${item.id}")
        }
        val tvdbAlreadyDone = !tvdbEnabledForCurrentLayout || item.id in prefetchedTvdbIds
        if ((item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) && tvdbAlreadyDone) {
            if (!externalEnrichmentOutstanding(item.id)) {
                if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
                // Publish what the earlier prefetch already merged (or the raw
                // catalog item): this path used to return without publishing
                // anything, so the hero kept the addon's English description and
                // the translation never got scheduled for it.
                val existing = _enrichedPreviews.value[item.id]
                if (existing == null) {
                    addEnrichedPreview(item.id, findCatalogItemById(item.id) ?: item)
                } else {
                    schedulePreviewDescriptionTranslation(item.id, existing)
                }
                // Still prefetch full meta in background for instant detail screen.
                prefetchBackgroundMetaForItem(item)
                return@launch
            }
        }

        try {
            // Launch TMDB and external meta addon fetch in parallel.
            // Which sources are used depends on settings:
            // - tmdbEnabledForCurrentLayout: controls TMDB enrichment
            // - externalMetaPrefetchEnabled: controls external meta addon fetch
            val tmdbDeferred = if (tmdbEnabledForCurrentLayout && item.id !in prefetchedTmdbIds) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                if (tmdbId != null) async {
                    runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = currentTmdbSettings.language
                        )
                    }.getOrNull()
                } else null
            } else null

            val externalMetaDeferred = if (externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                // The id is claimed here, in the enclosing coroutine, but released when the
                // deferred completes rather than inside its body. A focus that moves on during
                // the debounce cancels this job before the body runs, and a body that never runs
                // never reaches its own finally, which would strand the id in the in-flight set
                // and block every later fetch for that item.
                async { fetchExternalMetaOutcome(item) }
                    .also { d -> d.invokeOnCompletion { externalMetaPrefetchInFlightIds.remove(item.id) } }
            } else null

            // Await both results
            val tmdbEnrichment = tmdbDeferred?.await()
            val externalOutcome = externalMetaDeferred?.await()
            val externalMeta = (externalOutcome as? ExternalMetaOutcome.Resolved)?.meta

            // Mark as prefetched
            if (tmdbEnrichment != null) prefetchedTmdbIds.add(item.id)
            if (externalOutcome != null && externalOutcome != ExternalMetaOutcome.Failed) {
                prefetchedExternalMetaIds.add(item.id)
            }

            // TVDB only fills blanks after the parallel TMDB+external merge, and only when active.
            if (tvdbEnabledForCurrentLayout && item.id !in prefetchedTvdbIds) {
                val tvdbEnrichment = runCatching {
                    tvdbMetadataService.enrichPreview(
                        itemId = item.id,
                        name = item.name,
                        apiType = item.apiType,
                        settings = currentTvdbSettings
                    )
                }.getOrNull()
                prefetchedTvdbIds.add(item.id)
                if (tvdbEnrichment != null) {
                    updateCatalogItemWithTvdb(item.id, tvdbEnrichment)
                }
            }

            // Merge results: apply external meta first (base layer), then TMDB on top
            // respecting which TMDB settings are enabled.
            if (externalMeta != null) {
                updateCatalogItemWithMeta(item.id, externalMeta)
            }
            if (tmdbEnrichment != null) {
                updateCatalogItemWithTmdb(item.id, tmdbEnrichment)
            }

            // If neither source produced anything, mark enrichment in previews
            // so UI doesn't keep showing spinner. Skip titles a merge already reached: a row
            // loaded after that merge carries raw data, and publishing it would downgrade them.
            if (tmdbEnrichment == null && externalMeta == null && item.id !in enrichmentMergedIds) {
                val preview = findCatalogItemById(item.id) ?: item
                if (_enrichedPreviews.value[item.id] != preview) addEnrichedPreview(item.id, preview)
            }

            // Always prefetch full meta in background for instant detail screen loading.
            prefetchBackgroundMetaForItem(item)

            // Warm up watch progress pipeline so detail screen reads are fast.
            viewModelScope.launch {
                watchProgressRepository.getAllEpisodeProgress(item.id.substringBefore(":")).first()
            }

        } finally {
            android.util.Log.i("HomePipeline", "enrich end item=${item.id}")
            // Release the claim only if it is still ours: a later focus may have claimed another item.
            if (pendingTmdbEnrichItemId == item.id) pendingTmdbEnrichItemId = null
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
                // If enrichment completed but no enriched data exists for this item,
                // mark it as failed so the UI can show addon data immediately.
                if (item.id !in _enrichedPreviews.value &&
                    item.id !in prefetchedExternalMetaIds &&
                    item.id !in prefetchedTmdbIds) {
                    markEnrichmentFailed(item.id)
                }
            }
        }
    }
}

/**
 * Shares the fetch with the focused path but deliberately not its gate: an item whose TMDB fetch
 * succeeded is skipped here even when external metadata is outstanding. Adjacent prefetch is
 * opportunistic and the focused path retries anyway, so aligning the two would only add requests.
 */
internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    val tvdbEnabled = currentTvdbSettings.enabled && currentTvdbSettings.hasApiKey &&
        item.apiType in listOf("series", "tv")
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds ||
        (tvdbEnabled && item.id in prefetchedTvdbIds)
    ) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return

    // Il trailer va preparato in anticipo: passando di lì il focus trova già
    // l'URL e il video parte senza estrazioni a schermo.
    preloadTrailerPreviewPipeline(item)

    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
        val tmdbEnabledForCurrentLayout = currentTmdbSettings.enabled &&
            (_uiState.value.homeLayout != HomeLayout.MODERN || currentTmdbSettings.modernHomeEnabled)
        val tvdbEnabledForCurrentLayout = currentTvdbSettings.enabled && currentTvdbSettings.hasApiKey &&
            item.apiType in listOf("series", "tv")
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (pendingAdjacentPrefetchItemId != item.id) return@launch

        val tvdbAlreadyDone = !tvdbEnabledForCurrentLayout || item.id in prefetchedTvdbIds
        if ((item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) && tvdbAlreadyDone) return@launch

        try {
            // Launch TMDB and external meta addon fetch in parallel (same as focused pipeline).
            val tmdbDeferred = if (tmdbEnabledForCurrentLayout) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                if (tmdbId != null) async {
                    runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = currentTmdbSettings.language
                        )
                    }.getOrNull()
                } else null
            } else null

            val externalMetaDeferred = if (externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                // The id is claimed here, in the enclosing coroutine, but released when the
                // deferred completes rather than inside its body. A focus that moves on during
                // the debounce cancels this job before the body runs, and a body that never runs
                // never reaches its own finally, which would strand the id in the in-flight set
                // and block every later fetch for that item.
                async { fetchExternalMetaOutcome(item) }
                    .also { d -> d.invokeOnCompletion { externalMetaPrefetchInFlightIds.remove(item.id) } }
            } else null

            val tmdbEnrichment = tmdbDeferred?.await()
            val externalOutcome = externalMetaDeferred?.await()
            val externalMeta = (externalOutcome as? ExternalMetaOutcome.Resolved)?.meta

            if (tmdbEnrichment != null) prefetchedTmdbIds.add(item.id)
            if (externalOutcome != null && externalOutcome != ExternalMetaOutcome.Failed) {
                prefetchedExternalMetaIds.add(item.id)
            }

            // TVDB only fills blanks after the parallel TMDB+external merge, and only when active.
            if (tvdbEnabledForCurrentLayout && item.id !in prefetchedTvdbIds) {
                val tvdbEnrichment = runCatching {
                    tvdbMetadataService.enrichPreview(
                        itemId = item.id,
                        name = item.name,
                        apiType = item.apiType,
                        settings = currentTvdbSettings
                    )
                }.getOrNull()
                prefetchedTvdbIds.add(item.id)
                if (tvdbEnrichment != null) {
                    updateCatalogItemWithTvdb(item.id, tvdbEnrichment)
                }
            }

            if (externalMeta != null) {
                updateCatalogItemWithMeta(item.id, externalMeta)
            }
            if (tmdbEnrichment != null) {
                updateCatalogItemWithTmdb(item.id, tmdbEnrichment)
            }

            if (tmdbEnrichment == null && externalMeta == null && item.id !in enrichmentMergedIds) {
                addEnrichedPreview(item.id, item)
            }

            // Background prefetch for detail screen cache.
            prefetchBackgroundMetaForItem(item)

        } finally {
            if (pendingAdjacentPrefetchItemId == item.id) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

internal fun HomeViewModel.maybeFetchMdbListRatingForItem(item: MetaPreview) {
    val mdbEnabled = currentMdbListSettings.enabled && currentMdbListSettings.apiKey.isNotBlank()
    if (!mdbEnabled) return
    if (item.id in mdbListRatingFetchedIds) return

    pendingMdbListRatingItemId = item.id
    mdbListRatingFocusJob?.cancel()
    mdbListRatingFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingMdbListRatingItemId != item.id) return@launch
        if (item.id in mdbListRatingFetchedIds) return@launch
        val rating = runCatching { mdbListRepository.getImdbRatingForItem(item.id, item.apiType) }.getOrNull()
        if (rating != null) {
            updateCatalogItemImdbRating(item.id, rating.toFloat())
            mdbListRatingFetchedIds.add(item.id)
        }
    }
}

/**
 * Applies enrichment to the collections consumed by the non-modern layouts.
 *
 * [transform] must be pure and idempotent: _uiState.update can retry, and the item is merged in
 * each collection independently.
 */
private fun HomeViewModel.applyEnrichmentToDisplayedRows(
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
) {
    _uiState.update { state ->
        if (state.homeLayout == HomeLayout.MODERN) return@update state
        var changed = false

        fun patch(row: com.nuvio.tv.domain.model.CatalogRow): com.nuvio.tv.domain.model.CatalogRow {
            val index = row.items.indexOfFirst { it.id == itemId }
            if (index < 0) return row
            val merged = transform(row.items[index])
            if (merged == row.items[index]) return row
            changed = true
            return row.copy(items = row.items.toMutableList().apply { set(index, merged) })
        }

        val updatedCatalogRows = state.catalogRows.map(::patch)
        val updatedHomeRows = state.homeRows.map { homeRow ->
            if (homeRow is HomeRow.Catalog) {
                val patched = patch(homeRow.row)
                if (patched === homeRow.row) homeRow else HomeRow.Catalog(patched)
            } else {
                homeRow
            }
        }
        val updatedGridItems = state.gridItems.map { gridItem ->
            if (gridItem is GridItem.Content && gridItem.item.id == itemId) {
                val merged = transform(gridItem.item)
                if (merged == gridItem.item) {
                    gridItem
                } else {
                    changed = true
                    gridItem.copy(item = merged)
                }
            } else {
                gridItem
            }
        }

        if (changed) {
            state.copy(
                catalogRows = updatedCatalogRows,
                homeRows = updatedHomeRows,
                gridItems = updatedGridItems
            )
        } else {
            state
        }
    }
}

private fun HomeViewModel.updateCatalogItemWithTmdb(itemId: String, enrichment: TmdbEnrichment) {
    enrichmentMergedIds.add(itemId)
    val isModernLayout = _uiState.value.homeLayout == HomeLayout.MODERN
    fun mergeItem(currentItem: MetaPreview): MetaPreview {
        var merged = currentItem
        if (currentTmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = if (isModernLayout) enrichment.localizedTitle ?: merged.name else merged.name,
                description = enrichment.description ?: merged.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else merged.genres
            )
        }
        if (currentTmdbSettings.useArtwork) {
            merged = merged.copy(
                background = enrichment.backdrop ?: merged.background,
                logo = enrichment.logo ?: merged.logo
            )
        }
        if (currentTmdbSettings.useDetails) {
            merged = merged.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: merged.runtime,
                ageRating = enrichment.ageRating ?: merged.ageRating,
                status = enrichment.status ?: merged.status
            )
        }
        if (currentTmdbSettings.useReleaseDates) {
            merged = merged.copy(
                releaseInfo = enrichment.releaseInfo ?: merged.releaseInfo
            )
        }
        return merged
    }

    updateIndexedCatalogItem(itemId, ::mergeItem)
    clearEnrichmentFailure(itemId)

    applyEnrichmentToDisplayedRows(itemId, ::mergeItem)

    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

private fun HomeViewModel.updateCatalogItemWithTvdb(
    itemId: String,
    enrichment: TvdbMetadataService.PreviewEnrichment
) {
    val isModernLayout = _uiState.value.homeLayout == HomeLayout.MODERN
    fun mergeItem(currentItem: MetaPreview): MetaPreview {
        var merged = currentItem
        if (currentTvdbSettings.useBasicInfo && merged.description.isNullOrBlank() &&
            !enrichment.description.isNullOrBlank()
        ) {
            merged = merged.copy(description = enrichment.description)
        }
        if (currentTvdbSettings.useArtwork && merged.background.isNullOrBlank() &&
            !enrichment.background.isNullOrBlank()
        ) {
            merged = merged.copy(background = enrichment.background)
        }
        return merged
    }

    updateIndexedCatalogItem(itemId, ::mergeItem)

    if (!isModernLayout) {
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

    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

private fun HomeViewModel.updateCatalogItemImdbRating(itemId: String, rating: Float) {
    updateIndexedCatalogItem(itemId) { currentItem ->
        currentItem.copy(imdbRating = rating)
    }
    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val updated = row.items[idx].copy(imdbRating = rating)
                if (updated == row.items[idx]) row
                else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[idx] = updated
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
    // The modern layout reads enrichment via enrichedPreviews / lastEnrichedPreview,
    // so keep them in sync when the MDBList rating arrives.
    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

private fun HomeViewModel.prefetchBackgroundMetaForItem(item: MetaPreview) {
    // Awards must not wait on the addon meta round trip: publish them as soon
    // as OMDb answers, independently of the once-per-session meta gate below.
    prefetchItemAwards(item)
    if (item.id in backgroundMetaPrefetchedIds) return
    backgroundMetaPrefetchedIds.add(item.id)
    viewModelScope.launch(Dispatchers.IO) {
        // Start the OMDb lookup from the catalog preview ids right away so the
        // hero awards don't wait for two serial round trips (addon meta, then OMDb).
        // The repository cache and in-flight dedup make the later meta-based
        // fallback free when both paths resolve the same title.
        val earlyAwards = async {
            if (findCatalogItemById(item.id)?.awards.isNullOrBlank()) {
                runCatching {
                    omdbAwardsRepository.getAwards(
                        imdbId = item.imdbId,
                        primaryId = item.id,
                        apiType = item.apiType,
                        fallbackItemId = item.id,
                        fallbackItemType = item.apiType
                    )
                }.getOrNull()
            } else {
                null
            }
        }
        val result = metaRepository.getMetaFromAllAddons(
            type = item.apiType,
            id = item.id
        ).first { it !is NetworkResult.Loading }
        resolveAndPublishItemAwards(item, result, earlyAwards)
    }
}

/**
 * Publishes OMDb awards for the hero as soon as they resolve, independently
 * of the addon meta round trip: on slow devices that trip can take a minute
 * and the hero awards line would otherwise never appear in the meantime.
 * Re-runs on every focus while awards are still blank — the repository
 * memory/disk caches answer every repeat instantly, and negative results are
 * cached too, so a failed first attempt is retried without extra traffic.
 */
private fun HomeViewModel.prefetchItemAwards(item: MetaPreview) {
    if (!findCatalogItemById(item.id).let { it == null || it.awards.isNullOrBlank() }) return
    viewModelScope.launch(Dispatchers.IO) {
        val awards = runCatching {
            omdbAwardsRepository.getAwards(
                imdbId = item.imdbId,
                primaryId = item.id,
                apiType = item.apiType,
                fallbackItemId = item.id,
                fallbackItemType = item.apiType
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
        // Re-check right before publishing: the meta path may have landed
        // addon awards while OMDb was in flight (addon awards win).
        if (findCatalogItemById(item.id)?.awards.isNullOrBlank()) {
            publishItemAwards(item.id, awards)
        }
    }
}

private suspend fun HomeViewModel.resolveAndPublishItemAwards(
    item: MetaPreview,
    result: NetworkResult<Meta>,
    earlyAwards: Deferred<String?>
) {
    val meta = (result as? NetworkResult.Success<*>)?.data as? Meta
    meta?.awards?.takeIf { it.isNotBlank() }?.let { addonAwards ->
        publishItemAwards(item.id, addonAwards)
        return
    }
    if (!findCatalogItemById(item.id)?.awards.isNullOrBlank()) return
    val omdbAwards = earlyAwards.await()?.takeIf { it.isNotBlank() }
        ?: meta?.let { resolvedMeta ->
            runCatching {
                omdbAwardsRepository.getAwards(resolvedMeta, item.id, item.apiType)
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        ?: return
    publishItemAwards(item.id, omdbAwards)
}

private fun HomeViewModel.publishItemAwards(itemId: String, awards: String) {
    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(awards = awards)

    updateIndexedCatalogItem(itemId, ::mergeItem)
    applyEnrichmentToDisplayedRows(itemId, ::mergeItem)
    val enriched = findCatalogItemById(itemId)
    if (enriched != null) {
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    } else {
        // Items outside the catalog index (e.g. continue-watching entries)
        // still reach the modern hero through enrichedPreviews — publish into
        // the existing overlay instead of dropping the awards.
        _enrichedPreviews.value[itemId]?.let { existing ->
            addEnrichedPreview(itemId, mergeItem(existing))
        }
    }
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    enrichmentMergedIds.add(itemId)
    val incomingTrailerYtIds = meta.trailerYtIds
    val seasonCount = meta.videos
        .asSequence()
        .mapNotNull { it.season }
        .filter { it > 0 }
        .distinct()
        .count()
        .takeIf { it > 0 }

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
        seasonCount = seasonCount ?: currentItem.seasonCount,
        awards = meta.awards?.takeIf { it.isNotBlank() } ?: currentItem.awards,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)
    clearEnrichmentFailure(itemId)

    applyEnrichmentToDisplayedRows(itemId, ::mergeItem)
    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Only retry if this item is currently focused — avoid prefetching trailers for adjacent items.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId) && activeTrailerPreviewItemId == itemId) {
        trailerPreviewNegativeCache.remove(itemId)
        trailerPreviewLoadingIds.remove(itemId)
        // Bump version so any in-flight pipeline for this item treats itself as stale
        // and won't overwrite the retry result with a negative cache entry.
        trailerPreviewRequestVersion++
        val currentItem = findCatalogItemById(itemId) ?: return
        requestTrailerPreviewPipeline(currentItem)
    }
}

private fun HomeViewModel.updateCatalogItemArtworkOnly(itemId: String, meta: Meta) {
    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = meta.backdropUrl ?: currentItem.backdropUrl,
        logo = meta.logo ?: currentItem.logo
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)

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
    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items
    val mdbSettings = currentMdbListSettings
    val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()
    val tvdbSettings = currentTvdbSettings
    val tvdbEnabled = tvdbSettings.enabled && tvdbSettings.hasApiKey

    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val tmdbDeferred = if (settings.enabled) async {
                        val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async null
                        tmdbId.toIntOrNull()?.let { numericId ->
                            runCatching { tmdbService.tmdbToImdb(numericId, item.apiType) }
                        }
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = settings.language
                        )
                    } else null
                    val mdbDeferred = if (mdbEnabled) async {
                        runCatching { mdbListRepository.getImdbRatingForItem(item.id, item.apiType) }.getOrNull()
                    } else null
                    val tvdbDeferred = if (tvdbEnabled && item.apiType in listOf("series", "tv")) async {
                        runCatching {
                            tvdbMetadataService.enrichPreview(
                                itemId = item.id,
                                name = item.name,
                                apiType = item.apiType,
                                settings = tvdbSettings
                            )
                        }.getOrNull()
                    } else null

                    val enrichment = tmdbDeferred?.await()
                    val mdbImdbRating = mdbDeferred?.await()
                    val tvdbEnrichment = tvdbDeferred?.await()

                    var enriched = item

                    if (enrichment != null) {
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
                                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres
                            )
                        }

                        if (settings.useDetails) {
                            enriched = enriched.copy(
                                runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
                                status = enrichment.status ?: enriched.status,
                                ageRating = enrichment.ageRating ?: enriched.ageRating,
                                country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                                language = enrichment.language ?: enriched.language
                            )
                        }

                        if (settings.useReleaseDates) {
                            enriched = enriched.copy(
                                releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
                            )
                        }
                    }

                    // TVDB fills blanks after TMDB (TMDB wins).
                    enriched = tvdbMetadataService.applyPreviewToItem(enriched, tvdbEnrichment)

                    // MDBList rating applies independently of TMDB enrichment.
                    if (mdbImdbRating != null) {
                        enriched = enriched.copy(imdbRating = mdbImdbRating.toFloat())
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
        append(':')
        append(currentMdbListSettings.enabled && currentMdbListSettings.apiKey.isNotBlank())
        append(':')
        append(currentTvdbSettings.enabled)
        append(':')
        append(currentTvdbSettings.language)
        append("::")
        append(itemSignature)
    }
}
