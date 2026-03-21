package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "search_history"
        private const val DEFAULT_MAX_RECENT_SEARCHES = 8
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val recentSearchesKey = stringPreferencesKey("recent_searches")

    val recentSearches: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs ->
            parseRecentSearches(prefs[recentSearchesKey])
        }
    }

    suspend fun saveRecentSearch(query: String, maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return

        val current = recentSearches.first()
        val updated = buildList {
            add(normalized)
            addAll(current.filterNot { it.equals(normalized, ignoreCase = true) })
        }.take(maxItems.coerceAtLeast(1))

        store().edit { prefs ->
            prefs[recentSearchesKey] = gson.toJson(updated)
        }
    }

    suspend fun clearRecentSearches() {
        store().edit { prefs ->
            prefs.remove(recentSearchesKey)
        }
    }

    private fun parseRecentSearches(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(raw, type).orEmpty()
            normalizeRecentSearches(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeRecentSearches(items: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        return items.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { value -> seen.add(value.lowercase()) }
            .toList()
    }
}
