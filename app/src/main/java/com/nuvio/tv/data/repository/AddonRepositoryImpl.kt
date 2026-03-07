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
        private const val MANIFEST_CACHE_KEY = "manifests"
        private const val MANIFEST_SUFFIX = "/manifest.json"
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    var isSyncingFromRemote = false

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            trimmed
        }
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

    init {
        loadManifestCacheFromDisk()
    }

    private fun loadManifestCacheFromDisk() {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return
            val type = object : TypeToken<Map<String, Addon>>() {}.type
            val cached: Map<String, Addon> = gson.fromJson(json, type) ?: return
            manifestCache.putAll(cached)
            Log.d(TAG, "Loaded ${cached.size} cached manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(manifestCache.toMap())).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist manifest cache to disk", e)
        }
    }

    override fun getInstalledAddons(): Flow<List<Addon>> =
        preferences.installedAddonUrls.flatMapLatest { urls ->
            flow {
                // Emit cached addons immediately (now includes disk-persisted cache)
                val cached = urls.mapNotNull { manifestCache[canonicalizeUrl(it)] }
                if (cached.isNotEmpty()) {
                    emit(applyDisplayNames(cached))
                }

                val fresh = coroutineScope {
                    urls.map { url ->
                        async {
                            when (val result = fetchAddon(url)) {
                                is NetworkResult.Success -> result.data
                                else -> manifestCache[canonicalizeUrl(url)]
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

               
                if (fresh != cached || cached.isEmpty()) {
                    emit(applyDisplayNames(fresh))
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val manifestUrl = "$cleanBaseUrl/manifest.json"

        return when (val result = safeApiCall { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                manifestCache[cleanBaseUrl] = addon
                persistManifestCacheToDisk()
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch addon manifest for url=$cleanBaseUrl code=${result.code} message=${result.message}")
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

        if (shouldRemoveMissingLocal) {
            initialLocalUrls
                .filter { normalizeUrl(it) !in remoteSet }
                .forEach { removeAddon(it) }
        }

        normalizedRemote
            .filter { normalizeUrl(it) !in initialLocalSet }
            .forEach { addAddon(it) }

        val currentUrls = preferences.installedAddonUrls.first()
        val currentByNormalizedUrl = linkedMapOf<String, String>()
        currentUrls.forEach { url ->
            currentByNormalizedUrl.putIfAbsent(normalizeUrl(url), canonicalizeUrl(url))
        }
        val remoteOrdered = normalizedRemote
            .mapNotNull { currentByNormalizedUrl[normalizeUrl(it)] }
        val extras = currentUrls
            .map { canonicalizeUrl(it) }
            .filter { normalizeUrl(it) !in remoteSet }

        val reordered = if (shouldRemoveMissingLocal) remoteOrdered else remoteOrdered + extras
        if (reordered != currentUrls.map { canonicalizeUrl(it) }) {
            preferences.setAddonOrder(reordered)
        }
    }

    private fun applyDisplayNames(addons: List<Addon>): List<Addon> {
        val nameCounts = mutableMapOf<String, Int>()
        for (addon in addons) {
            nameCounts[addon.name] = (nameCounts[addon.name] ?: 0) + 1
        }

        val nameCounters = mutableMapOf<String, Int>()
        return addons.map { addon ->
            if ((nameCounts[addon.name] ?: 0) <= 1) {
                addon.copy(displayName = addon.name)
            } else {
                val occurrence = (nameCounters[addon.name] ?: 0) + 1
                nameCounters[addon.name] = occurrence
                if (occurrence == 1) {
                    addon.copy(displayName = addon.name)
                } else {
                    addon.copy(displayName = "${addon.name} ($occurrence)")
                }
            }
        }
    }
}
