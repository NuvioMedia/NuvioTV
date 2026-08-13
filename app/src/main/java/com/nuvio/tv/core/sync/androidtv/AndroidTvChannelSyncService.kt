package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"
private const val DEBOUNCE_MS = 2_000L
private const val CATALOG_DEBOUNCE_MS = 500L
private const val MAX_CATALOG_ITEMS = 20

@Singleton
class AndroidTvChannelSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: AndroidTvChannelManager,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tvRecommendationManager: TvRecommendationManager,
    private val tvChannelPreferences: TvChannelPreferences,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogSyncMutex = Mutex()

    @Volatile
    private var appInForeground = false

    fun onForegroundChanged(foreground: Boolean) {
        val wasForeground = appInForeground
        appInForeground = foreground
        if (wasForeground && !foreground) {
            scope.launch { reconcileFromCache() }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) {
            Log.d(TAG, "Non-leanback device; channel sync skipped")
            return
        }

        TvChannelRefreshJobService.schedulePeriodic(context)

        // Initial launcher sync.
        scope.launch { reconcileFromCache() }

        // Existing Continue Watching observer.
        scope.launch {
            combine(
                cwEnrichmentCache.snapshotVersion
                    .dropWhile { it == 0 },
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw
            ) { _, daysCap, dismissed, useEpisodeThumbnails ->
                ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
            }
                .debounce(DEBOUNCE_MS)
                .collect { settings ->
                    if (appInForeground) return@collect
                    reconcileFromCache(settings)
                }
        }

        // Selected addon catalogs -> dedicated Android TV Home channels.
        scope.launch {
            combine(
                addonRepository.getInstalledAddons().distinctUntilChanged(),
                tvChannelPreferences.enabledCatalogKeys.distinctUntilChanged()
            ) { installedAddons, enabledCatalogKeys ->
                installedAddons.enabledAddons() to enabledCatalogKeys
            }
                .debounce(CATALOG_DEBOUNCE_MS)
                .collect { (addons, enabledCatalogKeys) ->
                    syncSelectedCatalogs(
                        addons = addons,
                        enabledCatalogKeys = enabledCatalogKeys
                    )
                }
        }
    }

    /**
     * Reconciles Continue Watching, native Watch Next and selected catalog rows.
     * This method is also called by the existing periodic Android TV refresh job.
     */
    suspend fun reconcileFromCache(settings: ChannelSettingsSnapshot? = null) {
        val resolvedSettings = settings ?: run {
            val daysCap = traktSettingsDataStore.continueWatchingDaysCap.first()
            val dismissed = traktSettingsDataStore.dismissedNextUpKeys.first()
            val useEpisodeThumbnails = layoutPreferenceDataStore.useEpisodeThumbnailsInCw.first()
            ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
        }

        val inProgressItems = runCatching { cwEnrichmentCache.getInProgressSnapshot() }
            .getOrDefault(emptyList())
        val nextUpItems = runCatching { cwEnrichmentCache.getNextUpSnapshot() }
            .getOrDefault(emptyList())

        val channelItems = buildChannelItems(
            inProgressItems,
            nextUpItems,
            resolvedSettings
        )

        Log.d(
            TAG,
            "Reconciling from cache: ${channelItems.size} items " +
                "(${inProgressItems.size} in-progress, ${nextUpItems.size} next-up raw)"
        )

        manager.reconcile(channelItems)

        val cutoffMs =
            if (
                resolvedSettings.daysCap ==
                TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL
            ) {
                null
            } else {
                val windowMs =
                    resolvedSettings.daysCap.toLong() *
                        24L * 60L * 60L * 1000L
                System.currentTimeMillis() - windowMs
            }

        val watchNextInProgress = inProgressItems
            .filter { cutoffMs == null || it.lastWatched >= cutoffMs }

        runCatching {
            val cwItems = watchNextInProgress.map {
                ContinueWatchingItem.InProgress(
                    it.toWatchProgress(
                        resolvedSettings.useEpisodeThumbnails
                    )
                )
            }

            tvRecommendationManager.updateWatchNextFromCwItems(cwItems)
        }

        // The same refresh cycle also keeps user-selected catalog channels fresh.
        reconcileSelectedCatalogs()
    }

    suspend fun reconcileSelectedCatalogs() {
        if (!manager.isSupported()) return

        val enabledCatalogKeys =
            tvChannelPreferences.getEnabledCatalogKeys()

        if (enabledCatalogKeys.isEmpty()) return

        val addons = addonRepository
            .getInstalledAddons()
            .first()
            .enabledAddons()

        syncSelectedCatalogs(
            addons = addons,
            enabledCatalogKeys = enabledCatalogKeys
        )
    }

    private suspend fun syncSelectedCatalogs(
        addons: List<Addon>,
        enabledCatalogKeys: Set<String>
    ) {
        if (enabledCatalogKeys.isEmpty()) return

        catalogSyncMutex.withLock {
            val availableCatalogs =
                buildMap<String, Pair<Addon, CatalogDescriptor>> {
                    addons.forEach { addon ->
                        addon.catalogs
                            .filterNot { it.isSearchOnlyTvCatalog() }
                            .forEach { catalog ->
                                val key = catalogRowStableKey(
                                    addonId = addon.id,
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                )
                                put(key, addon to catalog)
                            }
                    }
                }

            enabledCatalogKeys.forEach { catalogKey ->
                val pair = availableCatalogs[catalogKey]

                if (pair == null) {
                    // Addon is disabled/removed or catalog no longer exists.
                    // Keep the user's selection, but hide the stale launcher row.
                    manager.removeCatalogChannel(catalogKey)
                    return@forEach
                }

                val (addon, catalog) = pair
                val displayName = buildCatalogChannelName(addon, catalog)

                val result = runCatching {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = 0,
                        skipStep = catalog.skipStep(),
                        supportsSkip = catalog.supportsExtra("skip")
                    ).first { it !is NetworkResult.Loading }
                }.getOrElse { error ->
                    Log.w(
                        TAG,
                        "TV catalog fetch failed key=$catalogKey",
                        error
                    )
                    return@forEach
                }

                when (result) {
                    is NetworkResult.Success -> {
                        manager.reconcileCatalog(
                            catalogKey = catalogKey,
                            displayName = displayName,
                            items = result.data.items,
                            maxItems = MAX_CATALOG_ITEMS
                        )
                    }

                    is NetworkResult.Error -> {
                        Log.w(
                            TAG,
                            "TV catalog fetch error key=$catalogKey " +
                                "code=${result.code} message=${result.message}"
                        )
                    }

                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun buildCatalogChannelName(
        addon: Addon,
        catalog: CatalogDescriptor
    ): String {
        val catalogName = catalog.name
            .trim()
            .ifBlank { catalog.id }

        val addonName = addon.displayName
            .trim()
            .ifBlank { addon.name }

        return "$catalogName · $addonName"
    }

    private fun CatalogDescriptor.isSearchOnlyTvCatalog(): Boolean {
        return extra.any {
            it.name.equals("search", ignoreCase = true) &&
                it.isRequired
        } || extraRequired.any {
            it.equals("search", ignoreCase = true)
        }
    }

    private fun buildChannelItems(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        settings: ChannelSettingsSnapshot
    ): List<WatchProgress> {
        val cutoffMs =
            if (
                settings.daysCap ==
                TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL
            ) {
                null
            } else {
                val windowMs =
                    settings.daysCap.toLong() *
                        24L * 60L * 60L * 1000L
                System.currentTimeMillis() - windowMs
            }

        val filteredInProgress = inProgress
            .filter {
                cutoffMs == null ||
                    it.lastWatched >= cutoffMs
            }

        val filteredNextUp = nextUp
            .filter { it.hasAired }
            .filter {
                cutoffMs == null ||
                    it.lastWatched >= cutoffMs
            }
            .filter {
                nextUpDismissKey(it) !in
                    settings.dismissedNextUp
            }

        val inProgressContentIds =
            filteredInProgress
                .mapTo(mutableSetOf()) {
                    it.contentId
                }

        val deduplicatedNextUp =
            filteredNextUp.filter {
                it.contentId !in
                    inProgressContentIds
            }

        data class SortableItem(
            val watchProgress: WatchProgress,
            val sortKey: Long
        )

        val inProgressSorted =
            filteredInProgress.map { item ->
                SortableItem(
                    item.toWatchProgress(
                        settings.useEpisodeThumbnails
                    ),
                    item.lastWatched
                )
            }

        val nextUpSorted =
            deduplicatedNextUp.map { item ->
                SortableItem(
                    item.toWatchProgress(
                        settings.useEpisodeThumbnails
                    ),
                    item.sortTimestamp
                )
            }

        return (inProgressSorted + nextUpSorted)
            .sortedByDescending {
                it.sortKey
            }
            .map {
                it.watchProgress
            }
            .distinctBy {
                it.contentId
            }
    }

    private fun nextUpDismissKey(
        item: CachedNextUpItem
    ): String {
        return buildString {
            append(item.contentId)

            if (item.seedSeason != null) {
                append("_s${item.seedSeason}")

                if (item.seedEpisode != null) {
                    append("e${item.seedEpisode}")
                }
            }
        }
    }

    data class ChannelSettingsSnapshot(
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val useEpisodeThumbnails: Boolean
    )
}

private fun CachedInProgressItem.toWatchProgress(
    useEpisodeThumbnails: Boolean
): WatchProgress {
    val image =
        if (useEpisodeThumbnails) {
            episodeThumbnail ?: backdrop
        } else {
            backdrop ?: episodeThumbnail
        }

    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}

private fun CachedNextUpItem.toWatchProgress(
    useEpisodeThumbnails: Boolean
): WatchProgress {
    val image =
        if (useEpisodeThumbnails) {
            thumbnail ?: backdrop
        } else {
            backdrop ?: thumbnail
        }

    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 0,
        duration = 0,
        lastWatched = sortTimestamp
    )
}
