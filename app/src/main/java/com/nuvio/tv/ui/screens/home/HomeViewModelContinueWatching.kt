package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val CW_MAX_RECENT_PROGRESS_ITEMS = 300
private const val CW_MAX_NEXT_UP_LOOKUPS = 24
private const val CW_MAX_NEXT_UP_CONCURRENCY = 4

private data class ContinueWatchingSettingsSnapshot(
    val items: List<WatchProgress>,
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean
)

private data class NextUpArtworkFallback(
    val thumbnail: String?,
    val backdrop: String?,
    val poster: String?,
    val airDate: String?
)

private data class NextUpResolution(
    val episode: Video,
    val lastWatched: Long
)

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        combine(
            watchProgressRepository.allProgress,
            traktSettingsDataStore.continueWatchingDaysCap,
            traktSettingsDataStore.dismissedNextUpKeys,
            traktSettingsDataStore.showUnairedNextUp
        ) { items, daysCap, dismissedNextUp, showUnairedNextUp ->
            ContinueWatchingSettingsSnapshot(
                items = items,
                daysCap = daysCap,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp
            )
        }.collectLatest { snapshot ->
            val items = snapshot.items
            val daysCap = snapshot.daysCap
            val dismissedNextUp = snapshot.dismissedNextUp
            val showUnairedNextUp = snapshot.showUnairedNextUp
            val cutoffMs = if (daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
                null
            } else {
                val windowMs = daysCap.toLong() * 24L * 60L * 60L * 1000L
                System.currentTimeMillis() - windowMs
            }
            val recentItems = items
                .asSequence()
                .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                .sortedByDescending { it.lastWatched }
                .take(CW_MAX_RECENT_PROGRESS_ITEMS)
                .toList()

            Log.d("HomeViewModel", "allProgress emitted=${items.size} recentWindow=${recentItems.size}")

            val inProgressOnly = buildList {
                deduplicateInProgress(
                    recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
                ).forEach { progress ->
                    add(
                        ContinueWatchingItem.InProgress(
                            progress = progress
                        )
                    )
                }
            }

            Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

            // Optimistic immediate render: show in-progress entries instantly.
            _uiState.update { state ->
                if (state.continueWatchingItems == inProgressOnly) {
                    state
                } else {
                    state.copy(continueWatchingItems = inProgressOnly)
                }
            }

            // Then enrich Next Up and item details in background.
            enrichContinueWatchingProgressively(
                allProgress = recentItems,
                inProgressItems = inProgressOnly,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp
            )
            enrichInProgressEpisodeDetailsProgressively(inProgressOnly)
        }
    }
}

private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
    val (series, nonSeries) = items.partition { isSeriesTypeCW(it.contentType) }
    val latestPerShow = series
        .sortedByDescending { it.lastWatched }
        .distinctBy { it.contentId }
    return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
}

private fun shouldTreatAsInProgressForContinueWatching(progress: WatchProgress): Boolean {
    if (progress.isInProgress()) return true
    if (progress.isCompleted()) return false

    // Rewatch edge case: a started replay can be below the default 2% "in progress"
    // threshold, but should still suppress Next Up and appear as resume.
    val hasStartedPlayback = progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    return hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
}

private suspend fun HomeViewModel.resolveCurrentEpisodeDescription(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    val video = resolveVideoForProgress(progress, meta) ?: return null
    return video.overview?.takeIf { it.isNotBlank() }
}

private suspend fun HomeViewModel.resolveCurrentEpisodeThumbnail(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    val video = resolveVideoForProgress(progress, meta) ?: return null
    return video.thumbnail?.takeIf { it.isNotBlank() }
}

private fun resolveVideoForProgress(progress: WatchProgress, meta: Meta): Video? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val videos = meta.videos.filter { it.season != null && it.episode != null && it.season != 0 }
    if (videos.isEmpty()) return null

    progress.videoId.takeIf { it.isNotBlank() }?.let { videoId ->
        videos.firstOrNull { it.id == videoId }?.let { return it }
    }

    val season = progress.season
    val episode = progress.episode
    if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
    }

    return null
}

