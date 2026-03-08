package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
    }

    // In-memory cache: "type:id" -> Meta
    private val metaCache = ConcurrentHashMap<String, Meta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = ConcurrentHashMap<String, Meta>()
    private val inFlightAddonMetaRequests = ConcurrentHashMap<String, Deferred<NetworkResult<Meta>>>()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        metaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val url = buildMetaUrl(addonBaseUrl, type, id)

        when (val result = safeApiCall { api.getMeta(url) }) {
            is NetworkResult.Success -> {
                val metaDto = result.data.meta
                if (metaDto != null) {
                    val episodeLabel = context.getString(R.string.episodes_episode)
                    val meta = metaDto.toDomain(episodeLabel)
                    metaCache[cacheKey] = meta
                    emit(NetworkResult.Success(meta))
                } else {
                    emit(NetworkResult.Error("Meta not found"))
                }
            }
            is NetworkResult.Error -> emit(result)
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    override fun getMetaFromAllAddons(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        addonMetaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val result = awaitAddonMetaResult(cacheKey = cacheKey, type = type, id = id)
        emit(result)
    }

    private suspend fun awaitAddonMetaResult(
        cacheKey: String,
        type: String,
        id: String
    ): NetworkResult<Meta> {
        addonMetaCache[cacheKey]?.let { return NetworkResult.Success(it) }
        val request = inFlightAddonMetaRequests[cacheKey] ?: requestScope.async {
            fetchMetaFromAllAddonsResult(cacheKey = cacheKey, type = type, id = id)
        }.also { created ->
            val existing = inFlightAddonMetaRequests.putIfAbsent(cacheKey, created)
            if (existing != null) {
                created.cancel()
            }
        }
        val activeRequest = inFlightAddonMetaRequests[cacheKey] ?: request
        return try {
            activeRequest.await()
        } finally {
            inFlightAddonMetaRequests.remove(cacheKey, activeRequest)
        }
    }

    private suspend fun fetchMetaFromAllAddonsResult(
        cacheKey: String,
        type: String,
        id: String
    ): NetworkResult<Meta> {
        val addons = addonRepository.getInstalledAddons().first()

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val metaResourceAddons = addons.filter { addon ->
            addon.resources.any { it.name == "meta" }
        }

        // Priority order:
        // 1) addons that explicitly support requested type
        // 2) addons that support inferred canonical type (for custom catalog types)
        // 3) top addon in installed order that exposes meta resource
        val prioritizedCandidates = linkedSetOf<Pair<Addon, String>>()
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                prioritizedCandidates.add(addon to requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    prioritizedCandidates.add(addon to inferredType)
                }
            }
        }
        metaResourceAddons.firstOrNull()?.let { topMetaAddon ->
            val fallbackType = when {
                topMetaAddon.supportsMetaType(requestedType) -> requestedType
                topMetaAddon.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            prioritizedCandidates.add(topMetaAddon to fallbackType)
        }

        if (prioritizedCandidates.isEmpty()) {
            // Last resort: try addons that declare the raw type (legacy behavior).
            val fallbackAddons = addons.filter { addon ->
                addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) }
            }

            for (addon in fallbackAddons) {
                val url = buildMetaUrl(addon.baseUrl, requestedType, id)
                when (val result = safeApiCall { api.getMeta(url) }) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                    val meta = metaDto.toDomain(episodeLabel)
                            addonMetaCache[cacheKey] = meta
                            metaCache[cacheKey] = meta
                            return NetworkResult.Success(meta)
                        }
                    }
                    else -> { /* Try next addon */ }
                }
            }

            return NetworkResult.Error("No addons support meta for type: $requestedType")
        }

        // Try each candidate until we find meta.
        for ((addon, candidateType) in prioritizedCandidates) {
            val url = buildMetaUrl(addon.baseUrl, candidateType, id)
            Log.d(
                TAG,
                "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url"
            )
            when (val result = safeApiCall { api.getMeta(url) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta
                    if (metaDto != null) {
                        val episodeLabel = context.getString(R.string.episodes_episode)
                    val meta = metaDto.toDomain(episodeLabel)
                        addonMetaCache[cacheKey] = meta
                        metaCache[cacheKey] = meta
                        Log.d(
                            TAG,
                            "Meta fetch success addonId=${addon.id} type=$candidateType id=$id"
                        )
                        return NetworkResult.Success(meta)
                    }
                    Log.d(
                        TAG,
                        "Meta response was null addonId=${addon.id} type=$candidateType id=$id"
                    )
                }
                is NetworkResult.Error -> {
                    if (isBenignMetaFetchFailure(result.message)) {
                        Log.d(
                            TAG,
                            "Meta fetch cancelled/short-circuited addonId=${addon.id} type=$candidateType id=$id message=${result.message}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Meta fetch failed addonId=${addon.id} type=$candidateType id=$id code=${result.code} message=${result.message}"
                        )
                    }
                }
                NetworkResult.Loading -> { /* no-op */ }
            }
        }

        return NetworkResult.Error("Meta not found in any addon")
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val encodedType = encodePathSegment(type)
        val encodedId = encodePathSegment(id)
        return "$cleanBaseUrl/meta/$encodedType/$encodedId.json"
    }

    private fun Addon.supportsMetaType(type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && resource.supportsType(target)
        }
    }

    private fun AddonResource.supportsType(type: String): Boolean {
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = type.trim()
        val known = setOf("movie", "series", "tv", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "tv"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun isBenignMetaFetchFailure(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        return "flow was aborted" in normalized ||
            "no more elements needed" in normalized ||
            "child of the scoped flow was cancelled" in normalized ||
            "standalonecoroutine was cancelled" in normalized ||
            "job was cancelled" in normalized
    }
    
    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
    }
}
