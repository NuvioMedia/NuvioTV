package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class ServerUserStateProjection @Inject constructor(
    private val catalog: ServerCatalog,
    private val repository: ServerRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            catalog.detailsLoaded.collect { details ->
                try {
                    apply(details)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to apply server user state", error)
                }
            }
        }
    }

    suspend fun apply(details: ServerItemDetails) {
        val meta = details.meta
        val ref = ServerItemRef.parse(meta.id) ?: return
        val connection = repository.connection(ref.connectionId)?.takeIf { it.importWatchState } ?: return
        val isSeries = meta.type == ContentType.SERIES
        val profileId = profileManager.activeProfileId.value

        val progress = details.userStates
            .filter { !it.played && it.positionMs > 0L && it.durationMs > 0L && it.lastPlayedEpochMs != null }
            .map { it.toProgress(meta, isSeries, ServerCatalog.baseUrl(connection.id)) }
            .filter { isNewer(it, isSeries, profileId) }
        watchProgressRepository.saveProgressBatch(progress, syncRemote = false)

        val states = details.userStates.filter { !isSeries || (it.season != null && it.episode != null) }
        val watchedEpisodes = if (isSeries) watchedItemsPreferences.getWatchedEpisodesForContent(meta.id, profileId).first() else emptySet()
        val movieWatched = !isSeries && watchedItemsPreferences.observeAllItems(profileId).first()
            .any { it.contentId == meta.id && it.season == null && it.episode == null }
        fun ServerUserState.isWatchedLocally(): Boolean =
            if (isSeries) (season!! to episode!!) in watchedEpisodes else movieWatched

        val (played, unplayed) = states.partition { it.played }
        watchedItemsPreferences.markAsWatchedBatch(
            played.filterNot { it.isWatchedLocally() }.map { it.toWatchedItem(meta, isSeries) },
            profileId
        )
        val unwatched = unplayed.filter { it.isWatchedLocally() }
        if (isSeries) {
            watchedItemsPreferences.unmarkAsWatchedBatch(meta.id, unwatched.map { it.season!! to it.episode!! }, profileId)
        } else if (unwatched.isNotEmpty()) {
            watchedItemsPreferences.unmarkAsWatched(meta.id, profileId = profileId)
        }
    }

    private suspend fun isNewer(progress: WatchProgress, isSeries: Boolean, profileId: Int): Boolean {
        val existing = if (isSeries) {
            watchProgressPreferences.getEpisodeProgress(progress.contentId, progress.season ?: 0, progress.episode ?: 0, profileId).first()
        } else {
            watchProgressPreferences.getProgress(progress.contentId, profileId).first()
        }
        return existing == null || existing.lastWatched < progress.lastWatched
    }

    private fun ServerUserState.toProgress(meta: Meta, isSeries: Boolean, addonBaseUrl: String) = WatchProgress(
        contentId = meta.id,
        contentType = meta.apiType,
        name = meta.name,
        poster = meta.poster,
        backdrop = meta.background,
        logo = meta.logo,
        videoId = videoId,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        episodeTitle = title.takeIf { isSeries },
        position = positionMs,
        duration = durationMs,
        lastWatched = lastPlayedEpochMs ?: 0L,
        addonBaseUrl = addonBaseUrl
    )

    private fun ServerUserState.toWatchedItem(meta: Meta, isSeries: Boolean) = WatchedItem(
        contentId = meta.id,
        contentType = meta.apiType,
        title = meta.name,
        season = season.takeIf { isSeries },
        episode = episode.takeIf { isSeries },
        watchedAt = lastPlayedEpochMs ?: System.currentTimeMillis(),
        poster = meta.poster
    )

    private companion object {
        const val TAG = "ServerUserState"
    }
}
