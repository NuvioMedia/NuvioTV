package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchProgressPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "WatchProgressPrefs"
        private const val FEATURE = "watch_progress_preferences"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val watchProgressKey = stringPreferencesKey("watch_progress_map")

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     */
    val allProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val allItems = parseProgressMap(json)

            // Group all entries by contentId and pick the most recently watched.
            // When lastWatched is equal (e.g. batch mark-as-watched), prefer the highest season/episode.
            val latestByContent = allItems.values
                .groupBy { it.contentId }
                .mapValues { (_, items) ->
                    items.maxWithOrNull(
                        compareBy<WatchProgress> { it.lastWatched }
                            .thenBy { it.season ?: 0 }
                            .thenBy { it.episode ?: 0 }
                    )
                }
                .values
                .filterNotNull()

            val result = latestByContent.sortedByDescending { it.lastWatched }
            result
        }
    }

    val allRawProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            parseProgressMap(json)
                .values
                .sortedByDescending { it.lastWatched }
        }
    }

    /**
     * Get items that are in progress (not completed)
     */
    val continueWatching: Flow<List<WatchProgress>> = allProgress.map { list ->
        list.filter { it.isInProgress() }
    }

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String): Flow<WatchProgress?> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            // Try direct key first (movies), then find latest episode entry (series).
            map[contentId] ?: map.values
                .filter { it.contentId == contentId }
                .maxByOrNull { it.lastWatched }
        }
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map.values.find { 
                it.contentId == contentId && it.season == season && it.episode == episode 
            }
        }
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return store().data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }
    }

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress) {
        store().edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            upsertProgressEntries(map, listOf(progress))

            val pruned = pruneOldItems(map)
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    suspend fun saveProgressBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        store().edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            upsertProgressEntries(map, progressList)
            val pruned = pruneOldItems(map)
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null) {
        store().edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()

            val beforeSize = map.size
            Log.d(
                TAG,
                "removeProgress start contentId=$contentId season=$season episode=$episode entriesBefore=$beforeSize"
            )

            if (season != null && episode != null) {
                // Remove specific episode progress + the series-level entry
                // so the item disappears from continue watching
                val key = "${contentId}_s${season}e${episode}"
                map.remove(key)
                map.remove(contentId)
                Log.d(TAG, "removeProgress episodeKey=$key existsAfter=${map.containsKey(key)}")
            } else {
                // Remove all progress for this content
                val keysToRemove = map.keys.filter { key ->
                    key == contentId || key.startsWith("${contentId}_s")
                }
                Log.d(TAG, "removeProgress removingKeys=${keysToRemove.joinToString()}")
                keysToRemove.forEach { map.remove(it) }
            }

            Log.d(TAG, "removeProgress complete contentId=$contentId entriesAfter=${map.size}")
            preferences[watchProgressKey] = gson.toJson(map)
        }
    }

    /**
     * Remove watch progress for multiple episodes in a single DataStore transaction.
     */
    suspend fun removeProgressBatch(contentId: String, episodes: List<Pair<Int, Int>>) {
        if (episodes.isEmpty()) return
        store().edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            for ((season, episode) in episodes) {
                map.remove("${contentId}_s${season}e${episode}")
            }
            map.remove(contentId)
            Log.d(TAG, "removeProgressBatch contentId=$contentId removed=${episodes.size} episodes entriesAfter=${map.size}")
            preferences[watchProgressKey] = gson.toJson(map)
        }
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress) {
        // If the incoming duration is a dummy sentinel (≤ 1ms), check for an
        // existing local entry with a real duration from prior playback.
        // This creates a proper completed entry that syncs correctly cross-device.
        val effectiveDuration = if (progress.duration <= 1L) {
            val key = createKey(progress)
            val existing = getAllRawEntries()[key]
            existing?.duration?.takeIf { it > 1L } ?: progress.duration
        } else {
            progress.duration
        }

        val completedProgress = progress.copy(
            position = effectiveDuration,
            duration = effectiveDuration,
            lastWatched = System.currentTimeMillis()
        )
        saveProgress(completedProgress)
    }

    /**
     * Mark multiple items as completed in a single DataStore transaction.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val rawEntries = getAllRawEntries()
        val now = System.currentTimeMillis()
        val completed = progressList.map { progress ->
            val effectiveDuration = if (progress.duration <= 1L) {
                val key = createKey(progress)
                rawEntries[key]?.duration?.takeIf { it > 1L } ?: progress.duration
            } else {
                progress.duration
            }
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = now
            )
        }
        saveProgressBatch(completed)
    }

    /**
     * Returns the raw key→WatchProgress map from DataStore (for sync push).
     */
    suspend fun getAllRawEntries(): Map<String, WatchProgress> {
        val preferences = store().data.first()
        val json = preferences[watchProgressKey] ?: "{}"
        return parseProgressMap(json)
    }

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     */
    suspend fun mergeRemoteEntries(remoteEntries: Map<String, WatchProgress>) {
        Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${remoteEntries.size} remote entries")
        store().edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val local = parseProgressMap(json).toMutableMap()
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${local.size} existing local entries")

            // Remove local entries that no longer exist on remote
            if (remoteEntries.isNotEmpty()) {
                val removedKeys = local.keys - remoteEntries.keys
                removedKeys.forEach { key ->
                    local.remove(key)
                    Log.d("WatchProgressPrefs", "  removed key=$key (not in remote)")
                }
            }

            for ((key, remote) in remoteEntries) {
                val existing = local[key]
                if (existing == null || remote.lastWatched > existing.lastWatched) {
                    local[key] = mergeDisplayMetadata(remote, existing)
                    Log.d("WatchProgressPrefs", "  merged key=$key (existing=${existing != null})")
                } else {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (local is newer)")
                }
            }

            val pruned = pruneOldItems(local)
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    suspend fun replaceWithRemoteEntries(remoteEntries: Map<String, WatchProgress>) {
        Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${remoteEntries.size} remote entries")
        store().edit { preferences ->
            val currentJson = preferences[watchProgressKey] ?: "{}"
            val current = parseProgressMap(currentJson)
            if (remoteEntries.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteEntries: remote empty while local has ${current.size} entries; preserving local watch progress")
                return@edit
            }
            val merged = remoteEntries.mapValues { (key, remote) ->
                mergeDisplayMetadata(remote, current[key])
            }.toMutableMap()
            val pruned = pruneOldItems(merged)
            Log.d("WatchProgressPrefs", "replaceWithRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Clear all watch progress
     */
    suspend fun clearAll() {
        store().edit { preferences ->
            preferences.remove(watchProgressKey)
        }
    }

    private fun createKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun upsertProgressEntries(
        map: MutableMap<String, WatchProgress>,
        progressList: List<WatchProgress>
    ) {
        progressList.forEach { progress ->
            val key = createKey(progress)
            map[key] = progress

            // Remove legacy series-level mirror key if this is an episode entry.
            // Mirror keys caused race conditions with stale progress data.
            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                if (seriesKey != key && map.containsKey(seriesKey)) {
                    map.remove(seriesKey)
                }
            }
        }
    }

    private fun mergeDisplayMetadata(remote: WatchProgress, existing: WatchProgress?): WatchProgress {
        if (existing == null) return remote
        return remote.copy(
            name = existing.name.takeIf { it.isNotBlank() } ?: remote.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = existing.poster ?: remote.poster,
            backdrop = existing.backdrop ?: remote.backdrop,
            logo = existing.logo ?: remote.logo,
            episodeTitle = existing.episodeTitle ?: remote.episodeTitle,
            addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
        )
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        return try {
            // Parse entry-by-entry so one malformed value doesn't wipe the entire map.
            val root = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            val parsed = mutableMapOf<String, WatchProgress>()
            root.entrySet().forEach { (key, value) ->
                runCatching {
                    parseWatchProgressFromJson(value)
                }.onSuccess { watchProgress ->
                    if (watchProgress != null) parsed[key] = watchProgress
                }.onFailure {
                    Log.w(TAG, "Skipping malformed watch progress entry for key=$key")
                }
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse progress data", e)
            // Backward compatibility with previously stored direct WatchProgress payloads.
            runCatching {
                val fallbackType = object : TypeToken<Map<String, WatchProgress>>() {}.type
                gson.fromJson<Map<String, WatchProgress>>(json, fallbackType) ?: emptyMap()
            }.getOrElse { emptyMap() }
        }
    }

    private fun parseWatchProgressFromJson(value: JsonElement): WatchProgress? {
        val obj = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                runCatching { gson.fromJson(value.asString, JsonObject::class.java) }.getOrNull()
            }
            else -> null
        } ?: return null
        val contentId = obj.getString("contentId", "content_id")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = obj.getString("contentType", "content_type")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = obj.getString("videoId", "video_id")?.takeIf { it.isNotBlank() } ?: contentId
        val lastWatched = obj.getLong("lastWatched", "last_watched") ?: return null

        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = obj.getString("name").orEmpty(),
            poster = obj.getString("poster"),
            backdrop = obj.getString("backdrop"),
            logo = obj.getString("logo"),
            videoId = videoId,
            season = obj.getInt("season"),
            episode = obj.getInt("episode"),
            episodeTitle = obj.getString("episodeTitle", "episode_title"),
            position = obj.getLong("position") ?: 0L,
            duration = obj.getLong("duration") ?: 0L,
            lastWatched = lastWatched,
            addonBaseUrl = obj.getString("addonBaseUrl", "addon_base_url"),
            progressPercent = obj.getFloat("progressPercent", "progress_percent"),
            source = obj.getString("source")?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = obj.getLong("traktPlaybackId", "trakt_playback_id"),
            traktMovieId = obj.getInt("traktMovieId", "trakt_movie_id"),
            traktShowId = obj.getInt("traktShowId", "trakt_show_id"),
            traktEpisodeId = obj.getInt("traktEpisodeId", "trakt_episode_id")
        )
    }

    private fun JsonObject.getString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            return runCatching { value.asString }.getOrNull()
        }
        return null
    }

    private fun JsonObject.getLong(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asLong }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toLong() }.getOrNull()?.let { return it }
            runCatching { value.asString.toLong() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getInt(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asInt }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toInt() }.getOrNull()?.let { return it }
            runCatching { value.asString.toInt() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getFloat(vararg keys: String): Float? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asFloat }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toFloat() }.getOrNull()?.let { return it }
            runCatching { value.asString.toFloat() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun pruneOldItems(map: MutableMap<String, WatchProgress>): Map<String, WatchProgress> {
        return map
    }
}
