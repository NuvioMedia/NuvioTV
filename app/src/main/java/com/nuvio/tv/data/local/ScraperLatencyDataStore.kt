package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.plugin.ScraperLatencyPolicy
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device per-provider scraper latency cache.
 * Entries expire after [ScraperLatencyPolicy.TTL_MS] (7 days) and are keyed by scraper id.
 */
@Singleton
class ScraperLatencyDataStore(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val currentTimeMs: () -> Long
) {
    @Inject
    constructor(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ) : this(factory, profileManager, { System.currentTimeMillis() })

    companion object {
        private const val FEATURE = "scraper_latency"
        private val LATENCY_KEY = stringPreferencesKey("scraper_latency")
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun record(scraperId: String, durationMs: Long, success: Boolean = true) {
        val now = currentTimeMs()
        val recordedMs = ScraperLatencyPolicy.recordedDurationMs(durationMs, success)
        store().edit { prefs ->
            val root = prefs[LATENCY_KEY]?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            } ?: JSONObject()

            val existing = root.optJSONObject(scraperId)
            val previousSamples = existing?.optInt("samples", 0) ?: 0
            val previousEma = existing?.optLong("emaMs", recordedMs) ?: recordedMs
            val emaMs = ScraperLatencyPolicy.nextEmaMs(
                previousEmaMs = if (previousSamples <= 0) null else previousEma,
                previousSamples = previousSamples,
                recordedMs = recordedMs
            )

            val expired = mutableListOf<String>()
            root.keys().forEach { key ->
                if (key == scraperId) return@forEach
                val entry = root.optJSONObject(key) ?: return@forEach
                val updatedAt = entry.optLong("updatedAtMs", 0L)
                if (!ScraperLatencyPolicy.isFresh(updatedAt, now)) {
                    expired += key
                }
            }
            expired.forEach { root.remove(it) }

            root.put(scraperId, JSONObject().apply {
                put("emaMs", emaMs)
                put("lastMs", recordedMs)
                put("updatedAtMs", now)
                put("samples", previousSamples + 1)
            })
            prefs[LATENCY_KEY] = root.toString()
        }
    }

    suspend fun snapshot(): Map<String, Long> {
        val now = currentTimeMs()
        val raw = store().data.first()[LATENCY_KEY] ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        root.keys().forEach { key ->
            val entry = root.optJSONObject(key) ?: return@forEach
            val updatedAt = entry.optLong("updatedAtMs", 0L)
            if (ScraperLatencyPolicy.isFresh(updatedAt, now)) {
                result[key] = entry.optLong("emaMs", 0L)
            }
        }
        return result
    }
}