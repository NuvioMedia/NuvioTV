package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared holder for fully-watched series IDs derived from the CW pipeline.
 * Updated by HomeViewModel, observed by any screen that needs series watched badges.
 * Persisted per profile so badges appear instantly on cold start.
 *
 * Uses per-series revalidation deadlines instead of a global TTL:
 * - Default: 7 days after last validation
 * - If a known upcoming season release date exists, revalidate at that date
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val REVALIDATE_KEY = stringPreferencesKey("revalidate_after")
        private const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    /** Per-series revalidation deadline (contentId → epochMs when re-check is needed). */
    @Volatile
    private var revalidateAfterMap: Map<String, Long> = emptyMap()
    private var loaded = false

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun loadFromDisk() {
        if (loaded) return
        val prefs = store().data.first()
        val persisted = prefs[KEY] ?: emptySet()
        revalidateAfterMap = parseTimestamps(prefs[REVALIDATE_KEY])
        if (_fullyWatchedSeriesIds.value.isEmpty() && persisted.isNotEmpty()) {
            _fullyWatchedSeriesIds.value = persisted
        }
        loaded = true
    }

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        scope.launch {
            store().edit { prefs -> prefs[KEY] = ids }
        }
    }

    /**
     * Update badge IDs and mark the given series as freshly validated.
     * [revalidateAt] allows setting per-series deadlines (e.g. upcoming season premiere).
     * Series not in [revalidateAt] get the default 7-day TTL.
     */
    @Synchronized
    fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap()
    ) {
        val idsChanged = _fullyWatchedSeriesIds.value != ids
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val defaultDeadline = now + DEFAULT_TTL_MS
        val updated = revalidateAfterMap.toMutableMap()
        var deadlinesChanged = false
        validatedIds.forEach { id ->
            val newDeadline = revalidateAt[id] ?: defaultDeadline
            if (updated[id] != newDeadline) {
                updated[id] = newDeadline
                deadlinesChanged = true
            }
        }
        // Prune entries for series no longer in badge set AND not freshly validated.
        // Skip pruning when only adding deadlines (validatedIds not in ids) to avoid
        // removing deadlines set by earlier calls in the same async inject loop.
        if (idsChanged) {
            val keysToRetain = ids + validatedIds
            val sizeBefore = updated.size
            updated.keys.retainAll(keysToRetain)
            if (updated.size != sizeBefore) deadlinesChanged = true
        }

        revalidateAfterMap = updated
        if (idsChanged || deadlinesChanged) {
            scope.launch {
                store().edit { prefs ->
                    prefs[KEY] = ids
                    prefs[REVALIDATE_KEY] = gson.toJson(updated)
                }
            }
        }
    }

    /**
     * Returns true if the given series does not yet need re-validation.
     */
    fun isSeriesValidationFresh(contentId: String): Boolean {
        val deadline = revalidateAfterMap[contentId] ?: return false
        return System.currentTimeMillis() < deadline
    }

    /**
     * Returns true if the series has ever been validated (has an entry in the map).
     */
    fun hasBeenValidated(contentId: String): Boolean {
        return contentId in revalidateAfterMap
    }

    /**
     * Filters the given set to only those series that need re-validation
     * (past their revalidation deadline or never validated).
     */
    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val deadline = revalidateAfterMap[id] ?: return@filter true
            now >= deadline
        }.toSet()
    }

    private fun parseTimestamps(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson<Map<String, Long>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