private suspend fun HomeViewModel.enrichContinueWatchingProgressively(
    allProgress: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean
) = coroutineScope {
    val inProgressIds = inProgressItems
        .map { it.progress.contentId }
        .filter { it.isNotBlank() }
        .toSet()

    val latestCompletedBySeries = allProgress
        .filter { progress ->
            isSeriesTypeCW(progress.contentType) &&
                progress.season != null &&
                progress.episode != null &&
                progress.season != 0 &&
                progress.isCompleted() &&
                progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK
        }
        .groupBy { it.contentId }
        .mapNotNull { (_, items) ->
            items.maxWithOrNull(
                compareBy<WatchProgress>(
                    { it.lastWatched },
                    { it.season ?: -1 },
                    { it.episode ?: -1 }
                )
            )
        }
        .filter { it.contentId !in inProgressIds }
        .filter { progress -> nextUpDismissKey(progress.contentId) !in dismissedNextUp }
        .sortedByDescending { it.lastWatched }
        .take(CW_MAX_NEXT_UP_LOOKUPS)

    if (latestCompletedBySeries.isEmpty()) {
        return@coroutineScope
    }

    val lookupSemaphore = Semaphore(CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
    val metaCache = mutableMapOf<String, Meta?>()
    var lastEmittedNextUpCount = 0

    val jobs = latestCompletedBySeries.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                val nextUp = buildNextUpItem(
                    progress = progress,
                    metaCache = metaCache,
                    showUnairedNextUp = showUnairedNextUp
                ) ?: return@withPermit
                mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                    if (nextUpByContent.size - lastEmittedNextUpCount >= 2) {
                        val nextUpItems = nextUpByContent.values.toList()
                        _uiState.update {
                            val mergedItems = mergeContinueWatchingItems(
                                inProgressItems = inProgressItems,
                                nextUpItems = nextUpItems
                            )
                            if (it.continueWatchingItems == mergedItems) {
                                it
                            } else {
                                it.copy(continueWatchingItems = mergedItems)
                            }
                        }
                        lastEmittedNextUpCount = nextUpByContent.size
                    }
                }
            }
        }
    }
    jobs.joinAll()

    mergeMutex.withLock {
        if (nextUpByContent.size != lastEmittedNextUpCount) {
            val nextUpItems = nextUpByContent.values.toList()
            _uiState.update {
                val mergedItems = mergeContinueWatchingItems(
                    inProgressItems = inProgressItems,
                    nextUpItems = nextUpItems
                )
                if (it.continueWatchingItems == mergedItems) {
                    it
                } else {
                    it.copy(continueWatchingItems = mergedItems)
                }
            }
        }
    }
}

private suspend fun HomeViewModel.enrichInProgressEpisodeDetailsProgressively(
    inProgressItems: List<ContinueWatchingItem.InProgress>
) = coroutineScope {
    if (inProgressItems.isEmpty()) return@coroutineScope

    val seriesItems = inProgressItems.filter { isSeriesTypeCW(it.progress.contentType) }
    if (seriesItems.isEmpty()) return@coroutineScope

    val metaCache = mutableMapOf<String, Meta?>()
    val enrichedByProgress = linkedMapOf<WatchProgress, ContinueWatchingItem.InProgress>()
    var lastAppliedCount = 0

    for (item in seriesItems) {
        val description = resolveCurrentEpisodeDescription(item.progress, metaCache)
        val thumbnail = resolveCurrentEpisodeThumbnail(item.progress, metaCache)
        val enrichedItem = item.copy(
            episodeDescription = description,
            episodeThumbnail = thumbnail
        )

        if (enrichedItem != item) {
            enrichedByProgress[item.progress] = enrichedItem
            if (enrichedByProgress.size - lastAppliedCount >= 2) {
                applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
                lastAppliedCount = enrichedByProgress.size
            }
        }
    }

    if (enrichedByProgress.isNotEmpty() && enrichedByProgress.size != lastAppliedCount) {
        applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
    }
}

private fun HomeViewModel.applyInProgressEpisodeDetailEnrichment(
    replacements: Map<WatchProgress, ContinueWatchingItem.InProgress>
) {
    if (replacements.isEmpty()) return

    _uiState.update { state ->
        var changed = false
        val updatedItems = state.continueWatchingItems.map { item ->
            if (item is ContinueWatchingItem.InProgress) {
                val replacement = replacements[item.progress]
                if (replacement != null && replacement != item) {
                    changed = true
                    replacement
                } else {
                    item
                }
            } else {
                item
            }
        }

        if (changed) {
            state.copy(continueWatchingItems = updatedItems)
        } else {
            state
        }
    }
}

private fun mergeContinueWatchingItems(
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    nextUpItems: List<ContinueWatchingItem.NextUp>
): List<ContinueWatchingItem> {
    val inProgressSeriesIds = inProgressItems
        .asSequence()
        .map { it.progress }
        .filter { isSeriesTypeCW(it.contentType) }
        .map { it.contentId }
        .filter { it.isNotBlank() }
        .toSet()

    val filteredNextUpItems = nextUpItems.filter { item ->
        item.info.contentId !in inProgressSeriesIds
    }

    val combined = mutableListOf<Pair<Long, ContinueWatchingItem>>()
    inProgressItems.forEach { combined.add(it.progress.lastWatched to it) }
    filteredNextUpItems.forEach { combined.add(it.info.lastWatched to it) }

    return combined
        .sortedByDescending { it.first }
        .map { it.second }
}

