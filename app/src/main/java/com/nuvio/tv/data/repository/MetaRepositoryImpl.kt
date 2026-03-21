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
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
    }

    private enum class MetaFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class MetaAttemptFailure(
        val addonName: String,
        val kind: MetaFailureKind,
        val detail: String
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache: "type:id" -> Meta
    private val metaCache = ConcurrentHashMap<String, Meta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = ConcurrentHashMap<String, Meta>()
    private val primaryAddonMetaCache = ConcurrentHashMap<String, Meta>()

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate requests
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

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
        val deferred = inFlightMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    when (val result = safeApiCall { api.getMeta(url) }) {
                        is NetworkResult.Success -> {
                            val metaDto = result.data.meta ?: return@async null
                            val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                            metaCache[cacheKey] = meta
                            meta
                        }
                        else -> null
                    }
                } finally {
                    inFlightMeta.remove(cacheKey)
                }
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found)))
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

        val addons = addonRepository.getInstalledAddons().first()

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        val attemptedAddonNames = linkedSetOf<String>()
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
                addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) } &&
                    addon.resources.any { it.name == "meta" }
            }

            for (addon in fallbackAddons) {
                attemptedAddonNames += addon.displayName
                val url = buildMetaUrl(addon.baseUrl, requestedType, id)
                when (val result = safeApiCall { api.getMeta(url) }) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = metaDto.toDomain(episodeLabel)
                            addonMetaCache[cacheKey] = meta
                            metaCache[cacheKey] = meta
                            emit(NetworkResult.Success(meta))
                            return@flow
                        } else {
                            attemptedFailures += buildMissingMetaFailure(addon)
                        }
                    }
                    is NetworkResult.Error -> {
                        attemptedFailures += buildAddonFailure(addon, result)
                    }
                    NetworkResult.Loading -> { /* Try next addon */ }
                }
            }

            val fallbackMessage = if (fallbackAddons.isEmpty()) {
                context.getString(R.string.error_meta_no_supported_addon, requestedType)
            } else {
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = attemptedAddonNames.toList(),
                    failures = attemptedFailures
                )
            }
            emit(NetworkResult.Error(fallbackMessage))
            return@flow
        }

        val deferred = inFlightAddonMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    for ((addon, candidateType) in prioritizedCandidates) {
                        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                        Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url")
                        when (val result = safeApiCall { api.getMeta(url) }) {
                            is NetworkResult.Success -> {
                                val metaDto = result.data.meta
                                if (metaDto != null) {
                                    val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                    addonMetaCache[cacheKey] = meta
                                    metaCache[cacheKey] = meta
                                    Log.d(TAG, "Meta fetch success addonId=${addon.id} type=$candidateType id=$id")
                                    return@async meta
                                }
                                Log.d(TAG, "Meta response was null addonId=${addon.id} type=$candidateType id=$id")
                            }
                            else -> { /* try next */ }
                        }
                    }
                    null
                } finally {
                    inFlightAddonMeta.remove(cacheKey)
                }
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(
                NetworkResult.Error(
                    buildAggregateFailureMessage(
                        type = requestedType,
                        id = id,
                        attemptedAddonNames = attemptedAddonNames.toList(),
                        failures = attemptedFailures
                    )
                )
            )
        }
    }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val addons = addonRepository.getInstalledAddons().first()
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val candidate = selectPrimaryMetaCandidate(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType
        )

        if (candidate == null) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_no_supported_addon, requestedType)))
            return@flow
        }

        val (addon, candidateType) = candidate
        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
        Log.d(
            TAG,
            "Trying primary meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url"
        )

        val deferred = inFlightPrimaryMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    when (val result = safeApiCall { api.getMeta(url) }) {
                        is NetworkResult.Success -> {
                            val metaDto = result.data.meta ?: return@async null
                            val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                            primaryAddonMetaCache[cacheKey] = meta
                            metaCache[cacheKey] = meta
                            meta
                        }
                        else -> null
                    }
                } finally {
                    inFlightPrimaryMeta.remove(cacheKey)
                }
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(buildAggregateFailureMessage(
                type = requestedType,
                id = id,
                attemptedAddonNames = listOf(addon.displayName),
                failures = listOf(buildMissingMetaFailure(addon))
            )))
        }
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

    private fun selectPrimaryMetaCandidate(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String
    ): Pair<Addon, String>? {
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                return addon to requestedType
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    return addon to inferredType
                }
            }
        }
        val topMetaAddon = addons.firstOrNull { addon ->
            addon.resources.any { it.name == "meta" }
        } ?: return null
        val fallbackType = when {
            topMetaAddon.supportsMetaType(requestedType) -> requestedType
            topMetaAddon.supportsMetaType(inferredType) -> inferredType
            else -> inferredType.ifBlank { requestedType }
        }
        return topMetaAddon to fallbackType
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildMissingMetaFailure(addon: Addon): MetaAttemptFailure {
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.MISSING,
            detail = "returned no metadata for this id"
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): MetaAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingMetaFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                "could not reach the addon server"
            error.message.contains("Failed to connect", ignoreCase = true) ->
                "connection to the addon failed"
            error.message.contains("timeout", ignoreCase = true) ->
                "the addon request timed out"
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                "the addon uses an insecure HTTP connection blocked by Android"
            error.message.isBlank() ->
                "the addon request failed"
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<MetaAttemptFailure>
    ): String {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_meta_no_addon_for_id, id, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == MetaFailureKind.MISSING }

        return if (missingOnly) {
            context.getString(R.string.error_meta_tried_none, triedAddons, id, type)
        } else {
            val issueSummary = failures
                .filter { it.kind == MetaFailureKind.REQUEST_FAILED }
                .distinctBy { it.addonName to it.detail }
                .take(3)
                .joinToString("; ") { "${it.addonName}: ${it.detail}" }
            if (issueSummary.isBlank()) {
                context.getString(R.string.error_meta_tried_generic, triedAddons, id, type)
            } else {
                context.getString(R.string.error_meta_tried_issues, triedAddons, id, type, issueSummary)
            }
        }
    }
    
    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
        primaryAddonMetaCache.clear()
        inFlightMeta.clear()
        inFlightAddonMeta.clear()
        inFlightPrimaryMeta.clear()
    }
}
