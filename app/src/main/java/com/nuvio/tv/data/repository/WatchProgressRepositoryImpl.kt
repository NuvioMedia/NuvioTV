package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.util.Log
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktProgressService: TraktProgressService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
    }

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var watchedItemsSyncJob: Job? = null
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false
    var hasCompletedInitialWatchedItemsPull = false

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 30

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(2000)
            watchProgressSyncService.pushToRemote()
        }
    }

    private fun triggerWatchedItemsSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialWatchedItemsPull) return
        if (!authManager.isAuthenticated) return
        watchedItemsSyncJob?.cancel()
        watchedItemsSyncJob = syncScope.launch {
            delay(2000)
            watchedItemsSyncService.pushToRemote()
        }
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            syncScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.background,
                    logo = meta.logo,
                    episodes = episodes
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        traktProgressService.observeAllProgress()
                            .onStart {
                                // Emit local-cache-backed continue watching immediately on app start
                                // while the first Trakt snapshot is still loading.
                                emit(emptyList())
                            },
                        watchProgressPreferences.allRawProgress,
                        metadataState
                    ) { remoteItems, localItems, metadataMap ->
                        val merged = mergeProgressLists(remoteItems, localItems)
                        hydrateMetadata(merged)
                        merged.map { enrichWithMetadata(it, metadataMap) }
                    }
                } else {
                    combine(
                        watchProgressPreferences.allProgress,
                        metadataState
                    ) { items, metadataMap ->
                        hydrateMetadata(items)
                        items.map { enrichWithMetadata(it, metadataMap) }
                    }
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items
                            .filter { it.contentId == contentId }
                            .maxByOrNull { it.lastWatched }
                    }
                } else {
                    watchProgressPreferences.getProgress(contentId)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items.firstOrNull {
                            it.contentId == contentId && it.season == season && it.episode == episode
                        }
                    }
                } else {
                    watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
                }
            }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        traktProgressService.observeEpisodeProgress(contentId),
                        allProgress.map { items ->
                            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val season = episodeProgress.season ?: return@forEach
                            val episode = episodeProgress.episode ?: return@forEach
                            merged[season to episode] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId)
                }
            }
    }

    override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> {
        return traktAuthDataStore.isEffectivelyAuthenticated
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (!isAuthenticated) {
                    val progressFlow = if (season != null && episode != null) {
                        watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
                    } else {
                        watchProgressPreferences.getProgress(contentId)
                    }
                    return@flatMapLatest combine(
                        progressFlow,
                        watchedItemsPreferences.isWatched(contentId, season, episode)
                    ) { progressEntry, itemWatched ->
                        val hasStartedReplay = progressEntry?.let { entry ->
                            !entry.isCompleted() &&
                                (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
                        } == true

                        if (hasStartedReplay) {
                            false
                        } else {
                            (progressEntry?.isCompleted() == true) || itemWatched
                        }
                    }
                }

                if (season != null && episode != null) {
                    traktProgressService.observeEpisodeProgress(contentId)
                        .map { progressMap ->
                            progressMap[season to episode]?.isCompleted() == true
                        }
                        .distinctUntilChanged()
                } else {
                    traktProgressService.observeMovieWatched(contentId)
                }
            }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktProgressService.applyOptimisticProgress(progress)
            watchProgressPreferences.saveProgress(progress)
            return
        }
        watchProgressPreferences.saveProgress(progress)
        
        
        if (syncRemote && authManager.isAuthenticated) {
            syncScope.launch {
                watchProgressSyncService.pushSingleToRemote(progressKey(progress), progress)
                    .onFailure { error ->
                        Log.w(TAG, "Failed single progress push; falling back to full sync next cycle", error)
                    }
            }
        }

        if (progress.isCompleted()) {
            watchedItemsPreferences.markAsWatched(
                WatchedItem(
                    contentId = progress.contentId,
                    contentType = progress.contentType,
                    title = progress.name,
                    season = progress.season,
                    episode = progress.episode,
                    watchedAt = System.currentTimeMillis()
                )
            )
            triggerWatchedItemsSync()
        }
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val isAuthenticated = traktAuthDataStore.isEffectivelyAuthenticated.first()
        Log.d(
            TAG,
            "removeProgress called contentId=$contentId season=$season episode=$episode authenticated=$isAuthenticated"
        )
        if (isAuthenticated) {
            traktProgressService.applyOptimisticRemoval(contentId, season, episode)
            traktProgressService.removeProgress(contentId, season, episode)
            watchProgressPreferences.removeProgress(contentId, season, episode)
            return
        }
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(TAG, "removeProgress remote delete failed; relying on push sync", error)
                }
        }
        triggerRemoteSync()
    }

    override suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktProgressService.removeFromHistory(contentId, season, episode)
            watchProgressPreferences.removeProgress(contentId, season, episode)
            return
        }
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode)
        watchProgressPreferences.removeProgress(contentId, season, episode)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory remote delete failed; relying on push sync", error)
                }
        }
        triggerRemoteSync()
        triggerWatchedItemsSync()
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            val now = System.currentTimeMillis()
            val duration = progress.duration.takeIf { it > 0L } ?: 1L
            val completed = progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                lastWatched = now
            )
            traktProgressService.applyOptimisticProgress(completed)
            runCatching {
                traktProgressService.markAsWatched(
                    progress = completed,
                    title = completed.name.takeIf { it.isNotBlank() },
                    year = null
                )
            }.onFailure {
                traktProgressService.applyOptimisticRemoval(
                    contentId = completed.contentId,
                    season = completed.season,
                    episode = completed.episode
                )
                throw it
            }
            return
        }
        watchProgressPreferences.markAsCompleted(progress)
        watchedItemsPreferences.markAsWatched(
            WatchedItem(
                contentId = progress.contentId,
                contentType = progress.contentType,
                title = progress.name,
                season = progress.season,
                episode = progress.episode,
                watchedAt = System.currentTimeMillis()
            )
        )
        triggerRemoteSync()
        triggerWatchedItemsSync()
    }

    override suspend fun clearAll() {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktProgressService.clearOptimistic()
            watchProgressPreferences.clearAll()
            return
        }
        watchProgressPreferences.clearAll()
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<String> {
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = watchProgressPreferences
                .getAllRawEntries()
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        return keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun mergeProgressLists(
        remoteItems: List<WatchProgress>,
        localItems: List<WatchProgress>
    ): List<WatchProgress> {
        val mergedByKey = linkedMapOf<String, WatchProgress>()

        fun upsert(progress: WatchProgress) {
            val key = progressKey(progress)
            val existing = mergedByKey[key]
            if (existing == null || shouldPreferProgress(existing, progress)) {
                mergedByKey[key] = progress
            }
        }

        remoteItems.forEach(::upsert)
        localItems.forEach(::upsert)

        return mergedByKey.values
            .sortedByDescending { it.lastWatched }
    }

    private fun shouldPreferProgress(existing: WatchProgress, candidate: WatchProgress): Boolean {
        val timeDiffMs = candidate.lastWatched - existing.lastWatched
        if (timeDiffMs > 1_000L) return true
        if (timeDiffMs < -1_000L) return false

        val candidateInProgress = candidate.isInProgress()
        val existingInProgress = existing.isInProgress()
        if (candidateInProgress && !existingInProgress) return true
        if (!candidateInProgress && existingInProgress) return false

        val candidateIsPlayback = candidate.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        val existingIsPlayback = existing.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        if (candidateIsPlayback && !existingIsPlayback) return true
        if (!candidateIsPlayback && existingIsPlayback) return false

        return false
    }
}