private suspend fun HomeViewModel.buildNextUpItem(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>,
    showUnairedNextUp: Boolean
): ContinueWatchingItem.NextUp? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    val nextUp = findNextUpEpisodeFromProgressMap(
        contentId = progress.contentId,
        meta = meta,
        showUnairedNextUp = showUnairedNextUp
    ) ?: findNextUpEpisodeFromLatestProgress(
        progress = progress,
        meta = meta,
        showUnairedNextUp = showUnairedNextUp
    ) ?: return null
    val video = nextUp.episode
    val nextSeason = requireNotNull(video.season)
    val nextEpisodeNumber = requireNotNull(video.episode)

    val existingPoster = meta.poster.normalizeImageUrl()
    val existingBackdrop = meta.background.normalizeImageUrl()
    val existingLogo = meta.logo.normalizeImageUrl()
    val existingThumbnail = video.thumbnail.normalizeImageUrl()
    val artworkFallback = if (
        existingThumbnail == null ||
        existingBackdrop == null ||
        existingPoster == null
    ) {
        resolveNextUpArtworkFallback(
            progress = progress,
            meta = meta,
            season = nextSeason,
            episode = nextEpisodeNumber
        )
    } else {
        null
    }
    val released = video.released?.trim()?.takeIf { it.isNotEmpty() }
        ?: artworkFallback?.airDate
    val releaseDate = parseEpisodeReleaseDate(released)
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val hasAired = releaseDate?.let { !it.isAfter(todayLocal) } ?: true
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = meta.name,
        poster = existingPoster ?: artworkFallback?.poster,
        backdrop = existingBackdrop ?: artworkFallback?.backdrop,
        logo = existingLogo,
        videoId = video.id,
        season = nextSeason,
        episode = nextEpisodeNumber,
        episodeTitle = video.title,
        episodeDescription = video.overview?.takeIf { it.isNotBlank() },
        thumbnail = existingThumbnail ?: artworkFallback?.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired) {
            null
        } else {
            formatEpisodeAirDateLabel(releaseDate)
        },
        lastWatched = nextUp.lastWatched
    )
    return ContinueWatchingItem.NextUp(info)
}

private suspend fun HomeViewModel.findNextUpEpisodeFromProgressMap(
    contentId: String,
    meta: Meta,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val episodes = meta.videos
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })
    if (episodes.isEmpty()) return null

    val progressMap = runCatching {
        withTimeoutOrNull(2_500L) {
            watchProgressRepository.getAllEpisodeProgress(contentId)
                .first { it.isNotEmpty() }
        } ?: watchProgressRepository.getAllEpisodeProgress(contentId).firstOrNull().orEmpty()
    }.getOrElse {
        Log.w(HomeViewModel.TAG, "findNextUpEpisodeFromProgressMap failed for $contentId: ${it.message}")
        emptyMap()
    }
    if (progressMap.isEmpty()) return null

    val completedProgress = progressMap.values
        .filter {
            val season = it.season
            val episode = it.episode
            season != null &&
                episode != null &&
                season != 0 &&
                it.isCompleted()
        }
    if (completedProgress.isEmpty()) return null

    val furthestCompleted = completedProgress.maxWithOrNull(
        compareBy<WatchProgress>({ it.season ?: -1 }, { it.episode ?: -1 }, { it.lastWatched })
    ) ?: return null

    val furthestIndex = episodes.indexOfFirst {
        it.season == furthestCompleted.season && it.episode == furthestCompleted.episode
    }
    if (furthestIndex < 0) return null

    val nextEpisode = episodes
        .drop(furthestIndex + 1)
        .firstOrNull { candidate ->
            val season = candidate.season ?: return@firstOrNull false
            val episode = candidate.episode ?: return@firstOrNull false
            val candidateProgress = progressMap[season to episode]
            candidateProgress?.isCompleted() != true
        }
        ?: return null

    val nextSeason = nextEpisode.season ?: return null
    val nextEpisodeNumber = nextEpisode.episode ?: return null
    val nextEpisodeProgress = progressMap[nextSeason to nextEpisodeNumber]
    if (nextEpisodeProgress != null && shouldTreatAsInProgressForContinueWatching(nextEpisodeProgress)) {
        return null
    }
    if (!shouldIncludeNextUpEpisode(nextEpisode, showUnairedNextUp)) return null

    val lastWatched = completedProgress.maxOfOrNull { it.lastWatched } ?: 0L
    return NextUpResolution(
        episode = nextEpisode,
        lastWatched = lastWatched
    )
}

