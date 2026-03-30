package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.RecommendationRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class RecommendationRepositoryImpl @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : RecommendationRepository {

    companion object {
        private const val TAG = "RecommendationRepo"
        private const val MAX_HISTORY_ITEMS = 50
        private const val MAX_CATALOG_QUERIES = 4
        private const val MAX_CANDIDATES = 50
        private const val TOP_CANDIDATES_FOR_SELECTION = 10
        private const val TIMEOUT_MS = 2000L
    }

    override suspend fun getSurpriseRecommendation(): MetaPreview? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val recommendation = generateRecommendation()
                if (recommendation != null) {
                    Log.d(TAG, "Recommendation: ${recommendation.name} (${recommendation.id})")
                }
                recommendation
            } catch (e: Exception) {
                Log.w(TAG, "Recommendation failed", e)
                null
            }
        }
    }

    private suspend fun generateRecommendation(): MetaPreview? {
        val addons = addonRepository.getInstalledAddons().first()
        if (addons.isEmpty()) return null

        val watchHistory = watchProgressRepository.allProgress.first()
            .take(MAX_HISTORY_ITEMS)
        val watchedIds = watchHistory.map { it.contentId }.toSet()

        // Build genre frequency map from watch history
        // We need to find genres for watched items from catalog data
        val genreFrequency = buildGenreFrequency(watchedIds, addons)

        return if (genreFrequency.isNotEmpty()) {
            getGenreBasedRecommendation(genreFrequency, watchedIds, addons)
        } else {
            getFallbackRecommendation(watchedIds, addons)
        }
    }

    private suspend fun buildGenreFrequency(
        watchedIds: Set<String>,
        addons: List<com.nuvio.tv.domain.model.Addon>
    ): Map<String, Int> {
        // Fetch first page of catalogs to find genre data for watched items
        val genreFrequency = mutableMapOf<String, Int>()

        // Query a few catalogs and match watched IDs to extract genres
        val catalogQueries = addons.flatMap { addon ->
            addon.catalogs.take(2).map { catalog ->
                Triple(addon, catalog, catalog.apiType)
            }
        }.take(MAX_CATALOG_QUERIES)

        coroutineScope {
            val results = catalogQueries.map { (addon, catalog, type) ->
                async {
                    fetchCatalogItems(addon.baseUrl, addon.id, addon.name, catalog.id, catalog.name, type)
                }
            }.awaitAll()

            for (items in results) {
                for (item in items) {
                    if (item.id in watchedIds) {
                        for (genre in item.genres) {
                            genreFrequency[genre] = (genreFrequency[genre] ?: 0) + 1
                        }
                    }
                }
            }
        }

        return genreFrequency
    }

    private suspend fun getGenreBasedRecommendation(
        genreFrequency: Map<String, Int>,
        watchedIds: Set<String>,
        addons: List<com.nuvio.tv.domain.model.Addon>
    ): MetaPreview? {
        val topGenres = genreFrequency.entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }

        Log.d(TAG, "Top genres: $topGenres (from ${genreFrequency.size} genres)")

        // Find catalogs that support genre filtering
        val genreCatalogs = addons.flatMap { addon ->
            addon.catalogs.filter { catalog ->
                catalog.extra.any { it.name == "genre" }
            }.map { catalog -> Triple(addon, catalog, catalog.apiType) }
        }.take(MAX_CATALOG_QUERIES)

        val candidates = mutableListOf<MetaPreview>()

        coroutineScope {
            val results = if (genreCatalogs.isNotEmpty()) {
                // Query with genre filter
                genreCatalogs.map { (addon, catalog, type) ->
                    async {
                        fetchCatalogItems(
                            addon.baseUrl, addon.id, addon.name,
                            catalog.id, catalog.name, type,
                            extraArgs = mapOf("genre" to topGenres.first())
                        )
                    }
                }.awaitAll()
            } else {
                // No genre filter support — fetch default catalogs
                addons.flatMap { addon ->
                    addon.catalogs.take(2).map { catalog ->
                        Triple(addon, catalog, catalog.apiType)
                    }
                }.take(MAX_CATALOG_QUERIES).map { (addon, catalog, type) ->
                    async {
                        fetchCatalogItems(addon.baseUrl, addon.id, addon.name, catalog.id, catalog.name, type)
                    }
                }.awaitAll()
            }

            for (items in results) {
                candidates.addAll(items)
            }
        }

        // Filter out watched items and deduplicate
        val filtered = candidates
            .filter { it.id !in watchedIds }
            .distinctBy { it.id }
            .take(MAX_CANDIDATES)

        if (filtered.isEmpty()) {
            return getFallbackRecommendation(watchedIds, addons)
        }

        return selectWeightedRandom(filtered, topGenres)
    }

    private suspend fun getFallbackRecommendation(
        watchedIds: Set<String>,
        addons: List<com.nuvio.tv.domain.model.Addon>
    ): MetaPreview? {
        Log.d(TAG, "Using fallback recommendation (no genre data)")

        // Just pick from the first available catalog
        for (addon in addons) {
            for (catalog in addon.catalogs.take(2)) {
                val items = fetchCatalogItems(
                    addon.baseUrl, addon.id, addon.name,
                    catalog.id, catalog.name, catalog.apiType
                )
                val unwatched = items.filter { it.id !in watchedIds }
                if (unwatched.isNotEmpty()) {
                    return unwatched[Random.nextInt(unwatched.size)]
                }
            }
        }
        return null
    }

    internal fun selectWeightedRandom(
        candidates: List<MetaPreview>,
        topGenres: List<String>
    ): MetaPreview? {
        if (candidates.isEmpty()) return null

        val scored = candidates.map { item ->
            val genreMatchCount = item.genres.count { it in topGenres }
            val ratingScore = item.imdbRating ?: 5f
            val recencyBonus = if (isRecentRelease(item.releaseInfo)) 2f else 0f
            val score = genreMatchCount * 3f + ratingScore + recencyBonus
            item to score
        }.sortedByDescending { it.second }
            .take(TOP_CANDIDATES_FOR_SELECTION)

        // Weighted random selection
        val totalWeight = scored.sumOf { it.second.toDouble() }
        if (totalWeight <= 0) return scored.firstOrNull()?.first

        var random = Random.nextDouble() * totalWeight
        for ((item, score) in scored) {
            random -= score
            if (random <= 0) return item
        }
        return scored.last().first
    }

    private fun isRecentRelease(releaseInfo: String?): Boolean {
        if (releaseInfo == null) return false
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return releaseInfo.contains(currentYear.toString()) ||
                releaseInfo.contains((currentYear - 1).toString())
    }

    private suspend fun fetchCatalogItems(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        extraArgs: Map<String, String> = emptyMap()
    ): List<MetaPreview> {
        return try {
            val result = catalogRepository.getCatalog(
                addonBaseUrl = addonBaseUrl,
                addonId = addonId,
                addonName = addonName,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                extraArgs = extraArgs
            ).firstOrNull { it is NetworkResult.Success }

            when (result) {
                is NetworkResult.Success -> result.data.items
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch catalog $catalogId from $addonId", e)
            emptyList()
        }
    }
}
