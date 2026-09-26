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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val MAX_META_CACHE_ENTRIES = 32
        private const val MAX_PRIMARY_META_CACHE_ENTRIES = 16
        /**
         * How long a meta lookup waits for the installed addon list to populate.
         *
         * [AddonRepository.getInstalledAddons] is a StateFlow seeded with an empty
         * list, so a cold start reads the seed rather than the addons. Cached
         * manifests make that a few milliseconds. A first-ever launch has nothing
         * cached and waits on live fetches, usually longer than this, so that one
         * start falls back to the requested type and pays the wait as well.
         *
         * Without the wait a lookup resolves against an empty list and reports
         * "no addon supports this" for content that is fetchable a second later.
         * [getMeta] checks its cache first, so a hit never waits.
         */
        private const val INSTALLED_ADDONS_WAIT_MS = 750L
    }

    /**
     * Creates a thread-safe LRU map that evicts oldest entries when [maxSize] is exceeded.
     */
    private fun <K, V> createLruCacheMap(maxSize: Int): MutableMap<K, V> {
        val lru = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxSize
        }
        return java.util.Collections.synchronizedMap(lru)
    }

    /** Internal result type for the deferred meta lookup to distinguish
     *  "fetched meta", "nothing found", and "source addon already provides this data". */
    private sealed class MetaLookupResult {
        data class Found(val meta: Meta) : MetaLookupResult()
        /** Carries what was tried, so every caller can report it. The loop runs in a
         *  shared Deferred, so attempts held in one caller's flow would leave the
         *  others reporting nothing. */
        data class NotFound(
            val attemptedAddonNames: List<String> = emptyList(),
            val failures: List<MetaAttemptFailure> = emptyList(),
            /**
             * Every candidate was attempted and every one of them answered "no such item". Set by
             * the lookup loop, which is the only place that knows how many candidates there were,
             * rather than inferred from [failures] by a caller.
             */
            val allAttemptsMissing: Boolean = false
        ) : MetaLookupResult()
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
    private val metaCache = createLruCacheMap<String, CachedMeta>(MAX_META_CACHE_ENTRIES)
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = createLruCacheMap<String, CachedMeta>(MAX_META_CACHE_ENTRIES)
    private val primaryAddonMetaCache = createLruCacheMap<String, CachedMeta>(MAX_PRIMARY_META_CACHE_ENTRIES)

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate requests
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<MetaLookupResult>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val requestedType = normalizeExternalMetaType(type)

        // Check the exact requested type before resolving the addon so a cache hit
        // skips the cold-start wait below.
        val probeTypes = listOf(requestedType).filter { it.isNotBlank() }
        probeTypes.forEach { probeType ->
            val probeKey = addonMetaCacheKey(addonBaseUrl, probeType, id)
            metaCache[probeKey]?.let { cached ->
                if (!cached.isExpired()) {
                    emit(NetworkResult.Success(cached.meta))
                    return@flow
                }
                metaCache.remove(probeKey)
            }
        }

        // A known addon's declared resource types are authoritative. Keep tv and
        // series separate at this external boundary.
        val addon = findAddonByBaseUrl(addonBaseUrl)
        val advertisedType = addon?.supportedCandidateType(requestedType)
        if (addon != null && advertisedType == null) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found), NetworkResult.META_NOT_FOUND_CODE))
            return@flow
        }
        if (addon == null) {
            Log.w(
                TAG,
                "Addon unresolved (not installed, disabled, or URL not matched), " +
                    "requesting as-is url=$addonBaseUrl type=$requestedType id=$id"
            )
        }
        val effectiveType = advertisedType ?: requestedType

        val cacheKey = addonMetaCacheKey(addonBaseUrl, effectiveType, id)

        emit(NetworkResult.Loading)

        val url = buildMetaUrl(addonBaseUrl, effectiveType, id)
        val deferred = inFlightMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    val response = api.getMeta(url)
                    if (response.isSuccessful) {
                        val metaDto = response.body()?.meta ?: return@async null
                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                        val ttlMs = parseMaxAgeMs(response.headers()["Cache-Control"])
                        val cached = CachedMeta(meta, System.currentTimeMillis() + ttlMs)
                        metaCache[cacheKey] = cached
                        addonMetaCache[metaLookupCacheKey(requestedType, id)] = cached
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
        val cacheKey = metaLookupCacheKey(type, id)
        addonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            addonMetaCache.remove(cacheKey)
        }

        inFlightAddonMeta[cacheKey]?.let { existingDeferred ->
            when (val lookupResult = existingDeferred.await()) {
                is MetaLookupResult.Found -> {
                    emit(NetworkResult.Success(lookupResult.meta))
                    return@flow
                }
                is MetaLookupResult.SourceSufficient -> {
                    emit(NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE))
                    return@flow
                }
                is MetaLookupResult.NotFound -> {
                    // Fall through — the in-flight request failed, try ourselves
                }
            }
        }

        emit(NetworkResult.Loading)

        val addons = installedAddonsOrEmpty()

        val requestedType = normalizeExternalMetaType(type)
        // Try only addons that declare this exact resource type and support the ID.
        val prioritizedCandidates = linkedSetOf<Pair<Addon, String>>()
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType) && addon.supportsMetaId(id)) {
                prioritizedCandidates.add(addon to requestedType)
            }
        }

        if (prioritizedCandidates.isEmpty()) {
            emit(
                NetworkResult.Error(
                    context.getString(R.string.error_meta_no_supported_addon, requestedType),
                    code = NetworkResult.META_NOT_FOUND_CODE
                )
            )
            return@flow
        }

        val deferred = inFlightAddonMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    // Kept here rather than in the enclosing flow's lists: this
                    // Deferred is shared, so only its creator would see those, and
                    // everyone else would report "no addon found" for addons tried.
                    val loopAddonNames = linkedSetOf<String>()
                    val loopFailures = mutableListOf<MetaAttemptFailure>()
                    var attempted = 0
                    var allMissing = true

                    // Normalize source addon URL for comparison so we can detect
                    // when the candidate is the same addon that served the catalog.
                    val normalizedSourceUrl = sourceAddonBaseUrl?.let(::normalizedAddonKey)

                    for ((addon, candidateType) in prioritizedCandidates) {
                        // If this candidate is the same addon that provided the catalog
                        // data for this item, the item already carries its meta —
                        // return immediately without making a request and without
                        // trying further addons.
                        if (normalizedSourceUrl != null) {
                            if (normalizedAddonKey(addon.baseUrl) == normalizedSourceUrl) {
                                Log.d(TAG, "Source addon matched, catalog meta is sufficient addon=${addon.name} type=$candidateType id=$id")
                                return@async MetaLookupResult.SourceSufficient
                            }
                        }

                        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                        Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url")
                        loopAddonNames += addon.displayName
                        attempted++
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
                                loopFailures += buildMissingMetaFailure(addon)
                            } else {
                                loopFailures += MetaAttemptFailure(
                                    addonName = addon.displayName,
                                    kind = if (response.code() == 404) MetaFailureKind.MISSING else MetaFailureKind.REQUEST_FAILED,
                                    detail = response.message() ?: "HTTP ${response.code()}"
                                )
                                if (response.code() != 404) allMissing = false
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Meta fetch failed addonId=${addon.id} type=$candidateType id=$id: ${e.message}")
                            loopFailures += MetaAttemptFailure(
                                addonName = addon.displayName,
                                kind = MetaFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(R.string.network_error_unknown)
                            )
                            allMissing = false
                            /* try next */
                        }
                    }
                    // Comparing against the candidate list makes "every candidate was attempted"
                    // explicit rather than implied by the loop never breaking early.
                    MetaLookupResult.NotFound(
                        attemptedAddonNames = loopAddonNames.toList(),
                        failures = loopFailures,
                        allAttemptsMissing = attempted > 0 &&
                            attempted == prioritizedCandidates.size &&
                            allMissing
                    )
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
                // The loop reports its own attempts. The enclosing lists are only used
                // by the legacy raw-type path, which returns before reaching here.
                // Every addon answering "no such item" is a final answer, not a failure. Flag it
                // so callers can cache the outcome instead of asking again on every focus. Only a
                // request failure stays retryable.
                val allMissing = lookupResult.allAttemptsMissing
                emit(
                    NetworkResult.Error(
                        buildAggregateFailureMessage(
                            type = requestedType,
                            id = id,
                            attemptedAddonNames = lookupResult.attemptedAddonNames,
                            failures = lookupResult.failures
                        ),
                        code = if (allMissing) NetworkResult.META_NOT_FOUND_CODE else null
                    )
                )
            }
        }
    }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = metaLookupCacheKey(type, id)
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                emit(NetworkResult.Success(cached.meta))
                return@flow
            }
            primaryAddonMetaCache.remove(cacheKey)
        }

        emit(NetworkResult.Loading)

        val addons = installedAddonsOrEmpty()
        val requestedType = normalizeExternalMetaType(type)
        val candidate = selectPrimaryMetaCandidate(
            addons = addons,
            requestedType = requestedType
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
        return "$basePath$baseQuery|${normalizeExternalMetaType(type)}:$id"
    }

    /** Normalized addon base URL, so two spellings of the same one compare equal. */
    private fun normalizedAddonKey(baseUrl: String): String =
        splitAddonBaseUrl(baseUrl).let { (basePath, baseQuery) -> "$basePath$baseQuery" }

    /** Keep different external content types in different cache entries. */
    private fun metaLookupCacheKey(type: String, id: String): String =
        "${normalizeExternalMetaType(type)}:$id"

    /**
     * Installed, enabled addons. Waits up to [INSTALLED_ADDONS_WAIT_MS] for a real
     * list instead of taking the StateFlow's empty seed. Returns empty if nothing
     * arrives in time, leaving callers to use the type as given or raise their
     * own no-addon error.
     */
    private suspend fun installedAddonsOrEmpty(): List<Addon> =
        withTimeoutOrNull(INSTALLED_ADDONS_WAIT_MS) {
            addonRepository.getInstalledAddons().filter { it.isNotEmpty() }.firstOrNull()
        }.orEmpty().enabledAddons()

    /** The installed, enabled addon at [baseUrl], if it is still installed. */
    private suspend fun findAddonByBaseUrl(baseUrl: String): Addon? {
        val target = normalizedAddonKey(baseUrl)
        return installedAddonsOrEmpty().firstOrNull { normalizedAddonKey(it.baseUrl) == target }
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        val encodedType = encodePathSegment(normalizeExternalMetaType(type))
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
        return types.any { it.trim().equals(type.trim(), ignoreCase = true) }
    }

    private fun normalizeExternalMetaType(type: String): String = type.trim().lowercase()

    /**
     * Picks a meta type this addon actually advertises, preferring the requested one.
     * Returns null when it supports neither, so a candidate is never built with a type
     * the addon has already rejected.
     */
    private fun Addon.supportedCandidateType(requestedType: String): String? =
        requestedType.takeIf(::supportsMetaType)

    private fun selectPrimaryMetaCandidate(
        addons: List<Addon>,
        requestedType: String
    ): Pair<Addon, String>? {
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                return addon to requestedType
            }
        }
        return null
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

    override fun getCachedMeta(type: String, id: String): Meta? {
        val cacheKey = metaLookupCacheKey(type, id)
        return addonMetaCache[cacheKey]?.takeIf { !it.isExpired() }?.meta
    }
}