private fun findNextUpEpisodeFromLatestProgress(
    progress: WatchProgress,
    meta: Meta,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val episodes = meta.videos
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })
    if (episodes.isEmpty()) return null

    val currentSeason = progress.season ?: return null
    val currentEpisode = progress.episode ?: return null
    val maxEpisodeInSeason = episodes.asSequence()
        .filter { it.season == currentSeason }
        .mapNotNull { it.episode }
        .maxOrNull()
        ?: return null

    val targetSeason = if (currentEpisode >= maxEpisodeInSeason) currentSeason + 1 else currentSeason
    val targetEpisode = if (currentEpisode >= maxEpisodeInSeason) 1 else currentEpisode + 1

    val nextEpisode = episodes.firstOrNull {
        it.season == targetSeason && it.episode == targetEpisode
    } ?: return null

    if (!shouldIncludeNextUpEpisode(nextEpisode, showUnairedNextUp)) return null

    return NextUpResolution(
        episode = nextEpisode,
        lastWatched = progress.lastWatched
    )
}

private suspend fun HomeViewModel.resolveMetaForProgress(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): Meta? {
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    synchronized(metaCache) {
        if (metaCache.containsKey(cacheKey)) {
            return metaCache[cacheKey]
        }
    }

    val idCandidates = buildList {
        add(progress.contentId)
        if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
        if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
    }.distinct()

    val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
    val resolved = run {
        var meta: Meta? = null
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(2500) {
                    metaRepository.getMetaFromAllAddons(
                        type = type,
                        id = candidateId
                    ).first { it !is NetworkResult.Loading }
                } ?: continue
                meta = (result as? NetworkResult.Success)?.data
                if (meta != null) break
            }
            if (meta != null) break
        }
        meta
    }

    synchronized(metaCache) {
        metaCache[cacheKey] = resolved
    }
    return resolved
}

private fun isSeriesTypeCW(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

private fun shouldIncludeNextUpEpisode(
    nextEpisode: Video,
    showUnairedNextUp: Boolean
): Boolean {
    if (showUnairedNextUp) return true
    val releaseDate = parseEpisodeReleaseDate(nextEpisode.released)
        ?: return true
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    return !releaseDate.isAfter(todayLocal)
}

private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneId.systemDefault()

    return runCatching {
        Instant.parse(value).atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).toInstant().atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value)
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion)
    }.getOrNull()
}

private suspend fun HomeViewModel.resolveNextUpArtworkFallback(
    progress: WatchProgress,
    meta: Meta,
    season: Int,
    episode: Int
): NextUpArtworkFallback? {
    val tmdbId = resolveTmdbIdForNextUp(progress, meta) ?: return null
    val language = currentTmdbSettings.language

    val episodeMeta = runCatching {
        tmdbMetadataService
            .fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = language
            )[season to episode]
    }.getOrNull()

    val showMeta = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = ContentType.SERIES,
            language = language
        )
    }.getOrNull()

    val fallback = NextUpArtworkFallback(
        thumbnail = episodeMeta?.thumbnail.normalizeImageUrl(),
        backdrop = showMeta?.backdrop.normalizeImageUrl(),
        poster = showMeta?.poster.normalizeImageUrl(),
        airDate = episodeMeta?.airDate?.trim()?.takeIf { it.isNotEmpty() }
    )

    return if (
        fallback.thumbnail == null &&
        fallback.backdrop == null &&
        fallback.poster == null &&
        fallback.airDate == null
    ) {
        null
    } else {
        fallback
    }
}

private suspend fun HomeViewModel.resolveTmdbIdForNextUp(
    progress: WatchProgress,
    meta: Meta
): String? {
    val candidates = buildList {
        add(progress.contentId)
        add(meta.id)
        add(progress.videoId)
        if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
        if (meta.id.startsWith("trakt:")) add(meta.id.substringAfter(':'))
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    for (candidate in candidates) {
        tmdbService.ensureTmdbId(candidate, progress.contentType)?.let { return it }
    }
    return null
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val formatter = if (releaseDate.year == todayLocal.year) {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    } else {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    }
    return releaseDate.format(formatter)
}

private fun String?.normalizeImageUrl(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun nextUpDismissKey(contentId: String): String {
    return contentId.trim()
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val dismissKey = nextUpDismissKey(contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(item.info.contentId) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                }
            )
        }
        viewModelScope.launch {
            traktSettingsDataStore.addDismissedNextUpKey(dismissKey)
        }
        return
    }
    viewModelScope.launch {
        val targetSeason = if (isNextUp) season else null
        val targetEpisode = if (isNextUp) episode else null
        Log.d(
            HomeViewModel.TAG,
            "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp targetSeason=$targetSeason targetEpisode=$targetEpisode"
        )
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = targetSeason,
            episode = targetEpisode
        )
    }
}
