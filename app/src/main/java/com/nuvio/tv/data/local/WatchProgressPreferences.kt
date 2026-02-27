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

    // Maximum items to keep in continue watching
    private val maxItems = 50
    
    private val maxEpisodesPerContent = 25
   
    private val maxStoredEntries = 300

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     */
    val allProgress: Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val allItems = parseProgressMap(json)

            val contentLevelEntries = allItems.entries
                .filter { (key, progress) -> key == progress.contentId }
                .associate { it.value.contentId to it.value }
                .toMutableMap()

            val latestEpisodeFallbacks = allItems.values
                .groupBy { it.contentId }
                .mapValues { (_, items) -> items.maxByOrNull { it.lastWatched } }

            latestEpisodeFallbacks.forEach { (contentId, latest) ->
                if (contentLevelEntries[contentId] == null && latest != null) {
                    contentLevelEntries[contentId] = latest
                }
            }

            contentLevelEntries.values
                .sortedByDescending { it.lastWatched }
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
            map[contentId]
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
            
            val key = createKey(progress)
            map[key] = progress

            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                val existingSeriesProgress = map[seriesKey]
                
                if (existingSeriesProgress == null || progress.lastWatched > existingSeriesProgress.lastWatched) {
                    map[seriesKey] = progress.copy(videoId = progress.videoId)
                }
            }

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
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress) {
        val completedProgress = progress.copy(
            position = progress.duration,
            lastWatched = System.currentTimeMillis()
        )
        saveProgress(completedProgress)
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
                    local[key] = remote
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
            val pruned = pruneOldItems(remoteEntries.toMutableMap())
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
        if (map.isEmpty()) return map

        val latestByContent = map.values
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.maxOf { it.lastWatched } }

        val inProgressContentIds = map.values
            .asSequence()
            .filter { it.isInProgress() }
            .map { it.contentId }
            .toSet()

        val sortedContentIds = latestByContent
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

        val keepContentIds = buildList {
            sortedContentIds
                .filter { it in inProgressContentIds }
                .forEach { add(it) }
            sortedContentIds
                .filter { it !in inProgressContentIds }
                .forEach { add(it) }
        }
            .distinct()
            .take(maxItems)

        val keepContentIdSet = keepContentIds.toSet()

        val filteredByContent = map.filterValues { it.contentId in keepContentIdSet }
        val boundedByContent = mutableMapOf<String, WatchProgress>()

        keepContentIds.forEach { contentId ->
            val entriesForContent = filteredByContent.filterValues { it.contentId == contentId }

            // Keep canonical content-level record when present.
            entriesForContent[contentId]?.let { boundedByContent[contentId] = it }

            val recentEpisodeEntries = entriesForContent
                .filterKeys { it != contentId }
                .entries
                .sortedByDescending { it.value.lastWatched }
                .take(maxEpisodesPerContent)

            recentEpisodeEntries.forEach { (key, value) ->
                boundedByContent[key] = value
            }
        }

        if (boundedByContent.size <= maxStoredEntries) return boundedByContent

        val pinnedContentKeys = keepContentIds.filter { boundedByContent.containsKey(it) }.toSet()
        val remainingSlots = (maxStoredEntries - pinnedContentKeys.size).coerceAtLeast(0)

        val limited = mutableMapOf<String, WatchProgress>()
        pinnedContentKeys.forEach { key ->
            boundedByContent[key]?.let { limited[key] = it }
        }

        boundedByContent.entries
            .asSequence()
            .filter { (key, _) -> key !in pinnedContentKeys }
            .sortedByDescending { (_, value) -> value.lastWatched }
            .take(remainingSlots)
            .forEach { (key, value) ->
                limited[key] = value
            }

        return limited
    }
}
