package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.util.Log
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktProgressService: TraktProgressService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
        private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3 * 60_000L
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
    private val optimisticContinueWatchingUpdates = MutableSharedFlow<WatchProgress>(
        replay = 1,
        extraBufferCapacity = 16
    )
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
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
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

    private fun useTraktProgressFlow(): Flow<Boolean> {
        return combine(
            traktAuthDataStore.isEffectivelyAuthenticated,
            traktSettingsDataStore.watchProgressSource
        ) { isEffectivelyAuthenticated, source ->
            isEffectivelyAuthenticated && source == WatchProgressSource.TRAKT
        }.distinctUntilChanged()
    }

    private suspend fun shouldUseTraktProgress(): Boolean = useTraktProgressFlow().first()

    private suspend fun hasEffectiveTraktConnection(): Boolean =
        traktAuthDataStore.isEffectivelyAuthenticated.first()

    private fun traktAllProgressFlow(): Flow<List<WatchProgress>> {
        return traktProgressService.observeAllProgress()
            .onStart {
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    traktAllProgressFlow()
                } else {
                    watchProgressPreferences.allProgress
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    traktProgressService.observeAllProgress().map { items ->
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
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    traktProgressService.observeAllProgress().map { items ->
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
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    combine(
                        traktProgressService.observeEpisodeProgress(contentId)
                            .onStart { emit(emptyMap()) },
                        allProgress.map { items ->
                            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val seasonNum = episodeProgress.season ?: return@forEach
                            val episodeNum = episodeProgress.episode ?: return@forEach
                            merged[seasonNum to episodeNum] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId)
                }
            }
    }

    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> {
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    traktProgressService.observeAiredEpisodes(contentId)
                } else {
                    flowOf(emptyList())
                }
            }
            .distinctUntilChanged()
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    combine(
                        traktProgressService.observeWatchedShowSeeds(),
                        traktProgressService.observeAllProgress()
                            .map { items ->
                                val nowMs = System.currentTimeMillis()
                                items.filter { progress ->
                                    isOptimisticNextUpSeedCandidate(progress, nowMs)
                                }
                            }
                            .onStart { emit(emptyList()) }
                    ) { canonicalSeeds, optimisticSeeds ->
                        mergeNextUpSeeds(canonicalSeeds, optimisticSeeds)
                    }
                } else {
                    watchProgressPreferences.allProgress.map { items ->
                        items.filter { progress ->
                            progress.isCompleted() &&
                                progress.contentType.equals("series", ignoreCase = true) &&
                                progress.season != null &&
                                progress.episode != null &&
                                progress.season != 0 &&
                                !isMalformedNextUpSeedContentId(progress.contentId)
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun isOptimisticNextUpSeedCandidate(
        progress: WatchProgress,
        nowMs: Long
    ): Boolean {
        if (!progress.contentType.equals("series", ignoreCase = true)) return false
        if (!progress.isCompleted()) return false
        if (progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return false
        if (progress.season == null || progress.episode == null || progress.season == 0) return false
        val ageMs = nowMs - progress.lastWatched
        return ageMs in 0..OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
    }

    private fun mergeNextUpSeeds(
        canonicalSeeds: List<WatchProgress>,
        optimisticSeeds: List<WatchProgress>
    ): List<WatchProgress> {
        val merged = linkedMapOf<String, WatchProgress>()
        canonicalSeeds.forEach { seed ->
            merged[nextUpSeedKey(seed)] = seed
        }
        optimisticSeeds.forEach { seed ->
            val key = nextUpSeedKey(seed)
            val existing = merged[key]
            if (existing == null || shouldReplaceNextUpSeed(existing, seed)) {
                merged[key] = seed
            }
        }
        return merged.values.sortedByDescending { it.lastWatched }
    }

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }

    private fun nextUpSeedKey(progress: WatchProgress): String {
        return progress.traktShowId?.let { "trakt_show:$it" }
            ?: progress.contentId.trim()
    }

    private fun shouldReplaceNextUpSeed(
        existing: WatchProgress,
        candidate: WatchProgress
    ): Boolean {
        val candidateSeason = candidate.season ?: -1
        val candidateEpisode = candidate.episode ?: -1
        val existingSeason = existing.season ?: -1
        val existingEpisode = existing.episode ?: -1
        return candidateSeason > existingSeason ||
            (
                candidateSeason == existingSeason &&
                    (
                        candidateEpisode > existingEpisode ||
                            (
                                candidateEpisode == existingEpisode &&
                                    candidate.lastWatched >= existing.lastWatched
                                )
                        )
                )
    }

    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> {
        return optimisticContinueWatchingUpdates
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (useTraktProgress) {
                    traktProgressService.observeAllWatchedMovieIds()
                } else {
                    combine(
                        watchProgressPreferences.allProgress,
                        watchedItemsPreferences.allItems
                    ) { progressList, watchedItems ->
                        val completedIds = mutableSetOf<String>()
                        val replayingIds = mutableSetOf<String>()
                        for (progress in progressList) {
                            if (progress.isCompleted()) {
                                completedIds.add(progress.contentId)
                            } else if (progress.position > 0L ||
                                progress.progressPercent?.let { it > 0f } == true
                            ) {
                                replayingIds.add(progress.contentId)
                            }
                        }
                        val watchedItemIds = watchedItems
                            .filter { it.season == null && it.episode == null }
                            .map { it.contentId }
                            .toSet()
                        (completedIds + watchedItemIds) - replayingIds
                    }.debounce(500)
                }
            }
            .distinctUntilChanged()
    }

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> {
        return useTraktProgressFlow()
            .flatMapLatest { useTraktProgress ->
                if (!useTraktProgress) {
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
                    traktProgressService.observeMovieWatched(contentId, videoId)
                }
            }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        // Clear any CW dismiss keys for this series so it reappears in Continue Watching.
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId)
        }
        if (shouldUseTraktProgress()) {
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

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        if (progressList.isEmpty()) return
        if (shouldUseTraktProgress()) {
            progressList.forEach { progress ->
                traktProgressService.applyOptimisticProgress(progress)
            }
            watchProgressPreferences.saveProgressBatch(progressList)
            return
        }

        watchProgressPreferences.saveProgressBatch(progressList)

        if (syncRemote && authManager.isAuthenticated) {
            triggerRemoteSync()
        }

        var completedSaved = false
        progressList
            .filter { it.isCompleted() }
            .forEach { progress ->
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
                completedSaved = true
            }
        if (completedSaved) {
            triggerWatchedItemsSync()
        }
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val useTraktProgress = shouldUseTraktProgress()
        val hasEffectiveTraktConnection = hasEffectiveTraktConnection()
        val remoteDeleteKeys = if (!useTraktProgress) {
            resolveRemoteDeleteKeys(contentId, season, episode)
        } else {
            emptyList()
        }
        if (hasEffectiveTraktConnection) {
            traktProgressService.applyOptimisticRemoval(contentId, season, episode)
            traktProgressService.removeProgress(contentId, season, episode)
        }
        watchProgressPreferences.removeProgress(contentId, season, episode)
        if (useTraktProgress) {
            return
        }
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(TAG, "removeProgress remote delete failed; relying on push sync", error)
                }
        }
        triggerRemoteSync()
    }

    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) {
        val useTraktProgress = shouldUseTraktProgress()
        val remoteDeleteKeys = if (!useTraktProgress) {
            resolveRemoteDeleteKeys(contentId, season, episode)
        } else {
            emptyList()
        }
        if (hasEffectiveTraktConnection()) {
            traktProgressService.removeFromHistory(contentId, videoId, season, episode)
        }
        watchProgressPreferences.removeProgress(contentId, season, episode)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode)
        if (useTraktProgress) {
            return
        }
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
        // Clear any CW dismiss keys for this series so it reappears in Continue Watching.
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId)
        }
        val useTraktProgress = shouldUseTraktProgress()
        val hasEffectiveTraktConnection = hasEffectiveTraktConnection()
        if (useTraktProgress && hasEffectiveTraktConnection) {
            val now = System.currentTimeMillis()
            val duration = progress.duration.takeIf { it > 0L } ?: 1L
            val completed = progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                lastWatched = now
            )
            optimisticContinueWatchingUpdates.tryEmit(completed)
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
        if (hasEffectiveTraktConnection) {
            val now = System.currentTimeMillis()
            val duration = progress.duration.takeIf { it > 0L } ?: 1L
            val completed = progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                lastWatched = now
            )
            optimisticContinueWatchingUpdates.tryEmit(completed)
            runCatching {
                traktProgressService.markAsWatched(
                    progress = completed,
                    title = completed.name.takeIf { it.isNotBlank() },
                    year = null
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to mirror completed state to Trakt", error)
            }
        }
        triggerRemoteSync()
        triggerWatchedItemsSync()
    }

    override suspend fun clearAll() {
        if (shouldUseTraktProgress()) {
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
        val rawEntries = watchProgressPreferences.getAllRawEntries()
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        val resolvedKeys = keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

}
