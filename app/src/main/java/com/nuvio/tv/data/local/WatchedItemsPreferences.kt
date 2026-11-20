package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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

    // Content ids of single items (no season or episode) marked watched while a tracking provider
    // was the watch progress source, which that provider has not reported yet. Its data alone
    // decides what shows as watched, and it may never know an item, such as an add-on's YouTube
    // video, so these marks are read alongside it until it reports them or they are unmarked.
    private val pendingProviderMarksKey = stringSetPreferencesKey("pending_provider_watched_marks")

    // Decoded items per profile, keyed by their stored JSON. Every collector of observeAllItems
    // decodes the full set on each change; reusing unchanged entries keeps a single write (such as
    // marking an episode watched during playback) from re-parsing the whole history in every
    // collector at once. Values depend only on the key, so a lost update only costs cache hits.
    // Entries that fail to parse are cached as null so they are only parsed once.
    private val decodeCaches = ConcurrentHashMap<Int, AtomicReference<Map<String, WatchedItem?>>>()

    private fun decodeItems(profileId: Int, raw: Set<String>): List<WatchedItem> {
        val cache = decodeCaches.getOrPut(profileId) { AtomicReference(emptyMap()) }
        val previous = cache.get()
        val decoded = HashMap<String, WatchedItem?>(raw.size * 2)
        val items = ArrayList<WatchedItem>(raw.size)
        raw.forEach { json ->
            val item = if (previous.containsKey(json)) {
                previous[json]
            } else {
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }
            decoded[json] = item
            if (item != null) items += item
        }
        cache.set(decoded)
        return items
    }

    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value): Long {
        val prefs = store(profileId).data.first()
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    /**
     * Advances the stored push point, never lowering it. The comparison happens inside
     * the edit, so two pushes finishing out of order cannot leave the older one on disk.
     * Nothing needs to lower it: deleting a profile removes the whole store.
     */
    suspend fun advanceLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { prefs ->
            val stored = prefs[lastSuccessfulPushMsKey] ?: 0L
            prefs[lastSuccessfulPushMsKey] = maxOf(stored, timestampMs)
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
        observeAllItems(pid)
    }

    fun observeAllItems(profileId: Int): Flow<List<WatchedItem>> {
        return store(profileId).data
            .map { preferences -> preferences[watchedItemsKey] ?: emptySet() }
            // The store also holds push and delta bookkeeping; skip those writes.
            .distinctUntilChanged()
            .map { raw -> decodeItems(profileId, raw) }
            .flowOn(Dispatchers.Default)
    }

    /** The active profile's pending provider marks; see [observePendingProviderMarks]. */
    internal val pendingProviderMarks: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        observePendingProviderMarks(pid)
    }

    /**
     * Content ids of single items marked watched while a tracking provider was the source and not
     * reported by it yet, that are still marked here.
     */
    fun observePendingProviderMarks(profileId: Int): Flow<Set<String>> {
        return store(profileId).data
            .map { preferences -> pendingMarksStillWatched(preferences) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    private fun pendingMarksStillWatched(preferences: Preferences): Set<String> {
        val pending = preferences[pendingProviderMarksKey].orEmpty()
        if (pending.isEmpty()) return emptySet()
        val storedKeys = preferences[watchedItemsKey].orEmpty().mapTo(HashSet(), ::extractWatchedItemKey)
        return pending.filterTo(linkedSetOf()) { contentId -> buildWatchedKey(contentId, null, null) in storedKeys }
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

    fun getWatchedEpisodesForContent(
        contentId: String,
        profileId: Int = profileManager.activeProfileId.value
    ): Flow<Set<Pair<Int, Int>>> {
        return observeAllItems(profileId).map { items ->
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

    /**
     * Stores [item]. With [pendingProviderMark], a single item is also kept as a pending provider
     * mark, for a mark made while a tracking provider is the watch progress source.
     */
    suspend fun markAsWatched(
        item: WatchedItem,
        profileId: Int = profileManager.activeProfileId.value,
        pendingProviderMark: Boolean = false
    ) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val itemKey = watchedItemKey(item)
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) == itemKey
            }
            preferences[watchedItemsKey] = filtered.toSet() + gson.toJson(item)
            if (pendingProviderMark && item.season == null && item.episode == null) {
                preferences[pendingProviderMarksKey] =
                    preferences[pendingProviderMarksKey].orEmpty() + item.contentId
            }
        }
    }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (items.isEmpty()) return
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val newKeys = items.map { watchedItemKey(it) }.toSet()
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) in newKeys
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
        val removeKey = buildWatchedKey(contentId, season, episode)
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) == removeKey
            }
            preferences[watchedItemsKey] = filtered.toSet()
            if (season == null && episode == null) {
                val pending = preferences[pendingProviderMarksKey].orEmpty()
                if (contentId in pending) preferences[pendingProviderMarksKey] = pending - contentId
            }
        }
    }

    /** Stops treating [contentIds] as pending once the tracking provider reports them watched. */
    suspend fun acknowledgePendingProviderMarks(
        contentIds: Set<String>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (contentIds.isEmpty()) return
        store(profileId).edit { preferences ->
            val pending = preferences[pendingProviderMarksKey].orEmpty()
            val remaining = pending - contentIds
            if (remaining.size != pending.size) preferences[pendingProviderMarksKey] = remaining
        }
    }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (episodes.isEmpty()) return
        val removeKeys = episodes.map { (s, e) -> buildWatchedKey(contentId, s, e) }.toSet()
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                extractWatchedItemKey(json) in removeKeys
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(profileId: Int = profileManager.activeProfileId.value): List<WatchedItem> {
        val preferences = store(profileId).data.first()
        return decodeItems(profileId, preferences[watchedItemsKey] ?: emptySet())
    }

    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>, profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val localItems = decodeItems(profileId, current)
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
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
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
            decodeItems(profileId, current).forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item
            }
            deletes.forEach { (contentId, season, episode) ->
                val mutationKey = WatchedMutationKey(contentId, season, episode)
                if (mutationKey !in pendingUpsertKeys) {
                    itemsByKey.remove(Triple(contentId, season, episode))
                }
            }
            upserts.forEach { item ->
                val mutationKey = item.mutationKey()
                when {
                    mutationKey in pendingDeleteKeys -> itemsByKey.remove(
                        Triple(item.contentId, item.season, item.episode)
                    )
                    mutationKey !in pendingUpsertKeys -> itemsByKey[
                        Triple(item.contentId, item.season, item.episode)
                    ] = item
                }
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
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
        lastSuccessfulPushMs: Long? = null,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        var preservedLocalItems = false
        store(profileId).edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId current=${current.size} remote=${remoteItems.size}")
            val deduped = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            remoteItems.filterNot { it.mutationKey() in pendingDeleteKeys }.forEach { item ->
                deduped[Triple(item.contentId, item.season, item.episode)] = item
            }
            val localItems = decodeItems(profileId, current)
            localItems.forEach { localItem ->
                val mutationKey = localItem.mutationKey()
                val itemKey = Triple(localItem.contentId, localItem.season, localItem.episode)
                if (ServerItemRef.isServerId(localItem.contentId)) {
                    deduped[itemKey] = localItem
                    return@forEach
                }
                val preservePendingUpsert = mutationKey in pendingUpsertKeys
                val preserveAfterPush = mutationKey !in pendingDeleteKeys &&
                    itemKey !in deduped &&
                    lastSuccessfulPushMs != null &&
                    localItem.watchedAt > lastSuccessfulPushMs
                if (preservePendingUpsert || preserveAfterPush) {
                    deduped[itemKey] = localItem
                    preservedLocalItems = true
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
            preferences.remove(pendingProviderMarksKey)
        }
        decodeCaches.remove(profileId)
    }

    /**
     * [clearAll] for a change of watch progress source, except for the pending provider marks,
     * which no source can bring back.
     */
    suspend fun clearAllKeepingPendingProviderMarks(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            val pending = pendingMarksStillWatched(preferences)
            val keptKeys = pending.mapTo(HashSet()) { contentId -> buildWatchedKey(contentId, null, null) }
            val kept = preferences[watchedItemsKey].orEmpty()
                .filterTo(linkedSetOf()) { json -> extractWatchedItemKey(json) in keptKeys }
            if (kept.isEmpty()) {
                preferences.remove(watchedItemsKey)
                preferences.remove(pendingProviderMarksKey)
            } else {
                preferences[watchedItemsKey] = kept
                preferences[pendingProviderMarksKey] = pending
            }
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
        decodeCaches.remove(profileId)
    }

    private fun watchedItemKey(item: WatchedItem): String =
        buildWatchedKey(item.contentId, item.season, item.episode)

    private fun buildWatchedKey(contentId: String, season: Int?, episode: Int?): String =
        "$contentId|${season ?: "_"}|${episode ?: "_"}"

    /**
     * Extracts a composite key from a raw JSON string without full Gson deserialization.
     * Looks for "contentId", "season", "episode" fields via simple string search.
     * Falls back to full Gson parse only if the fast path fails.
     */
    private fun extractWatchedItemKey(json: String): String {
        val contentId = extractJsonStringField(json, "contentId")
        val season = extractJsonIntField(json, "season")
        val episode = extractJsonIntField(json, "episode")
        if (contentId != null) {
            return buildWatchedKey(contentId, season, episode)
        }
        // Fallback: full deserialization (should rarely happen with well-formed JSON)
        val item = runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            ?: return json // use raw json as unique key for malformed entries
        return watchedItemKey(item)
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val marker = "\"$field\""
        val fieldIdx = json.indexOf(marker)
        if (fieldIdx < 0) return null
        val colonIdx = json.indexOf(':', fieldIdx + marker.length)
        if (colonIdx < 0) return null
        val openQuote = json.indexOf('"', colonIdx + 1)
        if (openQuote < 0) return null
        val closeQuote = json.indexOf('"', openQuote + 1)
        if (closeQuote < 0) return null
        return json.substring(openQuote + 1, closeQuote)
    }

    private fun extractJsonIntField(json: String, field: String): Int? {
        val marker = "\"$field\""
        val fieldIdx = json.indexOf(marker)
        if (fieldIdx < 0) return null
        val colonIdx = json.indexOf(':', fieldIdx + marker.length)
        if (colonIdx < 0) return null
        // Skip whitespace after colon
        var i = colonIdx + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        // Handle null
        if (json.startsWith("null", i)) return null
        // Parse integer
        val start = i
        if (json[i] == '-') i++
        while (i < json.length && json[i].isDigit()) i++
        if (i == start) return null
        return json.substring(start, i).toIntOrNull()
    }
}
