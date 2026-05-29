package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import javax.inject.Inject

class AddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: AddonPreferences,
    private val addonSyncService: AddonSyncService,
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context
) : AddonRepository {

    companion object {
        private const val TAG = "AddonRepository"
        private const val MANIFEST_CACHE_PREFS = "addon_manifest_cache"
        private const val MANIFEST_CACHE_KEY = "manifests_v2"
        private const val LEGACY_MANIFEST_CACHE_KEY = "manifests"
        private const val MANIFEST_SUFFIX = "/manifest.json"
        private const val MANIFEST_CACHE_TTL_MS = 6 * 60 * 60 * 1000L 
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    var isSyncingFromRemote = false

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        // Separate path from query string so we can detect /manifest.json
        // even when the URL carries query parameters (e.g. configurable addons).
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    private fun normalizeUrl(url: String): String = canonicalizeUrl(url).lowercase()

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) {
            Log.d(TAG, "triggerRemoteSync: skipped (syncing from remote)")
            return
        }
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "triggerRemoteSync: skipped (not authenticated, state=${authManager.authState.value})")
            return
        }
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            val result = addonSyncService.pushToRemote()
            Log.d(TAG, "triggerRemoteSync: push result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        }
    }

    private val gson = Gson()
    private val manifestCache = mutableMapOf<String, Addon>()
    @Volatile
    private var lastManifestRefreshTime = 0L
    private var manifestRefreshJob: Job? = null

    init {
        syncScope.launch { loadManifestCacheFromDisk() }
    }

    private fun isCacheStale(): Boolean =
        System.currentTimeMillis() - lastManifestRefreshTime > MANIFEST_CACHE_TTL_MS

    private fun scheduleManifestRefresh(urls: List<String>) {
        if (manifestRefreshJob?.isActive == true) return
        manifestRefreshJob = syncScope.launch {
            val refreshed = urls.map { url ->
                async {
                    fetchAddon(url)
                }
            }.awaitAll()
            val anyUpdated = refreshed.any { it is NetworkResult.Success }
            if (anyUpdated) {
                lastManifestRefreshTime = System.currentTimeMillis()
                Log.d(TAG, "Background manifest refresh completed")
            }
        }
    }

    private suspend fun loadManifestCacheFromDisk() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(LEGACY_MANIFEST_CACHE_KEY)) {
                prefs.edit().remove(LEGACY_MANIFEST_CACHE_KEY).apply()
            }
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return@withContext
            val type = object : TypeToken<Map<String, Addon>>() {}.type
            val cached: Map<String, Addon> = gson.fromJson(json, type) ?: return@withContext
            manifestCache.putAll(cached)
            Log.d(TAG, "Loaded ${cached.size} cached manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        syncScope.launch {
            try {
                val snapshot = manifestCache.toMap()
                val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(snapshot)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist manifest cache to disk", e)
            }
        }
    }

    override fun getInstalledAddons(): Flow<List<Addon>> =
        combine(
            preferences.installedAddonUrls,
            preferences.userSetNames,
            preferences.addonEnabledStates
        ) { urls, names, enabledStates -> Triple(urls, names, enabledStates) }
        .flatMapLatest { (urls, userNames, enabledStates) ->
            flow {
                if (urls.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }

                val enabledByUrl = enabledStates.mapKeys { (url, _) -> canonicalizeUrl(url) }
                val cached = urls.mapNotNull { url ->
                    val canonical = canonicalizeUrl(url)
                    val enabled = enabledByUrl[canonical] ?: true
                    manifestCache[canonical]
                        ?.copy(enabled = enabled)
                        ?: if (!enabled) placeholderAddon(canonical, userNames, enabled) else null
                }
                if (cached.isNotEmpty()) {
                    emit(applyDisplayNames(cached, userNames, enabledByUrl))
                }

                val hasCacheMiss = urls.any { url ->
                    val canonical = canonicalizeUrl(url)
                    (enabledByUrl[canonical] ?: true) && manifestCache[canonical] == null
                }
                if (hasCacheMiss) {
                    val fresh = coroutineScope {
                        urls.map { url ->
                            async {
                                val canonical = canonicalizeUrl(url)
                                val enabled = enabledByUrl[canonical] ?: true
                                if (!enabled) {
                                    return@async manifestCache[canonical]
                                        ?.copy(enabled = false)
                                        ?: placeholderAddon(canonical, userNames, enabled = false)
                                }
                                (manifestCache[canonical] ?: when (val result = fetchAddon(url)) {
                                    is NetworkResult.Success -> result.data
                                    else -> null
                                })?.copy(enabled = enabled)
                            }
                        }.awaitAll().filterNotNull()
                    }

                    if (fresh != cached) {
                        emit(applyDisplayNames(fresh, userNames, enabledByUrl))
                    }
                } else if (isCacheStale() && urls.isNotEmpty()) {
                    scheduleManifestRefresh(
                        urls.filter { url -> enabledByUrl[canonicalizeUrl(url)] ?: true }
                    )
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val manifestUrl = "$basePath/manifest.json$baseQuery"

        return when (val result = safeApiCall(context) { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                manifestCache[cleanBaseUrl] = addon
                persistManifestCacheToDisk()
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch addon manifest for url=$manifestUrl code=${result.code} message=${result.message}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addAddon(cleanUrl)
        triggerRemoteSync()
    }

    override suspend fun removeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        manifestCache.remove(cleanUrl)
        preferences.removeAddon(cleanUrl)
        triggerRemoteSync()
    }

    override suspend fun setAddonOrder(urls: List<String>) {
        preferences.setAddonOrder(urls)
        triggerRemoteSync()
    }

    override suspend fun setAddonEnabled(url: String, enabled: Boolean) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.setAddonEnabled(cleanUrl, enabled)
        if (enabled && manifestCache[cleanUrl] == null) {
            fetchAddon(cleanUrl)
        }
        triggerRemoteSync()
    }

    suspend fun reconcileWithRemoteAddonUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true
    ) {
        val normalizedRemote = remoteUrls
            .map { canonicalizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeUrl(it) }
        val remoteSet = normalizedRemote.map { normalizeUrl(it) }.toSet()

        val initialLocalUrls = preferences.installedAddonUrls.first()
        val initialLocalSet = initialLocalUrls.map { normalizeUrl(it) }.toSet()
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalUrls.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteAddonUrls: remote list empty while local has ${initialLocalUrls.size} entries; preserving local addons"
            )
            false
        } else {
            removeMissingLocal
        }

     
        val localByNormalized = linkedMapOf<String, String>()
        initialLocalUrls.forEach { url ->
            localByNormalized.putIfAbsent(normalizeUrl(url), canonicalizeUrl(url))
        }

        val remoteOrdered = normalizedRemote.map { remote ->
            localByNormalized[normalizeUrl(remote)] ?: remote
        }

        val finalList = if (shouldRemoveMissingLocal) {
            remoteOrdered
        } else {
            val extras = initialLocalUrls
                .map { canonicalizeUrl(it) }
                .filter { normalizeUrl(it) !in remoteSet }
            remoteOrdered + extras
        }

        if (shouldRemoveMissingLocal) {
            initialLocalUrls
                .filter { normalizeUrl(it) !in remoteSet }
                .forEach { manifestCache.remove(canonicalizeUrl(it)) }
        }


        val currentCanonical = initialLocalUrls.map { canonicalizeUrl(it) }
        if (finalList != currentCanonical) {
            preferences.setAddonOrder(finalList)
        }
    }

    private fun placeholderAddon(
        url: String,
        userSetNames: Map<String, String>,
        enabled: Boolean
    ): Addon {
        val canonical = canonicalizeUrl(url)
        val displayName = (userSetNames[canonical] ?: userSetNames[url])?.takeIf { it.isNotBlank() }
            ?: canonical.substringBefore("?").substringAfterLast("/").ifBlank { canonical }
        return Addon(
            id = canonical,
            name = displayName,
            displayName = displayName,
            version = "",
            description = null,
            logo = null,
            baseUrl = canonical,
            catalogs = emptyList(),
            types = emptyList(),
            rawTypes = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )
    }

    private fun applyDisplayNames(
        addons: List<Addon>,
        userSetNames: Map<String, String>,
        enabledStates: Map<String, Boolean>
    ): List<Addon> {
        val withUserNames = addons.map { addon ->
            val canonical = canonicalizeUrl(addon.baseUrl)
            val userSetName = userSetNames[canonical] ?: userSetNames[addon.baseUrl]
            val enabled = enabledStates[canonical] ?: addon.enabled
            if (!userSetName.isNullOrBlank() && userSetName != addon.name) {
                addon.copy(displayName = userSetName, enabled = enabled)
            } else {
                addon.copy(enabled = enabled)
            }
        }

        val unrenamed = withUserNames.filter { it.displayName == it.name }
        val nameCounts = mutableMapOf<String, Int>()
        for (addon in unrenamed) {
            nameCounts[addon.name] = (nameCounts[addon.name] ?: 0) + 1
        }

        val nameCounters = mutableMapOf<String, Int>()
        return withUserNames.map { addon ->
            if (addon.displayName != addon.name) {
                addon
            } else if ((nameCounts[addon.name] ?: 0) <= 1) {
                addon
            } else {
                val occurrence = (nameCounters[addon.name] ?: 0) + 1
                nameCounters[addon.name] = occurrence
                if (occurrence == 1) {
                    addon
                } else {
                    addon.copy(displayName = "${addon.name} ($occurrence)")
                }
            }
        }
    }
}
