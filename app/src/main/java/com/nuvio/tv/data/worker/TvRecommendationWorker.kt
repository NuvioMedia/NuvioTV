package com.nuvio.tv.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.core.recommendations.RecommendationDataStore
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.firstOrNull

/**
 * Periodically syncs TV Home Screen recommendation channels in the background.
 * Scheduled via WorkManager every 30 minutes (configurable in [RecommendationConstants]).
 *
 * Retries up to 3 times on transient failures; after that it reports failure
 * so the periodic schedule continues on the next window.
 */
@HiltWorker
class TvRecommendationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recommendationManager: TvRecommendationManager,
    private val recommendationDataStore: RecommendationDataStore,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : CoroutineWorker(context, params) {


    override suspend fun doWork(): Result {
        return try {
            if (!recommendationDataStore.isEnabled()) {
                return Result.success()
            }

            // 1. Sync WatchNext and cleanup legacy channels
            recommendationManager.syncAllChannels()

            // 2. Refresh Trending and New Releases silently from Addons (without opening the app)
            val addons = addonRepository.getInstalledAddons().firstOrNull() ?: emptyList()
            if (addons.isNotEmpty()) {
                val catalogs = loadEssentialCatalogs(addons)
                
                // Update Trending (Interleave 1st and 2nd lists, usually popular movies + series)
                val trending1 = catalogs.getOrNull(0)?.items.orEmpty().take(15)
                val trending2 = catalogs.getOrNull(1)?.items.orEmpty().take(15)
                val mixedTrending = buildList {
                    val maxT = maxOf(trending1.size, trending2.size)
                    for (i in 0 until maxT) {
                        if (i < trending1.size) add(trending1[i])
                        if (i < trending2.size) add(trending2[i])
                    }
                }.distinctBy { it.id }

                if (mixedTrending.isNotEmpty()) {
                    recommendationManager.updateTrending(mixedTrending)
                }
                
                // Update New Releases (Mix of newest movie / series lists)
                val newMovies = catalogs.firstOrNull { it.apiType == "movie" && it.items.isNotEmpty() }?.items.orEmpty().take(15)
                val newSeries = catalogs.firstOrNull { it.apiType == "series" && it.items.isNotEmpty() }?.items.orEmpty().take(15)
                
                val newReleases = (newMovies + newSeries)
                    .distinctBy { it.id }
                    .filter { !it.releaseInfo.isNullOrBlank() }
                    .sortedByDescending { it.releaseInfo }
                
                if (newReleases.isNotEmpty()) {
                    recommendationManager.updateNewReleases(newReleases)
                }
            }

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun loadEssentialCatalogs(addons: List<com.nuvio.tv.domain.model.Addon>): List<com.nuvio.tv.domain.model.CatalogRow> {
        val loadedRows = mutableListOf<com.nuvio.tv.domain.model.CatalogRow>()
        
        // Grab top 2 addons to fetch fresh basic lists
        for (addon in addons.take(2)) {
            val catalogsToLoad = addon.catalogs
                .filter { it.apiType == "movie" || it.apiType == "series" } 
                .filter { it.extra.none { extra -> extra.isRequired } } // Avoid catalog elements requiring user search queries
                .take(5) // Enough to hit popular/trending/new categories

            for (catalog in catalogsToLoad) {
                val result = catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    skip = 0,
                    supportsSkip = catalog.extra.any { it.name == "skip" }
                ).firstOrNull()
                
                if (result is NetworkResult.Success && result.data.items.isNotEmpty()) {
                    loadedRows.add(result.data)
                }
            }
            if (loadedRows.size >= 4) break // Stop unnecessarily hitting APIs if we have enough lines
        }
        return loadedRows
    }
}
