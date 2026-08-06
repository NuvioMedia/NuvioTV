package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.enabledAddons
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
import okhttp3.CacheControl
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
        /** Default TTL when addon response has no Cache-Control header (6 hours). */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
        /** Minimum TTL for meta responses even when server says no-cache/no-store (5 minutes).
         *  Prevents excessive re-fetching on every details screen visit for addons
         *  that don't set meaningful Cache-Control headers. */
        private const val MIN_META_TTL_MS = 5L * 60 * 1000
    }

    /** Internal result type for the deferred meta lookup to distinguish
     *  "fetched meta", "nothing found", and "source addon already provides this data". */
    private sealed class MetaLookupResult {
        data class Found(val meta: Meta) : MetaLookupResult()
        data object NotFound : MetaLookupResult()
        /** The first viable candidate is the same addon that served the catalog,
         *  so the item already has its meta — no request needed. */
        data object SourceSufficient : MetaLookupResult()
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

    /** Wrapper for cached meta with an expiration timestamp. */
    private data class CachedMeta(
        val meta: Meta,
        val expiresAtMs: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache: "addonBaseUrl|type:id" -> CachedMeta with TTL.
    // Respects Cache-Control max-age from addon responses.
    private val metaCache = ConcurrentHashMap<String, CachedMeta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = ConcurrentHashMap<String, CachedMeta>()
    private val primaryAddonMetaCache = ConcurrentHashMap<String, CachedMeta>()

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate requests
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<MetaLookupResult>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = addonMetaCacheKey(addonBaseUrl, type, id)
        metaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            metaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val url = buildMetaUrl(addonBaseUrl, type, id)
        val deferred = inFlightMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    val response = api.getMeta(url)
                    if (response.isSuccessful) {
                        val metaDto = response.body()?.meta ?: return@async null
                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                        val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                        metaCache[cacheKey] = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                        meta
                    } else {
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "getMeta failed for $url: ${e.message}")
                    null
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
        id: String,
        sourceAddonBaseUrl: String?
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        addonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            addonMetaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        val attemptedAddonNames = linkedSetOf<String>()
        val metaResourceAddons = addons.filter { addon ->
            addon.resources.any { it.name == "meta" }
        }

        // Priority order:
        // 1) addons that explicitly support requested type AND support the ID prefix
        // 2) addons that support inferred canonical type AND support the ID prefix
        // 3) addons that support the type but have no idPrefixes (accept all IDs)
        // 4) top addon in installed order that exposes meta resource
        val prioritizedCandidates = linkedSetOf<Pair<Addon, String>>()
        // First pass: addons that explicitly match type AND id prefix
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType) && addon.supportsMetaId(id)) {
                prioritizedCandidates.add(addon to requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType) && addon.supportsMetaId(id)) {
                    prioritizedCandidates.add(addon to inferredType)
                }
            }
        }
        metaResourceAddons.firstOrNull { it.supportsMetaId(id) }?.let { topMetaAddon ->
            val fallbackType = when {
                topMetaAddon.supportsMetaType(requestedType) -> requestedType
                topMetaAddon.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            prioritizedCandidates.add(topMetaAddon to fallbackType)
        }
        // Fallback: if no ID-matching addons found, include addons without idPrefixes
        if (prioritizedCandidates.isEmpty()) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(requestedType) && addon.idPrefixes.isEmpty()) {
                    prioritizedCandidates.add(addon to requestedType)
                }
            }
            metaResourceAddons.firstOrNull { it.idPrefixes.isEmpty() }?.let { topMetaAddon ->
                val fallbackType = when {
                    topMetaAddon.supportsMetaType(requestedType) -> requestedType
                    topMetaAddon.supportsMetaType(inferredType) -> inferredType
                    else -> inferredType.ifBlank { requestedType }
                }
                prioritizedCandidates.add(topMetaAddon to fallbackType)
            }
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
                try {
                    val response = api.getMeta(url)
                    if (response.isSuccessful) {
                        val metaDto = response.body()?.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = metaDto.toDomain(episodeLabel)
                            val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                            val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                            addonMetaCache[cacheKey] = cached
                            metaCache[addonMetaCacheKey(addon.baseUrl, requestedType, id)] = cached
                            emit(NetworkResult.Success(meta))
                            return@flow
                        } else {
                            attemptedFailures += buildMissingMetaFailure(addon)
                        }
                    } else {
                        attemptedFailures += MetaAttemptFailure(
                            addonName = addon.displayName,
                            kind = if (response.code() == 404) MetaFailureKind.MISSING else MetaFailureKind.REQUEST_FAILED,
                            detail = response.message() ?: "HTTP ${response.code()}"
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attemptedFailures += MetaAttemptFailure(
                        addonName = addon.displayName,
                        kind = MetaFailureKind.REQUEST_FAILED,
                        detail = e.message ?: context.getString(R.string.network_error_unknown)
                    )
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
                    // Normalize source addon URL for comparison so we can detect
                    // when the candidate is the same addon that served the catalog.
                    val normalizedSourceUrl = sourceAddonBaseUrl
                        ?.let { splitAddonBaseUrl(it).let { (p, q) -> "$p$q" } }

                    for ((addon, candidateType) in prioritizedCandidates) {
                        // If this candidate is the same addon that provided the catalog
                        // data for this item, the item already carries its meta —
                        // return immediately without making a request and without
                        // trying further addons.
                        if (normalizedSourceUrl != null) {
                            val normalizedCandidateUrl = splitAddonBaseUrl(addon.baseUrl)
                                .let { (p, q) -> "$p$q" }
                            if (normalizedCandidateUrl == normalizedSourceUrl) {
                                Log.d(TAG, "Source addon matched, catalog meta is sufficient addon=${addon.name} type=$candidateType id=$id")
                                return@async MetaLookupResult.SourceSufficient
                            }
                        }

                        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                        Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url")
                        try {
                            val response = api.getMeta(url)
                            if (response.isSuccessful) {
                                val metaDto = response.body()?.meta
                                if (metaDto != null) {
                                    val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                    val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                                    val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                                    addonMetaCache[cacheKey] = cached
                                    metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = cached
                                    Log.d(TAG, "Meta fetch success addonId=${addon.id} type=$candidateType id=$id ttl=${ttlMs}ms")
                                    return@async MetaLookupResult.Found(meta)
                                }
                                Log.d(TAG, "Meta response was null addonId=${addon.id} type=$candidateType id=$id")
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Meta fetch failed addonId=${addon.id} type=$candidateType id=$id: ${e.message}")
                            /* try next */
                        }
                    }
                    MetaLookupResult.NotFound
                } finally {
                    inFlightAddonMeta.remove(cacheKey)
                }
            }
        }

        when (val lookupResult = deferred.await()) {
            is MetaLookupResult.Found -> {
                emit(NetworkResult.Success(lookupResult.meta))
            }
            is MetaLookupResult.SourceSufficient -> {
                emit(NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE))
            }
            is MetaLookupResult.NotFound -> {
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
    }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            primaryAddonMetaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
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
                    val response = api.getMeta(url)
                    if (response.isSuccessful) {
                        val metaDto = response.body()?.meta ?: return@async null
                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                        val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                        val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                        primaryAddonMetaCache[cacheKey] = cached
                        metaCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = cached
                        meta
                    } else {
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Primary meta fetch failed for $url: ${e.message}")
                    null
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

    /**
     * Splits an addon base URL into its path (trailing slashes trimmed) and
     * query portions. Shared by URL construction and cache keying so the two
     * always normalize equivalent base URLs identically.
     */
    private fun splitAddonBaseUrl(baseUrl: String): Pair<String, String> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        return basePath to baseQuery
    }

    private fun addonMetaCacheKey(addonBaseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(addonBaseUrl)
        return "$basePath$baseQuery|$type:$id"
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        val encodedType = encodePathSegment(type)
        val encodedId = encodePathSegment(id)
        return "$basePath/meta/$encodedType/$encodedId.json$baseQuery"
    }

    private fun Addon.supportsMetaType(type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && resource.supportsType(target)
        }
    }

    /**
     * Check if an addon can handle a specific ID based on idPrefixes.
     * Returns true if:
     * - The addon has no idPrefixes (accepts all IDs)
     * - The resource-level idPrefixes match the ID
     * - The addon-level idPrefixes match the ID
     */
    private fun Addon.supportsMetaId(id: String): Boolean {
        // Check resource-level idPrefixes first
        val metaResource = resources.firstOrNull { it.name == "meta" }
        if (metaResource?.idPrefixes != null && metaResource.idPrefixes.isNotEmpty()) {
            return metaResource.idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        // Fall back to addon-level idPrefixes
        if (idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        // No idPrefixes declared — addon accepts all IDs
        return true
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
            detail = context.getString(com.nuvio.tv.R.string.meta_error_detail_no_metadata_for_id)
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
    
    /**
     * Parses the max-age directive from a Cache-Control header value.
     * Returns the TTL in milliseconds, or [DEFAULT_TTL_MS] if the header is
     * missing or malformed. Applies [MIN_META_TTL_MS] as a floor so that
     * addons responding with no-cache/no-store/max-age=0 still get a short
     * grace period, preventing re-fetches on every details screen visit.
     */
    private fun parseMaxAgeMs(cacheControl: String?): Long {
        if (cacheControl == null) return DEFAULT_TTL_MS
        val parsed = CacheControl.parse(okhttp3.Headers.headersOf("Cache-Control", cacheControl))
        if (parsed.noStore || parsed.noCache) return MIN_META_TTL_MS
        val maxAgeSec = parsed.maxAgeSeconds
        val ttlMs = if (maxAgeSec >= 0) maxAgeSec * 1000L else DEFAULT_TTL_MS
        return maxOf(ttlMs, MIN_META_TTL_MS)
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
