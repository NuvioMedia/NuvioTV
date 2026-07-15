package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedItemsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_items_preferences"
        private const val TAG = "WatchedItemsPrefs"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val watchedItemsKey = stringSetPreferencesKey("watched_items")
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_watched_push_ms")
    private val deltaCursorKey = longPreferencesKey("watched_items_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watched_items_delta_initialized")

    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    suspend fun setLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[lastSuccessfulPushMsKey] = timestampMs
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value): Boolean {
        val prefs = store(profileId).data.first()
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    internal val allItems: Flow<List<WatchedItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val raw = preferences[watchedItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }
        }.flowOn(Dispatchers.Default)
    }

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return allItems.map { items ->
            items.any { item ->
                item.contentId == contentId &&
                    item.season == season &&
                    item.episode == episode
            }
        }
    }

    fun getWatchedEpisodesForContent(contentId: String): Flow<Set<Pair<Int, Int>>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }
    }

    fun getWatchedEpisodesWithTimestamps(contentId: String): Flow<Map<Pair<Int, Int>, Long>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associate { (it.season!! to it.episode!!) to it.watchedAt }
        }
    }

    suspend fun markAsWatched(
        item: WatchedItem,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    existing.contentId == item.contentId &&
                        existing.season == item.season &&
                        existing.episode == item.episode
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet() + gson.toJson(item)
        }
    }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val newKeys = items.map { Triple(it.contentId, it.season, it.episode) }.toSet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    Triple(existing.contentId, existing.season, existing.episode) in newKeys
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet() + items.map { gson.toJson(it) }
        }
    }

    suspend fun unmarkAsWatched(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    existing.contentId == contentId &&
                        existing.season == season &&
                        existing.episode == episode
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        val removeKeys = episodes.map { (s, e) -> Triple(contentId, s, e) }.toSet()
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    Triple(existing.contentId, existing.season, existing.episode) in removeKeys
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<WatchedItem> {
        return allItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val localItems = current.mapNotNull { json ->
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }
            val localKeys = localItems.map { Triple(it.contentId, it.season, it.episode) }.toSet()

            val newItems = remoteItems.filter { remote ->
                Triple(remote.contentId, remote.season, remote.episode) !in localKeys
            }

            if (newItems.isNotEmpty()) {
                preferences[watchedItemsKey] = current + newItems.map { gson.toJson(it) }.toSet()
            }
        }
    }

    suspend fun applyRemoteChanges(
        upserts: List<WatchedItem>,
        deletes: List<Triple<String, Int?, Int?>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return
        }
        var beforeCount = 0
        var afterCount = 0
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            beforeCount = current.size
            val itemsByKey = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            current.mapNotNull { json ->
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }.forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item
            }
            deletes.forEach { key ->
                itemsByKey.remove(key)
            }
            upserts.forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item
            }
            preferences[watchedItemsKey] = itemsByKey.values
                .map { gson.toJson(it) }
                .toSet()
            afterCount = itemsByKey.size
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size}")
    }

    suspend fun replaceWithRemoteItems(
        remoteItems: List<WatchedItem>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        var preservedLocalItems = false
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId current=${current.size} remote=${remoteItems.size} lastPush=$lastSuccessfulPushMs")
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteItems: remote list empty while local has ${current.size} entries; preserving local watched items")
                return@edit
            }
            val deduped = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            remoteItems.forEach { item ->
                deduped[Triple(item.contentId, item.season, item.episode)] = item
            }
            // Preserve local items that were marked as watched after the last
            // successful push - they haven't reached remote yet, so their
            // absence doesn't mean deletion on another device.
            if (lastSuccessfulPushMs > 0L) {
                val localItems = current.mapNotNull { json ->
                    runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
                }
                localItems.forEach { localItem ->
                    val key = Triple(localItem.contentId, localItem.season, localItem.episode)
                    if (key !in deduped && localItem.watchedAt > lastSuccessfulPushMs) {
                        deduped[key] = localItem
                        preservedLocalItems = true
                        Log.d(TAG, "replaceWithRemoteItems: preserved local item ${localItem.contentId} s${localItem.season}e${localItem.episode} (watchedAt=${localItem.watchedAt} > lastPush=$lastSuccessfulPushMs)")
                    }
                }
            }
            preferences[watchedItemsKey] = deduped.values
                .map { gson.toJson(it) }
                .toSet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId stored=${deduped.size} preservedLocal=$preservedLocalItems")
        }
        return preservedLocalItems
    }

    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            preferences.remove(watchedItemsKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }
}
