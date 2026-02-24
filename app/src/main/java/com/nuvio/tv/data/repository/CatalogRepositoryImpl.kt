package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    private val catalogCache = ConcurrentHashMap<String, CatalogRow>()

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val cacheKey = buildCacheKey(
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            extraArgs = extraArgs
        )

        // Emit cached data immediately if available
        val cached = catalogCache[cacheKey]
        if (cached != null) {
            emit(NetworkResult.Success(cached))
        } else {
            emit(NetworkResult.Loading)
        }

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { it.toDomain() }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

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
                    currentPage = skip / 100,
                    supportsSkip = supportsSkip
                )
                catalogCache[cacheKey] = catalogRow
                // Only emit fresh data if it differs from cache
                if (cached == null || cached.items != catalogRow.items) {
                    emit(NetworkResult.Success(catalogRow))
                }
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // Only emit error if we had no cached data
                if (cached == null) {
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
        val cleanBaseUrl = baseUrl.trimEnd('/')

        if (extraArgs.isEmpty()) {
            return if (skip > 0) {
                "$cleanBaseUrl/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$cleanBaseUrl/catalog/$type/$catalogId.json"
            }
        }

        val allArgs = LinkedHashMap<String, String>()
        allArgs.putAll(extraArgs)

        // For Stremio catalogs, pagination is controlled by `skip` inside extraArgs.
        if (!allArgs.containsKey("skip") && skip > 0) {
            allArgs["skip"] = skip.toString()
        }

        val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
            "${encodeArg(key)}=${encodeArg(value)}"
        }

        return "$cleanBaseUrl/catalog/$type/$catalogId/$encodedArgs.json"
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildCacheKey(
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val normalizedArgs = extraArgs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        return "${addonId}_${type}_${catalogId}_${skip}_${normalizedArgs}"
    }
}
