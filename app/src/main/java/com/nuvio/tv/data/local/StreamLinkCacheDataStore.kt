package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class CachedStreamLink(
    val url: String,
    val streamName: String,
    val headers: Map<String, String>,
    val cachedAtMs: Long,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val bingeGroup: String? = null,
    val contentLanguage: String? = null,
    val year: String? = null
)

@Singleton
class StreamLinkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "stream_link_cache"
        private const val CACHE_LOCK_COUNT = 32
    }

    private val invalidations = StreamLinkCacheInvalidations()
    private val cacheLocks = Array(CACHE_LOCK_COUNT) { Mutex() }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun save(
        contentKey: String,
        url: String,
        streamName: String,
        headers: Map<String, String>?,
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String>? = null,
        bingeGroup: String? = null,
        contentLanguage: String? = null,
        year: String? = null
    ) {
        val invalidationAtStart = invalidations.current(contentKey)
        val payload = JSONObject().apply {
            put("url", url)
            put("streamName", streamName)
            put("cachedAtMs", System.currentTimeMillis())
            put("headers", JSONObject(headers ?: emptyMap<String, String>()))
            put("filename", filename)
            put("videoHash", videoHash)
            videoSize?.let { put("videoSize", it) }
            infoHash?.let { put("infoHash", it) }
            fileIdx?.let { put("fileIdx", it) }
            sources?.let { put("sources", JSONArray(it)) }
            bingeGroup?.let { put("bingeGroup", it) }
            contentLanguage?.let { put("contentLanguage", it) }
            year?.let { put("year", it) }
        }.toString()

        cacheLock(contentKey).withLock {
            store().edit { prefs ->
                prefs[cachePrefKey(contentKey)] = payload
            }
            invalidationAtStart?.let { invalidations.clearIfCurrent(contentKey, it) }
        }
    }

    suspend fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null
        if (invalidations.isInvalidated(contentKey)) return null

        return cacheLock(contentKey).withLock {
            if (invalidations.isInvalidated(contentKey)) return@withLock null

            val key = cachePrefKey(contentKey)
            val raw = store().data.first()[key] ?: return@withLock null

            val parsed = runCatching {
                val json = JSONObject(raw)
                val cachedAtMs = json.optLong("cachedAtMs", 0L)
                val age = System.currentTimeMillis() - cachedAtMs
                if (cachedAtMs <= 0L || age > maxAgeMs) return@runCatching null

                val headersJson = json.optJSONObject("headers")
                val headers = buildMap {
                    headersJson?.keys()?.forEach { headerKey ->
                        put(headerKey, headersJson.optString(headerKey, ""))
                    }
                }.filterValues { it.isNotEmpty() }

                val url = json.optString("url", "")
                val streamName = json.optString("streamName", "")
                val infoHash = json.optString("infoHash", "").ifBlank { null }
                // Accept entry if it has either a real URL (HTTP stream) or an
                // infoHash (torrent stream — URL is re-resolved on each playback).
                if (streamName.isBlank() || (url.isBlank() && infoHash == null)) return@runCatching null

                val sourcesJson = json.optJSONArray("sources")
                val sources = sourcesJson?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotEmpty() }
                    }
                }?.takeIf { it.isNotEmpty() }

                CachedStreamLink(
                    url = url,
                    streamName = streamName,
                    headers = headers,
                    cachedAtMs = cachedAtMs,
                    filename = json.optString("filename", "").ifBlank { null },
                    videoHash = json.optString("videoHash", "").ifBlank { null },
                    videoSize = json.optLong("videoSize", -1L).takeIf { it >= 0L },
                    infoHash = infoHash,
                    fileIdx = if (json.has("fileIdx")) json.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                    sources = sources,
                    bingeGroup = json.optString("bingeGroup", "").ifBlank { null },
                    contentLanguage = json.optString("contentLanguage", "").ifBlank { null },
                    year = json.optString("year", "").ifBlank { null }
                )
            }.getOrNull()

            if (parsed == null) {
                store().edit { mutablePrefs ->
                    mutablePrefs.remove(key)
                }
            }

            parsed
        }
    }

    fun invalidate(contentKey: String): Long = invalidations.invalidate(contentKey)

    suspend fun removeInvalidated(contentKey: String, invalidation: Long) {
        cacheLock(contentKey).withLock {
            if (invalidations.current(contentKey) != invalidation) return@withLock
            store().edit { prefs ->
                prefs.remove(cachePrefKey(contentKey))
            }
        }
    }

    private fun cacheLock(contentKey: String): Mutex {
        val index = (contentKey.hashCode() and Int.MAX_VALUE) % cacheLocks.size
        return cacheLocks[index]
    }

    private fun cachePrefKey(contentKey: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("stream_link_$digest")
    }
}

internal class StreamLinkCacheInvalidations {
    private val sequence = AtomicLong(0L)
    private val entries = ConcurrentHashMap<String, Long>()

    fun invalidate(contentKey: String): Long {
        val token = sequence.incrementAndGet()
        entries[contentKey] = token
        return token
    }

    fun current(contentKey: String): Long? = entries[contentKey]

    fun isInvalidated(contentKey: String): Boolean = entries.containsKey(contentKey)

    fun clearIfCurrent(contentKey: String, token: Long): Boolean =
        entries.remove(contentKey, token)
}
