package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchProgress
import com.nuvio.tv.domain.model.WatchProgress
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchProgressSyncService"

@Singleton
class WatchProgressSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val profileManager: ProfileManager
) {
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun deleteFromRemote(keys: Collection<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watch progress delete")
                return@withContext Result.success(Unit)
            }

            val distinctKeys = keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (distinctKeys.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_keys", buildJsonArray {
                    distinctKeys.forEach { add(it) }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watch_progress", params)
            }
            Log.d(TAG, "Deleted ${distinctKeys.size} watch progress entries from remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watch progress from remote", e)
            Result.failure(e)
        }
    }

    /**
     * Push all local watch progress to Supabase via RPC.
     * Skips if Trakt is connected (Trakt handles progress when active).
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watch progress push")
                return@withContext Result.success(Unit)
            }

            val rawEntries = watchProgressPreferences.getAllRawEntries()
            val entries = canonicalizeForRemote(rawEntries)
            Log.d(TAG, "pushToRemote: ${rawEntries.size} local entries, ${entries.size} canonical entries to push")
            entries.forEach { (key, progress) ->
                Log.d(TAG, "  push entry: key=$key contentId=${progress.contentId} type=${progress.contentType} pos=${progress.position} dur=${progress.duration} lastWatched=${progress.lastWatched}")
            }

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_entries", buildJsonArray {
                    entries.forEach { (key, progress) ->
                        addJsonObject {
                            put("content_id", progress.contentId)
                            put("content_type", progress.contentType)
                            put("video_id", progress.videoId)
                            progress.season?.let { put("season", it) }
                            progress.episode?.let { put("episode", it) }
                            put("position", progress.position)
                            put("duration", progress.duration)
                            put("last_watched", progress.lastWatched)
                            put("progress_key", key)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watch_progress", params)
            }

            Log.d(TAG, "Pushed ${entries.size} watch progress entries to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watch progress to remote", e)
            Result.failure(e)
        }
    }

    
    suspend fun pushSingleToRemote(key: String, progress: WatchProgress): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping single watch progress push")
                return@withContext Result.success(Unit)
            }

           
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_entries", buildJsonArray {
                    addJsonObject {
                        put("content_id", progress.contentId)
                        put("content_type", progress.contentType)
                        put("video_id", progress.videoId)
                        progress.season?.let { put("season", it) }
                        progress.episode?.let { put("episode", it) }
                        put("position", progress.position)
                        put("duration", progress.duration)
                        put("last_watched", progress.lastWatched)
                        put("progress_key", key)
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watch_progress", params)
            }

            Log.d(TAG, "Pushed single watch progress entry to remote for profile $profileId (key=$key)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push single watch progress to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull watch progress from Supabase via SECURITY DEFINER RPC.
     * Uses get_sync_owner() server-side to fetch the correct user's data,
     * bypassing RLS (which would block linked devices from reading owner data).
     * Skips if Trakt is connected. Caller is responsible for merging into local.
     */
    suspend fun pullFromRemote(): Result<List<Pair<String, WatchProgress>>> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watch progress pull")
                return@withContext Result.success(emptyList())
            }

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_watch_progress", params)
            }
            val remote = response.decodeList<SupabaseWatchProgress>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} entries from Supabase via RPC for profile $profileId")
            remote.forEach { entry ->
                Log.d(TAG, "  pull entry: key=${entry.progressKey} contentId=${entry.contentId} type=${entry.contentType} pos=${entry.position} dur=${entry.duration} lastWatched=${entry.lastWatched}")
            }

            val pulled = remote.map { entry ->
                entry.progressKey to WatchProgress(
                    contentId = entry.contentId,
                    contentType = entry.contentType,
                    name = "",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = entry.videoId,
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = null,
                    position = entry.position,
                    duration = entry.duration,
                    lastWatched = entry.lastWatched,
                    source = WatchProgress.SOURCE_LOCAL
                )
            }

            val normalized = normalizePulledEntries(pulled)
            Log.d(TAG, "pullFromRemote: normalized ${pulled.size} -> ${normalized.size} entries")
            Result.success(normalized)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress from remote", e)
            Result.failure(e)
        }
    }

    private fun canonicalizeForRemote(
        rawEntries: Map<String, WatchProgress>
    ): Map<String, WatchProgress> {
        if (rawEntries.isEmpty()) return rawEntries

        val canonical = rawEntries.toMutableMap()
        rawEntries.forEach { (key, progress) ->
            val isSeriesMirrorKey = key == progress.contentId &&
                isSeriesType(progress.contentType) &&
                progress.season != null &&
                progress.episode != null
            if (!isSeriesMirrorKey) return@forEach

            val season = progress.season
            val episode = progress.episode
            val episodeKey = episodeKey(
                contentId = progress.contentId,
                season = season,
                episode = episode
            )
            val episodeProgress = rawEntries[episodeKey] ?: return@forEach

            val exactMirror = progress.position == episodeProgress.position &&
                progress.duration == episodeProgress.duration &&
                progress.lastWatched == episodeProgress.lastWatched
            val episodeIsAtLeastAsFresh = episodeProgress.lastWatched >= progress.lastWatched - 1_000L

            if (exactMirror || episodeIsAtLeastAsFresh) {
                canonical.remove(key)
            }
        }

        return canonical
    }

    private fun normalizePulledEntries(
        entries: List<Pair<String, WatchProgress>>
    ): List<Pair<String, WatchProgress>> {
        if (entries.isEmpty()) return entries

        val byKey = linkedMapOf<String, WatchProgress>()
        entries.sortedByDescending { it.second.lastWatched }
            .forEach { (key, progress) ->
                val existing = byKey[key]
                if (existing == null || progress.lastWatched > existing.lastWatched) {
                    byKey[key] = progress
                }
            }

        val latestEpisodeByContent = byKey.entries
            .asSequence()
            .mapNotNull { (key, progress) ->
                if (isSeriesType(progress.contentType) &&
                    progress.season != null &&
                    progress.episode != null &&
                    key != progress.contentId
                ) {
                    progress
                } else {
                    null
                }
            }
            .groupBy { it.contentId }
            .mapValues { (_, episodes) -> episodes.maxByOrNull { it.lastWatched } }

        latestEpisodeByContent.forEach { (contentId, latestEpisode) ->
            val latest = latestEpisode ?: return@forEach
            val existingSeriesEntry = byKey[contentId]
            if (existingSeriesEntry == null || existingSeriesEntry.lastWatched < latest.lastWatched) {
                byKey[contentId] = latest
            }
        }

        return byKey.entries
            .sortedByDescending { it.value.lastWatched }
            .map { it.key to it.value }
    }

    private fun episodeKey(contentId: String, season: Int, episode: Int): String {
        return "${contentId}_s${season}e${episode}"
    }

    private fun isSeriesType(contentType: String): Boolean {
        return contentType.lowercase() in setOf("series", "tv")
    }
}
