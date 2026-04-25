package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedEpisodeRef(
    val season: Int,
    val episode: Int
)

data class TraktProgressSnapshot(
    val savedAtMs: Long,
    val remoteProgress: List<WatchProgress>,
    val hasLoadedRemoteProgress: Boolean,
    val watchedShowSeeds: List<WatchProgress>,
    val watchedShowSeedsUpdatedAtMs: Long,
    val hasLoadedWatchedShowSeeds: Boolean,
    val watchedShowEpisodes: Map<String, List<PersistedEpisodeRef>>,
    val showIdToTraktPathId: Map<String, String>,
    val hiddenProgressShowIds: Set<String>,
    val hiddenProgressShowsLoadedAtMs: Long,
    val upNext: List<TraktProgressService.TraktUpNextEntry>,
    val hasLoadedUpNext: Boolean,
    val upNextEndpointUnavailable: Boolean,
    val lastKnownActivityFingerprint: String?,
    val lastKnownMoviesWatchedAt: String?,
    val lastKnownEpisodeActivityFingerprint: String?
)

@Singleton
class TraktProgressSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "TraktProgSnapshot"
    }

    private val gson = Gson()
    private val mutex = Mutex()

    private fun snapshotFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "trakt_progress")
        dir.mkdirs()
        return File(dir, "snapshot_${profileId}.json")
    }

    suspend fun load(): TraktProgressSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = snapshotFile()
                if (!file.exists()) return@withContext null
                gson.fromJson<TraktProgressSnapshot>(
                    file.readText(),
                    object : TypeToken<TraktProgressSnapshot>() {}.type
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read Trakt progress snapshot: ${e.message}")
                null
            }
        }
    }

    suspend fun save(snapshot: TraktProgressSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                snapshotFile().writeText(gson.toJson(snapshot))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write Trakt progress snapshot: ${e.message}")
            }
        }
    }
}
