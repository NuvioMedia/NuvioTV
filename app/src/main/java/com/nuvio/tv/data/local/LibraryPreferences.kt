package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.normalizeContentId
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.data.repository.ParsedContentIds
import com.google.gson.Gson
import com.nuvio.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "library_preferences"
        private const val TAG = "LibraryPrefs"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")

    val libraryItems: Flow<List<SavedLibraryItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val raw = preferences[libraryItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
            }
        }
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryItems.map { items ->
            val queryKeys = buildContentKeys(itemId = itemId, itemType = itemType)
            items.any { saved ->
                buildContentKeys(
                    itemId = saved.id,
                    itemType = saved.type,
                    imdbId = saved.imdbId,
                    tmdbId = saved.tmdbId,
                    traktId = saved.traktId
                ).any(queryKeys::contains)
            }
        }
    }

    suspend fun addItem(item: SavedLibraryItem) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val targetKeys = buildContentKeys(
                itemId = item.id,
                itemType = item.type,
                imdbId = item.imdbId,
                tmdbId = item.tmdbId,
                traktId = item.traktId
            )
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    buildContentKeys(
                        itemId = saved.id,
                        itemType = saved.type,
                        imdbId = saved.imdbId,
                        tmdbId = saved.tmdbId,
                        traktId = saved.traktId
                    ).any(targetKeys::contains)
                } ?: false
            }
            val itemWithTimestamp = if (item.addedAt == 0L) item.copy(addedAt = System.currentTimeMillis()) else item
            preferences[libraryItemsKey] = filtered.toSet() + gson.toJson(itemWithTimestamp)
        }
    }

    suspend fun removeItem(itemId: String, itemType: String) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val targetKeys = buildContentKeys(itemId = itemId, itemType = itemType)
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    buildContentKeys(
                        itemId = saved.id,
                        itemType = saved.type,
                        imdbId = saved.imdbId,
                        tmdbId = saved.tmdbId,
                        traktId = saved.traktId
                    ).any(targetKeys::contains)
                } ?: false
            }
            preferences[libraryItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<SavedLibraryItem> {
        return libraryItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<SavedLibraryItem>) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "mergeRemoteItems: remote list empty while local has ${current.size} entries; preserving local library")
                return@edit
            }
            val dedupedRemote = linkedMapOf<Pair<String, String>, SavedLibraryItem>()
            remoteItems.forEach { item ->
                dedupedRemote[item.id to item.type.lowercase()] = item
            }
            preferences[libraryItemsKey] = dedupedRemote.values
                .map { gson.toJson(it) }
                .toSet()
        }
    }

    private fun buildContentKeys(
        itemId: String,
        itemType: String,
        imdbId: String? = null,
        tmdbId: Int? = null,
        traktId: Int? = null
    ): Set<String> {
        val normalizedType = when (itemType.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> itemType.lowercase()
        }
        val parsed = buildSet {
            add(ParsedContentIds(imdb = imdbId, tmdb = tmdbId, trakt = traktId))
            add(
                com.nuvio.tv.data.repository.parseContentIds(itemId)
            )
        }

        return buildSet {
            val trimmedId = itemId.trim()
            if (trimmedId.isNotBlank()) {
                add("$normalizedType:$trimmedId")
            }
            parsed.forEach { ids ->
                val normalizedId = normalizeContentId(toTraktIds(ids))
                if (normalizedId.isNotBlank()) {
                    add("$normalizedType:$normalizedId")
                }
                ids.imdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:$it") }
                ids.tmdb?.let { add("$normalizedType:tmdb:$it") }
                ids.trakt?.let { add("$normalizedType:trakt:$it") }
            }
        }
    }
}
