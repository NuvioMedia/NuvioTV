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
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val TIMESTAMPS_KEY = stringPreferencesKey("validated_timestamps")
        private const val VALIDATION_TTL_MS = 12L * 60 * 60 * 1000 // 12 hours
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    /** Per-series validation timestamps (contentId → epochMs). */
    @Volatile
    private var validatedAtMap: Map<String, Long> = emptyMap()
    private var loaded = false

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    fun loadFromDisk() {
        if (loaded) return
        scope.launch {
            val prefs = store().data.first()
            val persisted = prefs[KEY] ?: emptySet()
            validatedAtMap = parseTimestamps(prefs[TIMESTAMPS_KEY])
            if (_fullyWatchedSeriesIds.value.isEmpty() && persisted.isNotEmpty()) {
                _fullyWatchedSeriesIds.value = persisted
            }
            loaded = true
        }
    }

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        scope.launch {
            store().edit { prefs -> prefs[KEY] = ids }
        }
    }

    /**
     * Update badge IDs and mark the given series as freshly validated.
     */
    fun updateWithValidation(ids: Set<String>, validatedIds: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val updated = validatedAtMap.toMutableMap()
        validatedIds.forEach { updated[it] = now }
        // Prune entries no longer in the badge set.
        updated.keys.retainAll(ids)
        validatedAtMap = updated
        scope.launch {
            store().edit { prefs ->
                prefs[KEY] = ids
                prefs[TIMESTAMPS_KEY] = gson.toJson(updated)
            }
        }
    }

    /**
     * Returns true if the given series was validated within the TTL window.
     */
    fun isSeriesValidationFresh(contentId: String): Boolean {
        val ts = validatedAtMap[contentId] ?: return false
        return System.currentTimeMillis() - ts < VALIDATION_TTL_MS
    }

    /**
     * Filters the given set to only those series that need re-validation
     * (not validated within TTL).
     */
    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val ts = validatedAtMap[id] ?: return@filter true
            now - ts >= VALIDATION_TTL_MS
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
