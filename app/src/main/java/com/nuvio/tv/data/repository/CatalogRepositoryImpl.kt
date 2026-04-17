package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    @ApplicationContext private val context: Context
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private const val DISK_CACHE_DIR = "catalog_cache"
        private const val DISK_CACHE_MAX_AGE_MS = 48L * 60 * 60 * 1000 // 48h
    }

    private val catalogCache = ConcurrentHashMap<String, CatalogRow>()
    private val gson = Gson()
    private val diskCacheDir by lazy {
        File(context.filesDir, DISK_CACHE_DIR).also { it.mkdirs() }
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val cacheKey = buildCacheKey(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs
        )

        // Emit cached data immediately if available (memory → disk)
        val cached = catalogCache[cacheKey]
        if (cached != null) {
            emit(NetworkResult.Success(cached))
        } else {
            val diskCached = loadFromDisk(cacheKey)
            if (diskCached != null) {
                catalogCache[cacheKey] = diskCached
                emit(NetworkResult.Success(diskCached))
            } else {
                emit(NetworkResult.Loading)
            }
        }
        val staleData = cached ?: catalogCache[cacheKey]

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { it.toDomain() }.distinctBy { it.id }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val effectiveSkipStep = if (skip == 0 && items.isNotEmpty() && items.size < skipStep) {
                    items.size
                } else {
                    skipStep
                }
                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && items.isNotEmpty(),
                    currentPage = if (effectiveSkipStep > 0) skip / effectiveSkipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = effectiveSkipStep,
                    extraArgs = extraArgs
                )
                catalogCache[cacheKey] = catalogRow
                saveToDisk(cacheKey, catalogRow)
                // Only emit fresh data if it differs from cache
                if (staleData == null || staleData.items != catalogRow.items) {
                    emit(NetworkResult.Success(catalogRow))
                }
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // Only emit error if we had no cached data
                if (staleData == null) {
                    emit(result)
                }
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        // Separate path from query string so the catalog path segment is
        // inserted before any query parameters (configurable addon URLs).
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            // For Stremio catalogs, pagination is controlled by `skip` inside extraArgs.
            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildCacheKey(
        addonBaseUrl: String,
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>
    ): String {
        val normalizedArgs = extraArgs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
        return "${normalizedBaseUrl}_${addonId}_${type}_${catalogId}_${skip}_${normalizedArgs}"
    }

    private fun diskCacheFile(cacheKey: String): File {
        val safeKey = cacheKey.hashCode().toUInt().toString(16)
        return File(diskCacheDir, "$safeKey.json")
    }

    private fun saveToDisk(cacheKey: String, row: CatalogRow) {
        try {
            val file = diskCacheFile(cacheKey)
            val wrapper = mapOf(
                "key" to cacheKey,
                "timestamp" to System.currentTimeMillis(),
                "row" to row
            )
            file.writeText(gson.toJson(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save catalog to disk: ${e.message}")
        }
    }

    private suspend fun loadFromDisk(cacheKey: String): CatalogRow? = withContext(Dispatchers.IO) {
        try {
            val file = diskCacheFile(cacheKey)
            if (!file.exists()) return@withContext null
            val json = file.readText()
            val mapType = object : TypeToken<Map<String, Any>>() {}.type
            val wrapper: Map<String, Any> = gson.fromJson(json, mapType)
            val timestamp = (wrapper["timestamp"] as? Double)?.toLong() ?: return@withContext null
            if (System.currentTimeMillis() - timestamp > DISK_CACHE_MAX_AGE_MS) {
                file.delete()
                return@withContext null
            }
            val rowJson = gson.toJson(wrapper["row"])
            gson.fromJson(rowJson, CatalogRow::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load catalog from disk: ${e.message}")
            null
        }
    }
}
