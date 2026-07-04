package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchProgress
import com.nuvio.tv.data.remote.supabase.SupabaseWatchProgressEvent
import com.nuvio.tv.domain.model.WatchProgress
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchProgressSyncService"
private const val WATCH_PROGRESS_DELTA_PAGE_SIZE = 900
private const val WATCH_PROGRESS_EVENT_UPSERT = "upsert"
private const val WATCH_PROGRESS_EVENT_DELETE = "delete"

data class WatchProgressRemoteSyncResult(
    val upsertedEntries: Int,
    val deletedEntries: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

@Singleton
class WatchProgressSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deltaSyncMutex = Mutex()

    /**
     * Timestamp (epoch ms) of the last successful push to remote.
     * Used by [WatchProgressPreferences.mergeRemoteEntries] to protect local entries
     * that were created after the last push - these haven't reached remote yet,
     * so their absence from a pull response does NOT mean they were deleted on
     * another device.
     */
    @Volatile
    var lastSuccessfulPushMs: Long = 0L
        private set

    /** Called after a successful push to record the sync point. */
    fun markPushSucceeded() {
        val now = System.currentTimeMillis()
        lastSuccessfulPushMs = now
        scope.launch {
            watchProgressPreferences.setLastSuccessfulPushMs(now)
        }
    }

    /** Restores persisted push timestamp on startup. */
    suspend fun restoreLastPushTimestamp() {
        lastSuccessfulPushMs = watchProgressPreferences.getLastSuccessfulPushMs()
    }
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun shouldUseSupabaseWatchProgressSync(): Boolean {
        val hasEffectiveTraktConnection = traktAuthDataStore.isEffectivelyAuthenticated.first()
        val source = traktSettingsDataStore.watchProgressSource.first()
        return !(hasEffectiveTraktConnection && source == WatchProgressSource.TRAKT)
    }

    private suspend fun fetchDeltaCursor(profileId: Int): Long {
        Log.d(TAG, "fetchDeltaCursor: requesting cursor for profile $profileId")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        return withJwtRefreshRetry {
            postgrest.rpc("sync_get_watch_progress_delta_cursor", params).decodeAs<Long>()
        }.also { cursor ->
            Log.d(TAG, "fetchDeltaCursor: cursor=$cursor for profile $profileId")
        }
    }

    private suspend fun pullDeltaPage(profileId: Int, cursor: Long): List<SupabaseWatchProgressEvent> {
        Log.d(TAG, "pullDeltaPage: requesting progress events after cursor $cursor for profile $profileId limit=$WATCH_PROGRESS_DELTA_PAGE_SIZE")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_since_event_id", cursor)
            put("p_limit", WATCH_PROGRESS_DELTA_PAGE_SIZE)
        }
        return withJwtRefreshRetry {
            postgrest.rpc("sync_pull_watch_progress_delta", params).decodeList<SupabaseWatchProgressEvent>()
        }.also { events ->
            val firstEvent = events.firstOrNull()?.eventId
            val lastEvent = events.lastOrNull()?.eventId
            val upserts = events.count { it.operation.equals(WATCH_PROGRESS_EVENT_UPSERT, ignoreCase = true) }
            val deletes = events.count { it.operation.equals(WATCH_PROGRESS_EVENT_DELETE, ignoreCase = true) }
            Log.d(TAG, "pullDeltaPage: received ${events.size} progress events for profile $profileId first=$firstEvent last=$lastEvent upserts=$upserts deletes=$deletes")
        }
    }

    suspend fun deleteFromRemote(
        keys: Collection<String>,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val distinctKeys = keys
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (distinctKeys.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            val params = buildJsonObject {
                put("p_keys", buildJsonArray {
                    distinctKeys.forEach { add(it) }
                })
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
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
     * Always syncs regardless of CW source — both Trakt and Nuvio Sync
     * should have up-to-date progress data.
     *
     * @param profileId The profile to push data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pushToRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)
            val entries = canonicalizeForRemote(rawEntries).filterValues { progress ->
                !(progress.position <= 1L && progress.duration <= 1L && progress.duration > 0L)
            }
            Log.d(TAG, "pushToRemote: ${rawEntries.size} local entries, ${entries.size} canonical entries to push for profile $profileId")
            entries.forEach { (key, progress) ->
                Log.d(TAG, "  push entry: key=$key contentId=${progress.contentId} type=${progress.contentType} pos=${progress.position} dur=${progress.duration} lastWatched=${progress.lastWatched}")
            }

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
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watch_progress", params)
            }

            Log.d(TAG, "Pushed ${entries.size} watch progress entries to remote for profile $profileId")
            markPushSucceeded()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watch progress to remote", e)
            Result.failure(e)
        }
    }

    
    /**
     * Push a single watch progress entry to Supabase via RPC.
     *
     * @param profileId The profile to push data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pushSingleToRemote(
        key: String,
        progress: WatchProgress,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
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
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watch_progress", params)
            }

            Log.d(TAG, "Pushed single watch progress entry to remote for profile $profileId (key=$key)")
            markPushSucceeded()
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
     *
     * @param profileId The profile to pull data for. Captured at call-site to
     *   prevent race conditions when the active profile changes mid-operation.
     */
    suspend fun pullFromRemote(
        profileId: Int = profileManager.activeProfileId.value,
        sinceLastWatched: Long? = null,
        limit: Int? = null
    ): Result<List<Pair<String, WatchProgress>>> = withContext(Dispatchers.IO) {
        try {
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using Trakt watch progress, skipping watch progress pull")
                return@withContext Result.success(emptyList())
            }

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (sinceLastWatched != null) {
                    put("p_since_last_watched", sinceLastWatched)
                }
                if (limit != null) {
                    put("p_limit", limit)
                }
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_watch_progress", params)
            }
            val remote = response.decodeList<SupabaseWatchProgress>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} entries from Supabase via RPC for profile $profileId sinceLastWatched=$sinceLastWatched")
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

    suspend fun syncDeltaFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchProgressRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncDeltaFromRemoteLocked(profileId)
        }
    }

    suspend fun syncSnapshotFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchProgressRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            try {
                if (!shouldUseSupabaseWatchProgressSync()) {
                    Log.d(TAG, "Using Trakt watch progress, skipping watch progress snapshot pull")
                    return@withLock Result.success(WatchProgressRemoteSyncResult(0, 0, usedSnapshot = false, preservedLocalItems = false))
                }
                val cursorBeforeSnapshot = try {
                    fetchDeltaCursor(profileId)
                } catch (e: Exception) {
                    Log.w(TAG, "syncSnapshotFromRemote: delta cursor unavailable, applying snapshot without initialized cursor for profile $profileId", e)
                    null
                }
                val result = pullSnapshotFromRemote(profileId, resetDeltaState = cursorBeforeSnapshot == null)
                if (cursorBeforeSnapshot != null) {
                    watchProgressPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
                }
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull watch progress snapshot from remote", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchProgressRemoteSyncResult> {
        return try {
            val deltaInitialized = watchProgressPreferences.isDeltaInitialized(profileId)
            val deltaCursor = watchProgressPreferences.getDeltaCursor(profileId)
            val localCount = watchProgressPreferences.getAllRawEntries(profileId).size
            Log.d(
                TAG,
                "syncDeltaFromRemote: start profile=$profileId localCount=$localCount deltaInitialized=$deltaInitialized cursor=$deltaCursor lastPush=$lastSuccessfulPushMs"
            )
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using Trakt watch progress, skipping watch progress delta pull")
                return Result.success(WatchProgressRemoteSyncResult(0, 0, usedSnapshot = false, preservedLocalItems = false))
            }

            if (!deltaInitialized) {
                Log.d(TAG, "syncDeltaFromRemote: delta not initialized, taking one watch progress snapshot for profile $profileId")
                val cursorBeforeSnapshot = try {
                    fetchDeltaCursor(profileId)
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: delta cursor unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true)
                    return Result.success(fallbackResult)
                }
                val remoteEntries = pullFromRemote(profileId).getOrElse { throw it }
                val hadUnsyncedProgress = watchProgressPreferences.mergeRemoteEntries(
                    remoteEntries.toMap(),
                    lastSuccessfulPushMs = lastSuccessfulPushMs,
                    profileId = profileId
                )
                watchProgressPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
                val finalLocalCount = watchProgressPreferences.getAllRawEntries(profileId).size
                Log.d(TAG, "syncDeltaFromRemote: initialized cursor $cursorBeforeSnapshot with ${remoteEntries.size} snapshot entries for profile $profileId finalLocalCount=$finalLocalCount preservedLocal=$hadUnsyncedProgress")
                return Result.success(
                    WatchProgressRemoteSyncResult(
                        upsertedEntries = remoteEntries.size,
                        deletedEntries = 0,
                        usedSnapshot = true,
                        preservedLocalItems = hadUnsyncedProgress
                    )
                )
            }

            var cursor = deltaCursor
            var totalUpserts = 0
            var totalDeletes = 0
            var preservedLocalItems = false
            var page = 1

            while (true) {
                Log.d(TAG, "syncDeltaFromRemote: pulling progress delta page $page from cursor $cursor for profile $profileId")
                val events = try {
                    pullDeltaPage(profileId, cursor)
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: delta pull unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true)
                    return Result.success(fallbackResult)
                }
                if (events.isEmpty()) {
                    Log.d(TAG, "syncDeltaFromRemote: no watch progress delta events for profile $profileId at cursor $cursor")
                    break
                }

                val pageChanges = linkedMapOf<String, WatchProgress?>()
                events.forEach { event ->
                    if (event.operation.equals(WATCH_PROGRESS_EVENT_DELETE, ignoreCase = true)) {
                        pageChanges[event.progressKey] = null
                    } else if (event.operation.equals(WATCH_PROGRESS_EVENT_UPSERT, ignoreCase = true)) {
                        // Don't synthesize series-level mirror keys from individual delta events.
                        // normalizePulledEntries creates a series key (contentId) from any episode
                        // key (contentId_s1e5), which would resurrect series entries that were
                        // explicitly deleted remotely. Only apply normalization on full snapshots.
                        val progress = event.toWatchProgress()
                        pageChanges[event.progressKey] = progress
                    }
                }

                val upserts = pageChanges.mapNotNull { (key, progress) ->
                    progress?.let { key to it }
                }.toMap()
                val deletes = pageChanges.filterValues { it == null }.keys
                val pagePreservedLocal = watchProgressPreferences.applyRemoteChanges(
                    upserts = upserts,
                    deletes = deletes,
                    lastSuccessfulPushMs = lastSuccessfulPushMs,
                    profileId = profileId
                )
                preservedLocalItems = preservedLocalItems || pagePreservedLocal
                cursor = maxOf(cursor, events.maxOf { it.eventId })
                watchProgressPreferences.setDeltaState(cursor, initialized = true, profileId = profileId)
                totalUpserts += upserts.size
                totalDeletes += deletes.size
                Log.d(TAG, "syncDeltaFromRemote: applied page $page for profile $profileId newCursor=$cursor pageUpserts=${upserts.size} pageDeletes=${deletes.size}")

                if (events.size < WATCH_PROGRESS_DELTA_PAGE_SIZE) break
                page++
            }

            val finalLocalCount = watchProgressPreferences.getAllRawEntries(profileId).size
            Log.d(TAG, "syncDeltaFromRemote: finished profile=$profileId appliedUpserts=$totalUpserts appliedDeletes=$totalDeletes cursor=$cursor finalLocalCount=$finalLocalCount preservedLocal=$preservedLocalItems")
            Result.success(
                WatchProgressRemoteSyncResult(
                    upsertedEntries = totalUpserts,
                    deletedEntries = totalDeletes,
                    usedSnapshot = false,
                    preservedLocalItems = preservedLocalItems
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress delta from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun pullSnapshotFromRemote(
        profileId: Int,
        resetDeltaState: Boolean
    ): WatchProgressRemoteSyncResult {
        val remoteEntries = pullFromRemote(profileId).getOrElse { throw it }
        val hadUnsyncedProgress = watchProgressPreferences.mergeRemoteEntries(
            remoteEntries.toMap(),
            lastSuccessfulPushMs = lastSuccessfulPushMs,
            profileId = profileId
        )
        if (resetDeltaState) {
            watchProgressPreferences.setDeltaState(0L, initialized = false, profileId = profileId)
        }
        val finalLocalCount = watchProgressPreferences.getAllRawEntries(profileId).size
        Log.d(TAG, "pullSnapshotFromRemote: applied ${remoteEntries.size} snapshot entries for profile $profileId finalLocalCount=$finalLocalCount preservedLocal=$hadUnsyncedProgress resetDeltaState=$resetDeltaState")
        return WatchProgressRemoteSyncResult(
            upsertedEntries = remoteEntries.size,
            deletedEntries = 0,
            usedSnapshot = true,
            preservedLocalItems = hadUnsyncedProgress
        )
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
            .mapValues { (_, episodes) -> episodes.maxWithOrNull(
                compareBy<WatchProgress> { it.lastWatched }
                    .thenBy { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            ) }

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

    private fun SupabaseWatchProgressEvent.toWatchProgress(): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = "",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = position,
            duration = duration,
            lastWatched = lastWatched,
            source = WatchProgress.SOURCE_LOCAL
        )
    }
}
