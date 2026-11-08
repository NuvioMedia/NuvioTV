package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingListWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResolution
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AniListTrackingWrites internal constructor(
    private val sync: AniListSyncRepository,
    private val api: AniListApiClient,
    private val resolver: AniListIdResolver,
    private val auth: AniListAuthStore
) : TrackingScrobbler, TrackingHistoryWriter, TrackingListWriter {
    override val providerId = TrackingProviderId.ANILIST
    private val writeLock = Mutex()

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (action != TrackingScrobbleAction.STOP) return
        if (event.progressPercent < COMPLETION_THRESHOLD_PERCENT) return
        if (!auth.state.value.isAuthenticated) return
        sync.ensureLoaded()
        val scope = sync.currentScope()
        val target = resolve(event.media) ?: return
        writeLock.withLock {
            updateProgress(scope, target.mediaId, target.episode, onlyIfGreater = true)
        }
    }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = scope(profileId)
        val targets = mutableMapOf<Long, Int>()
        var notFound = 0
        items.forEach { item ->
            val target = resolve(item.media)
            if (target == null) {
                notFound += 1
            } else {
                targets[target.mediaId] = maxOf(targets[target.mediaId] ?: 0, target.episode)
            }
        }
        writeLock.withLock {
            targets.forEach { (mediaId, episode) ->
                updateProgress(scope, mediaId, episode, onlyIfGreater = true)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        val scope = scope(profileId)
        val targets = mutableMapOf<Long, Int>()
        var notFound = 0
        items.forEach { media ->
            val target = resolve(media)
            if (target == null) {
                notFound += 1
            } else {
                targets[target.mediaId] = minOf(targets[target.mediaId] ?: Int.MAX_VALUE, target.episode)
            }
        }
        writeLock.withLock {
            targets.forEach { (mediaId, episode) ->
                val (media, current) = currentEntry(scope, mediaId) ?: return@forEach
                current ?: return@forEach
                val progress = current.effectiveProgress()
                if (progress < episode) return@forEach
                val nextProgress = (episode - 1).coerceAtLeast(0)
                val nextStatus = when {
                    current.status == AniListListStatus.COMPLETED && nextProgress > 0 -> AniListListStatus.CURRENT
                    current.status == AniListListStatus.COMPLETED -> AniListListStatus.PLANNING
                    else -> current.status
                }
                val saved = api.saveEntry(scope, mediaId, status = nextStatus, progress = nextProgress)
                sync.applyEntry(scope, saved, media)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    override suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        val resolutions = mutableListOf<TrackingMutationResolution>()
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                setStatus(scope, target.mediaId, aniListStatusFor(destination))
                resolutions += TrackingMutationResolution(listStatus = destination, mediaKind = TrackingMediaKind.ANIME)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound, resolutions = resolutions)
    }

    override suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        val scope = scope(profileId)
        var notFound = 0
        writeLock.withLock {
            items.forEach { media ->
                val target = resolve(media)
                if (target == null) {
                    notFound += 1
                    return@forEach
                }
                deleteEntry(scope, target.mediaId)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFound)
    }

    internal suspend fun setStatus(scope: AniListAuthScope, mediaId: Long, status: String) {
        val (media, current) = currentEntry(scope, mediaId) ?: throw AniListNotFoundException("AniList media not found")
        val total = media.totalEpisodeCount()
        val progress = when {
            status == AniListListStatus.COMPLETED && total > 0 -> total
            else -> null
        }
        if (current?.status == status && progress == null) return
        val saved = api.saveEntry(scope, mediaId, status = status, progress = progress)
        sync.applyEntry(scope, saved, media)
    }

    internal suspend fun deleteEntry(scope: AniListAuthScope, mediaId: Long) {
        val (_, current) = currentEntry(scope, mediaId) ?: return
        current ?: return
        api.deleteEntry(scope, current.id)
        sync.removeEntry(scope, mediaId)
    }

    internal suspend fun setScore(scope: AniListAuthScope, mediaId: Long, scoreRaw: Int) {
        writeLock.withLock {
            val (media, current) = currentEntry(scope, mediaId) ?: throw AniListNotFoundException("AniList media not found")
            if (current == null && scoreRaw <= 0) return
            val saved = api.saveEntry(
                scope,
                mediaId,
                status = if (current == null) AniListListStatus.COMPLETED else null,
                progress = if (current == null && media.totalEpisodeCount() > 0) media.totalEpisodeCount() else null,
                scoreRaw = scoreRaw
            )
            sync.applyEntry(scope, saved, media)
        }
    }

    internal suspend fun resolve(media: TrackingMediaReference): AniListTarget? = try {
        resolver.resolve(media, sync.currentSnapshot())
    } catch (error: CancellationException) {
        throw error
    } catch (error: AniListAuthException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun updateProgress(scope: AniListAuthScope, mediaId: Long, episode: Int, onlyIfGreater: Boolean) {
        val (media, current) = currentEntry(scope, mediaId) ?: return
        if (onlyIfGreater && current != null && current.progress >= episode) return
        val total = media.totalEpisodeCount()
        var status = AniListListStatus.CURRENT
        if (current?.status == AniListListStatus.REPEATING) status = AniListListStatus.REPEATING
        if (total > 0 && episode >= total) status = AniListListStatus.COMPLETED
        val progress = if (total > 0 && episode > total) total else episode
        val saved = api.saveEntry(scope, mediaId, status = status, progress = progress)
        sync.applyEntry(scope, saved, media)
    }

    private suspend fun currentEntry(scope: AniListAuthScope, mediaId: Long): Pair<AniListMedia, AniListEntry?>? {
        val snapshot = sync.currentSnapshot()
        snapshot?.entries?.firstOrNull { it.mediaId == mediaId }?.let { entry ->
            entry.media?.let { return it to entry }
        }
        return api.mediaById(scope, mediaId)
    }

    private fun scope(profileId: Int): AniListAuthScope = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("AniList profile changed")
    }

    companion object {
        const val COMPLETION_THRESHOLD_PERCENT = 80.0
    }
}
