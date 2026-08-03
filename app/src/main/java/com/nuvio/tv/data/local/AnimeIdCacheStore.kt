package com.nuvio.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class CachedAnimeTmdbMapping(
    val tmdbId: Int?,
    val expiresAtMs: Long,
    val missCount: Int
)

@Singleton
class AnimeIdCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun get(source: String, id: String, nowMs: Long = System.currentTimeMillis()): CachedAnimeTmdbMapping? {
        val raw = synchronized(lock) { preferences.getString(cacheKey(source, id), null) } ?: return null
        val parsed = runCatching {
            val json = JSONObject(raw)
            CachedAnimeTmdbMapping(
                tmdbId = json.optInt("tmdbId").takeIf { json.has("tmdbId") && !json.isNull("tmdbId") },
                expiresAtMs = json.getLong("expiresAtMs"),
                missCount = json.optInt("missCount", 0)
            )
        }.getOrNull() ?: return null
        return parsed.takeIf { it.expiresAtMs > nowMs }
    }

    fun putSuccess(source: String, id: String, tmdbId: Int, nowMs: Long = System.currentTimeMillis()) {
        put(source, id, tmdbId, nowMs + POSITIVE_TTL_MS, missCount = 0)
    }

    fun putMiss(source: String, id: String, nowMs: Long = System.currentTimeMillis()) {
        val missCount = (readMissCount(source, id) + 1).coerceAtMost(3)
        val ttl = when (missCount) {
            1 -> FIRST_MISS_TTL_MS
            2 -> SECOND_MISS_TTL_MS
            else -> LATER_MISS_TTL_MS
        }
        put(source, id, tmdbId = null, expiresAtMs = nowMs + ttl, missCount = missCount)
    }

    fun putFailure(source: String, id: String, nowMs: Long = System.currentTimeMillis()) {
        put(
            source = source,
            id = id,
            tmdbId = null,
            expiresAtMs = nowMs + FAILURE_TTL_MS,
            missCount = readMissCount(source, id)
        )
    }

    fun clear() {
        synchronized(lock) {
            preferences.edit().clear().apply()
        }
    }

    private fun put(
        source: String,
        id: String,
        tmdbId: Int?,
        expiresAtMs: Long,
        missCount: Int
    ) {
        val json = JSONObject()
            .put("tmdbId", tmdbId ?: JSONObject.NULL)
            .put("expiresAtMs", expiresAtMs)
            .put("missCount", missCount)
        synchronized(lock) {
            preferences.edit().putString(cacheKey(source, id), json.toString()).apply()
        }
    }

    private fun readMissCount(source: String, id: String): Int {
        val raw = synchronized(lock) { preferences.getString(cacheKey(source, id), null) } ?: return 0
        return runCatching { JSONObject(raw).optInt("missCount", 0) }.getOrDefault(0)
    }

    private fun cacheKey(source: String, id: String): String = "$source:$id"

    private companion object {
        const val PREFERENCES_NAME = "anime_tmdb_id_mappings_v2"
        const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val FIRST_MISS_TTL_MS = 6L * 60 * 60 * 1000
        const val SECOND_MISS_TTL_MS = 12L * 60 * 60 * 1000
        const val LATER_MISS_TTL_MS = 24L * 60 * 60 * 1000
        const val FAILURE_TTL_MS = 15L * 60 * 1000
    }
}
