package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private data class PendingProgressUpsert(
    val key: String,
    val progress: WatchProgress
)

private data class PendingWatchedUpsert(
    val key: WatchedMutationKey,
    val item: WatchedItem
)

@Singleton
class WatchStateMutationStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "watch_state_pending_mutations"
    }

    private val gson = Gson()
    private val progressUpsertsKey = stringSetPreferencesKey("progress_upserts")
    private val progressDeletesKey = stringSetPreferencesKey("progress_deletes")
    private val watchedUpsertsKey = stringSetPreferencesKey("watched_upserts")
    private val watchedDeletesKey = stringSetPreferencesKey("watched_deletes")

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    suspend fun queueProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                pending[key] = progress
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - entries.keys
        }
    }

    suspend fun queueProgressDeletes(keys: Collection<String>, profileId: Int) {
        val normalized = keys.map(String::trim).filter(String::isNotEmpty).toSet()
        if (normalized.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]) - normalized
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() + normalized
        }
    }

    suspend fun pendingProgressUpserts(profileId: Int): Map<String, WatchProgress> {
        val preferences = store(profileId).data.first()
        return parseProgressUpserts(preferences[progressUpsertsKey])
    }

    suspend fun pendingProgressDeletes(profileId: Int): Set<String> {
        return store(profileId).data.first()[progressDeletesKey].orEmpty()
    }

    suspend fun acknowledgeProgressUpserts(
        entries: Map<String, WatchProgress>,
        profileId: Int
    ) {
        if (entries.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseProgressUpserts(preferences[progressUpsertsKey]).toMutableMap()
            entries.forEach { (key, progress) ->
                if (pending[key] == progress) pending.remove(key)
            }
            preferences[progressUpsertsKey] = pending.map { (key, progress) ->
                gson.toJson(PendingProgressUpsert(key, progress))
            }.toSet()
        }
    }

    suspend fun acknowledgeProgressDeletes(keys: Collection<String>, profileId: Int) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            preferences[progressDeletesKey] = preferences[progressDeletesKey].orEmpty() - keys.toSet()
        }
    }

    suspend fun queueWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                pending[item.mutationKey()] = item
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            val upsertKeys = items.mapTo(mutableSetOf(), WatchedItem::mutationKey)
            preferences[watchedDeletesKey] = parseWatchedDeletes(preferences[watchedDeletesKey])
                .minus(upsertKeys)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun queueWatchedDeletes(keys: Collection<WatchedMutationKey>, profileId: Int) {
        if (keys.isEmpty()) return
        val normalized = keys.toSet()
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]) - normalized
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
            preferences[watchedDeletesKey] = (parseWatchedDeletes(preferences[watchedDeletesKey]) + normalized)
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun pendingWatchedUpserts(profileId: Int): Map<WatchedMutationKey, WatchedItem> {
        val preferences = store(profileId).data.first()
        return parseWatchedUpserts(preferences[watchedUpsertsKey])
    }

    suspend fun pendingWatchedDeletes(profileId: Int): Set<WatchedMutationKey> {
        return parseWatchedDeletes(store(profileId).data.first()[watchedDeletesKey])
    }

    suspend fun acknowledgeWatchedUpserts(items: Collection<WatchedItem>, profileId: Int) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = parseWatchedUpserts(preferences[watchedUpsertsKey]).toMutableMap()
            items.forEach { item ->
                val key = item.mutationKey()
                if (pending[key] == item) pending.remove(key)
            }
            preferences[watchedUpsertsKey] = pending.map { (key, item) ->
                gson.toJson(PendingWatchedUpsert(key, item))
            }.toSet()
        }
    }

    suspend fun acknowledgeWatchedDeletes(
        keys: Collection<WatchedMutationKey>,
        profileId: Int
    ) {
        if (keys.isEmpty()) return
        store(profileId).edit { preferences ->
            preferences[watchedDeletesKey] = parseWatchedDeletes(preferences[watchedDeletesKey])
                .minus(keys.toSet())
                .map(gson::toJson)
                .toSet()
        }
    }

    suspend fun hasPendingProgressMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[progressUpsertsKey].isNullOrEmpty() ||
            !preferences[progressDeletesKey].isNullOrEmpty()
    }

    suspend fun hasPendingWatchedMutations(profileId: Int): Boolean {
        val preferences = store(profileId).data.first()
        return !preferences[watchedUpsertsKey].isNullOrEmpty() ||
            !preferences[watchedDeletesKey].isNullOrEmpty()
    }

    private fun parseProgressUpserts(raw: Set<String>?): Map<String, WatchProgress> {
        return raw.orEmpty().mapNotNull { json ->
            runCatching { gson.fromJson(json, PendingProgressUpsert::class.java) }.getOrNull()
        }.associate { it.key to it.progress }
    }

    private fun parseWatchedUpserts(raw: Set<String>?): Map<WatchedMutationKey, WatchedItem> {
        return raw.orEmpty().mapNotNull { json ->
            runCatching { gson.fromJson(json, PendingWatchedUpsert::class.java) }.getOrNull()
        }.associate { it.key to it.item }
    }

    private fun parseWatchedDeletes(raw: Set<String>?): Set<WatchedMutationKey> {
        return raw.orEmpty().mapNotNullTo(mutableSetOf()) { json ->
            runCatching { gson.fromJson(json, WatchedMutationKey::class.java) }.getOrNull()
        }
    }
}
