package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LocalScraperResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_CACHE_SIZE = 500

@Singleton
class StreamCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) {

    companion object {
        private const val FEATURE = "stream_cache"
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listType =
        Types.newParameterizedType(List::class.java, LocalScraperResult::class.java)

    private val adapter = moshi.adapter<List<LocalScraperResult>>(listType)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun trySave(
        cacheKey: String,
        results: List<LocalScraperResult>,
    ) {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.streamCacheEnabled) return

        val ttl = settings.streamCacheHours * 60 * 60 * 1000L

        val payload = JSONObject().apply {
            put("cachedAt", System.currentTimeMillis())
            put("results", adapter.toJson(results))
        }.toString()

        val key = prefKey(cacheKey)

        store().edit { prefs ->
            prefs[key] = payload
        }

        cleanupExpired(ttl)
        enforceMaxSize(MAX_CACHE_SIZE)
    }

    suspend fun getValidIfEnabled(
        cacheKey: String
    ): List<LocalScraperResult>? {

        val settings = playerSettingsDataStore.playerSettings.first()

        if (settings.streamCacheEnabled) {
            val ttl = settings.streamCacheHours * 60 * 60 * 1000L
            val stored = getValid(cacheKey, ttl)

            if (stored != null) {
                return stored
            }
        }

        return null
    }

    suspend fun clear(cacheKey: String) {
        store().edit {
            it.remove(prefKey(cacheKey))
        }
    }

    suspend fun cleanupExpired(maxAgeMs: Long) {

        val now = System.currentTimeMillis()

        store().edit { prefs ->

            prefs.asMap().forEach { (key, value) ->

                if (!key.name.startsWith("stream_cache_")) return@forEach

                runCatching {

                    val json = JSONObject(value as String)
                    val cachedAt = json.optLong("cachedAt", 0L)

                    if (cachedAt <= 0L || now - cachedAt > maxAgeMs) {
                        prefs.remove(key)
                    }

                }.onFailure {
                    prefs.remove(key)
                }
            }
        }
    }

    suspend fun enforceMaxSize(maxEntries: Int) {

        val prefs = store().data.first()

        val entries = prefs.asMap()
            .filterKeys { it.name.startsWith("stream_cache_") }
            .mapNotNull { (key, value) ->

                runCatching {
                    val json = JSONObject(value as String)
                    val cachedAt = json.optLong("cachedAt", 0L)
                    key to cachedAt
                }.getOrNull()

            }
            .sortedBy { it.second }

        if (entries.size <= maxEntries) return

        val removeCount = entries.size - maxEntries
        val toRemove = entries.take(removeCount)

        store().edit { prefsEdit ->
            toRemove.forEach { (key, _) ->
                prefsEdit.remove(key)
            }
        }
    }

    suspend fun clearAll() {
        store().edit { prefs ->
            prefs.asMap()
                .filterKeys { it.name.startsWith("stream_cache_") }
                .forEach { (key, _) ->
                    prefs.remove(key)
                }
        }
    }

    private suspend fun getValid(
        cacheKey: String,
        ttl: Long
    ): List<LocalScraperResult>? {

        val key = prefKey(cacheKey)
        val raw = store().data.first()[key] ?: return null

        val parsed = runCatching {

            val json = JSONObject(raw)

            val cachedAt = json.optLong("cachedAt", 0L)
            val age = System.currentTimeMillis() - cachedAt

            if (cachedAt <= 0L || age > ttl) return@runCatching null

            val resultsJson = json.optString("results")
            adapter.fromJson(resultsJson)

        }.getOrNull()

        if (parsed == null) {
            store().edit { it.remove(key) }
        }

        return parsed
    }

    private fun prefKey(contentKey: String): Preferences.Key<String> {

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return stringPreferencesKey("stream_cache_$digest")
    }
}