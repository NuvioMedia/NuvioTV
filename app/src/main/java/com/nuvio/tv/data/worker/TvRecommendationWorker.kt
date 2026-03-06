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
                recommendationManager.clearAll()
                return Result.success()
            }

            // 1. Sync WatchNext and cleanup legacy channels
            recommendationManager.syncAllChannels()

            // 2. Refresh dynamic channels from Addons
            val addons = addonRepository.getInstalledAddons().firstOrNull() ?: emptyList()
            if (addons.isNotEmpty()) {
                val enabledCatalogs = recommendationDataStore.getEnabledCatalogs()
                val catalogs = loadEssentialCatalogs(addons, enabledCatalogs)
                
                catalogs.forEach { row ->
                    val catalogKey = "${row.addonId}_${row.apiType}_${row.catalogId}"
                    val catalogName = "${row.catalogName} (${row.addonName})"
                    if (enabledCatalogs.contains(catalogKey)) {
                        recommendationManager.updateCatalogChannel(
                            catalogKey = catalogKey,
                            catalogName = catalogName,
                            items = row.items
                        )
                    }
                }
            }

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun loadEssentialCatalogs(
        addons: List<com.nuvio.tv.domain.model.Addon>, 
        enabledCatalogs: Set<String>
    ): List<com.nuvio.tv.domain.model.CatalogRow> {
        val loadedRows = mutableListOf<com.nuvio.tv.domain.model.CatalogRow>()
        
        for (addon in addons) {
            val catalogsToLoad = addon.catalogs
                .filter { catalog ->
                    val key = "${addon.id}_${catalog.apiType}_${catalog.id}"
                    enabledCatalogs.contains(key)
                }

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
        }
        return loadedRows
    }
}
