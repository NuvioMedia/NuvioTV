package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CachedStreamLink(
    val url: String,
    val streamName: String,
    val headers: Map<String, String>,
    val cachedAtMs: Long,
    val rememberedAudioLanguage: String? = null,
    val rememberedAudioName: String? = null
)

@Singleton
class StreamLinkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "stream_link_cache"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun save(
        contentKey: String,
        url: String,
        streamName: String,
        headers: Map<String, String>?,
        rememberedAudioLanguage: String? = null,
        rememberedAudioName: String? = null
    ) {
        val payload = JSONObject().apply {
            put("url", url)
            put("streamName", streamName)
            put("cachedAtMs", System.currentTimeMillis())
            put("headers", JSONObject(headers ?: emptyMap<String, String>()))
            put("rememberedAudioLanguage", rememberedAudioLanguage)
            put("rememberedAudioName", rememberedAudioName)
        }.toString()

        store().edit { prefs ->
            prefs[cachePrefKey(contentKey)] = payload
        }
    }

    suspend fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null

        val key = cachePrefKey(contentKey)
        val raw = store().data.first()[key] ?: return null

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
            if (url.isBlank() || streamName.isBlank()) return@runCatching null

            CachedStreamLink(
                url = url,
                streamName = streamName,
                headers = headers,
                cachedAtMs = cachedAtMs,
                rememberedAudioLanguage = json.optString("rememberedAudioLanguage", "").ifBlank { null },
                rememberedAudioName = json.optString("rememberedAudioName", "").ifBlank { null }
            )
        }.getOrNull()

        if (parsed == null) {
            store().edit { mutablePrefs ->
                mutablePrefs.remove(key)
            }
        }

        return parsed
    }

    private fun cachePrefKey(contentKey: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("stream_link_$digest")
    }
}
