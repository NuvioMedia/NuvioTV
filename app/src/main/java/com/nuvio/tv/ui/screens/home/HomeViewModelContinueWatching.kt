package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
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
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val CW_MAX_RECENT_PROGRESS_ITEMS = 300
private const val CW_MAX_NEXT_UP_LOOKUPS = 24
private const val CW_MAX_NEXT_UP_CONCURRENCY = 2
private const val CW_PROGRESS_DEBOUNCE_MS = 500L
private const val CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS = 7L

private data class ContinueWatchingSettingsSnapshot(
    val items: List<WatchProgress>,
    val nextUpSeeds: List<WatchProgress>,
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean
)

private data class NextUpTmdbData(
    val thumbnail: String?,
    val backdrop: String?,
    val poster: String?,
    val logo: String?,
    val name: String?,
    val episodeTitle: String?,
    val airDate: String?,
    val overview: String?,
    val showDescription: String?
)

private data class NextUpResolution(
    val season: Int,
    val episode: Int,
    val videoId: String,
    val episodeTitle: String?,
    val released: String?,
    val hasAired: Boolean,
    val airDateLabel: String?,
    val lastWatched: Long
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        combine(
            combine(
                watchProgressRepository.allProgress,
                watchProgressRepository.observeNextUpSeeds()
            ) { items, nextUpSeeds ->
                items to nextUpSeeds
            },
            combine(
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                traktSettingsDataStore.showUnairedNextUp
            ) { daysCap, dismissedNextUp, showUnairedNextUp ->
                Triple(daysCap, dismissedNextUp, showUnairedNextUp)
            }
        ) { progressSnapshot, settingsSnapshot ->
            val (items, nextUpSeeds) = progressSnapshot
            val (daysCap, dismissedNextUp, showUnairedNextUp) = settingsSnapshot
            ContinueWatchingSettingsSnapshot(
                items = items,
                nextUpSeeds = nextUpSeeds,
                daysCap = daysCap,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp
            )
        }.debounce(CW_PROGRESS_DEBOUNCE_MS).collectLatest { snapshot ->
            val items = snapshot.items
            val nextUpSeeds = snapshot.nextUpSeeds
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
            val recentNextUpSeeds = nextUpSeeds
                .asSequence()
                .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                .sortedByDescending { it.lastWatched }
                .take(CW_MAX_RECENT_PROGRESS_ITEMS)
                .toList()

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

            val nextUpItems = buildLightweightNextUpItems(
                allProgress = recentItems,
                nextUpSeeds = recentNextUpSeeds,
                inProgressItems = inProgressOnly,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp
            )
            val normalItems = mergeContinueWatchingItems(
                inProgressItems = inProgressOnly,
                nextUpItems = nextUpItems
            )

            _uiState.update { state ->
                if (state.continueWatchingItems == normalItems) {
                    state
                } else {
                    state.copy(continueWatchingItems = normalItems)
                }
            }

            // Rich metadata only runs after the final lightweight CW list is visible.
            val enrichmentDelayMs = remainingContinueWatchingEnrichmentGraceMs()
            if (enrichmentDelayMs > 0L) {
                delay(enrichmentDelayMs)
            }
            enrichVisibleContinueWatchingItems(normalItems)
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

private fun shouldUseAsCompletedSeed(progress: WatchProgress): Boolean {
    if (!progress.isCompleted()) return false
    if (progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return true
    val explicitPercent = progress.progressPercent ?: return false
    return explicitPercent >= 95f
}

private fun shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgress,
    latestCompletedAt: Long?
): Boolean {
    if (!shouldTreatAsInProgressForContinueWatching(progress)) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastWatched >= latestCompletedAt
}

private fun logNextUpDecision(message: String) {
    Unit
}

private fun shouldTraceNextUpSeries(progress: WatchProgress): Boolean = false

private fun WatchProgress.toNextUpTraceString(): String {
    return buildString {
        append(name)
        append("(")
        append(contentId)
        append(") s=")
        append(season)
        append(" e=")
        append(episode)
        append(" src=")
        append(source)
        append(" last=")
        append(lastWatched)
        append(" pct=")
        append(progressPercent)
        append(" videoId=")
        append(videoId)
    }
}

private fun nextUpSeedSourceRank(progress: WatchProgress): Int {
    return when (progress.source) {
        WatchProgress.SOURCE_TRAKT_PLAYBACK -> 0
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> 0
        WatchProgress.SOURCE_TRAKT_HISTORY -> 1
        WatchProgress.SOURCE_LOCAL -> 2
        else -> 4
    }
}

private fun choosePreferredNextUpSeed(items: List<WatchProgress>): WatchProgress? {
    if (items.isEmpty()) return null
    val bestRank = items.minOf(::nextUpSeedSourceRank)
    return items
        .asSequence()
        .filter { nextUpSeedSourceRank(it) == bestRank }
        .maxWithOrNull(
            compareBy<WatchProgress>(
                { it.season ?: -1 },
                { it.episode ?: -1 },
                { it.lastWatched }
            )
        )
}

private suspend fun HomeViewModel.resolveCurrentEpisodeDescription(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    if (isSeriesTypeCW(progress.contentType)) {
        val video = resolveVideoForProgress(progress, meta)
        if (video != null) {
            val season = video.season
            val episode = video.episode
            if (season != null && episode != null && currentTmdbSettings.enabled) {
                val tmdbId = resolveTmdbIdForNextUp(progress, meta)
                if (tmdbId != null) {
                    val tmdbOverview = runCatching {
                        tmdbMetadataService.fetchEpisodeEnrichment(
                            tmdbId = tmdbId,
                            seasonNumbers = listOf(season),
                            language = currentTmdbSettings.language
                        )[season to episode]?.overview
                    }.getOrNull()
                    if (!tmdbOverview.isNullOrBlank()) return tmdbOverview
                }
            }
            val episodeOverview = video.overview?.takeIf { it.isNotBlank() }
            if (episodeOverview != null) return episodeOverview
        }
    }
    return meta.description?.takeIf { it.isNotBlank() }
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

private suspend fun HomeViewModel.resolveCurrentEpisodeImdbRating(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): Float? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    return meta.imdbRating
}

private suspend fun HomeViewModel.resolveCurrentGenres(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): List<String> {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return emptyList()
    return meta.genres.take(3)
}

private suspend fun HomeViewModel.resolveCurrentReleaseInfo(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    return meta.releaseInfo?.takeIf { it.isNotBlank() }
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

private suspend fun HomeViewModel.buildLightweightNextUpItems(
    allProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean
): List<ContinueWatchingItem.NextUp> = coroutineScope {
    val latestCompletedByContent = allProgress
        .asSequence()
        .filter { isSeriesTypeCW(it.contentType) }
        .filter { it.contentId.isNotBlank() }
        .filter { shouldUseAsCompletedSeed(it) }
        .groupBy { it.contentId }
        .mapValues { (_, items) ->
            items.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE
        }

    val inProgressIds = inProgressItems
        .map { it.progress }
        .filter { progress ->
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = progress,
                latestCompletedAt = latestCompletedByContent[progress.contentId]
            )
        }
        .map { it.contentId }
        .toSet()

    val latestCompletedBySeries = nextUpSeeds
        .filter { progress ->
            isSeriesTypeCW(progress.contentType) &&
                progress.season != null &&
                progress.episode != null &&
                progress.season != 0 &&
                shouldUseAsCompletedSeed(progress)
        }
        .groupBy { it.contentId }
        .mapNotNull { (_, items) ->
            if (items.any(::shouldTraceNextUpSeries)) {
                val candidates = items
                    .sortedWith(
                        compareBy<WatchProgress> { nextUpSeedSourceRank(it) }
                            .thenByDescending { it.season ?: -1 }
                            .thenByDescending { it.episode ?: -1 }
                            .thenByDescending { it.lastWatched }
                    )
                    .joinToString(" || ") { it.toNextUpTraceString() }
                logNextUpDecision("seed-group contentId=${items.first().contentId} candidates=$candidates")
            }
            val chosen = choosePreferredNextUpSeed(items)
            if (chosen != null && shouldTraceNextUpSeries(chosen)) {
                logNextUpDecision(
                    "seed-picked ${chosen.toNextUpTraceString()} rank=${nextUpSeedSourceRank(chosen)}"
                )
            }
            chosen
        }
        .filter { it.contentId !in inProgressIds }
        .filter { progress -> nextUpDismissKey(progress.contentId) !in dismissedNextUp }
        .sortedByDescending { it.lastWatched }
        .take(CW_MAX_NEXT_UP_LOOKUPS)

    logNextUpDecision(
        "seed candidates=${latestCompletedBySeries.joinToString { "${it.name}(${it.contentId}) s=${it.season} e=${it.episode}" }} " +
            "suppressedInProgress=${inProgressIds.joinToString()}"
    )

    if (latestCompletedBySeries.isEmpty()) {
        return@coroutineScope emptyList()
    }

    val lookupSemaphore = Semaphore(CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()

    val jobs = latestCompletedBySeries.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                val nextUp = buildNextUpItem(
                    progress = progress,
                    showUnairedNextUp = showUnairedNextUp
                ) ?: run {
                    logNextUpDecision("drop contentId=${progress.contentId} name=${progress.name} reason=buildNextUpItem-null")
                    return@withPermit
                }
                mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                }
            }
        }
    }
    jobs.joinAll()

    nextUpByContent.values.toList()
}

private suspend fun HomeViewModel.enrichVisibleContinueWatchingItems(
    finalItems: List<ContinueWatchingItem>
) = coroutineScope {
    if (finalItems.isEmpty()) return@coroutineScope

    val metaCache = cwMetaCache
    val enrichedItems = buildList(finalItems.size) {
        finalItems.forEach { item ->
            add(
                when (item) {
                    is ContinueWatchingItem.InProgress -> enrichInProgressItem(item, metaCache)
                    is ContinueWatchingItem.NextUp -> enrichNextUpItem(item, metaCache)
                }
            )
        }
    }

    if (enrichedItems == finalItems) return@coroutineScope

    _uiState.update { state ->
        if (state.continueWatchingItems == enrichedItems) {
            state
        } else {
            state.copy(continueWatchingItems = enrichedItems)
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
    showUnairedNextUp: Boolean
): ContinueWatchingItem.NextUp? {
    if (shouldTraceNextUpSeries(progress)) {
        logNextUpDecision(
            "build-start ${progress.toNextUpTraceString()} showUnaired=$showUnairedNextUp"
        )
    }
    val nextUp = findNextUpEpisodeFromMetaSeed(
        progress = progress,
        showUnairedNextUp = showUnairedNextUp
    ) ?: return null

    val name = progress.name.trim().takeIf { it.isNotEmpty() }
        ?: progress.contentId
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = name,
        poster = progress.poster.normalizeImageUrl(),
        backdrop = progress.backdrop.normalizeImageUrl(),
        logo = progress.logo.normalizeImageUrl(),
        videoId = nextUp.videoId,
        season = nextUp.season,
        episode = nextUp.episode,
        episodeTitle = nextUp.episodeTitle,
        episodeDescription = null,
        thumbnail = null,
        released = nextUp.released,
        hasAired = nextUp.hasAired,
        airDateLabel = nextUp.airDateLabel,
        lastWatched = nextUp.lastWatched,
        imdbRating = null,
        genres = emptyList(),
        releaseInfo = null,
        seedSeason = progress.season,
        seedEpisode = progress.episode
    )
    logNextUpDecision(
        "built contentId=${progress.contentId} name=${progress.name} next=${nextUp.season}x${nextUp.episode} " +
            "videoId=${nextUp.videoId} lastWatched=${nextUp.lastWatched}"
    )
    return ContinueWatchingItem.NextUp(info)
}

private suspend fun HomeViewModel.enrichInProgressItem(
    item: ContinueWatchingItem.InProgress,
    metaCache: MutableMap<String, Meta?>
): ContinueWatchingItem.InProgress {
    val description = resolveCurrentEpisodeDescription(item.progress, metaCache)
    val thumbnail = resolveCurrentEpisodeThumbnail(item.progress, metaCache)
    val imdbRating = resolveCurrentEpisodeImdbRating(item.progress, metaCache)
    val genres = resolveCurrentGenres(item.progress, metaCache)
    val releaseInfo = resolveCurrentReleaseInfo(item.progress, metaCache)
    return item.copy(
        episodeDescription = description,
        episodeThumbnail = thumbnail,
        episodeImdbRating = imdbRating,
        genres = genres,
        releaseInfo = releaseInfo
    )
}

private suspend fun HomeViewModel.enrichNextUpItem(
    item: ContinueWatchingItem.NextUp,
    metaCache: MutableMap<String, Meta?>
): ContinueWatchingItem.NextUp {
    val progressSeed = item.info.toProgressSeed()
    val meta = resolveMetaForProgress(progressSeed, metaCache) ?: return item
    val video = resolveNextUpVideoFromMeta(progressSeed, meta)
    val tmdbData = resolveNextUpTmdbData(
        progress = progressSeed,
        meta = meta,
        season = video?.season ?: item.info.season,
        episode = video?.episode ?: item.info.episode
    )
    val released = video?.released?.trim()?.takeIf { it.isNotEmpty() }
        ?: tmdbData?.airDate
        ?: item.info.released
    val releaseDate = parseEpisodeReleaseDate(released)
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val hasAired = releaseDate?.let { !it.isAfter(todayLocal) } ?: item.info.hasAired

    val enrichedInfo = item.info.copy(
        name = tmdbData?.name ?: meta.name,
        poster = item.info.poster ?: meta.poster.normalizeImageUrl() ?: tmdbData?.poster,
        backdrop = item.info.backdrop ?: meta.backdropUrl.normalizeImageUrl() ?: tmdbData?.backdrop,
        logo = item.info.logo ?: meta.logo.normalizeImageUrl() ?: tmdbData?.logo,
        season = video?.season ?: item.info.season,
        episode = video?.episode ?: item.info.episode,
        videoId = video?.id?.takeIf { it.isNotBlank() } ?: item.info.videoId,
        episodeTitle = tmdbData?.episodeTitle
            ?: video?.title?.takeIf { it.isNotBlank() }
            ?: item.info.episodeTitle,
        episodeDescription = tmdbData?.overview
            ?: video?.overview?.takeIf { it.isNotBlank() }
            ?: item.info.episodeDescription,
        thumbnail = item.info.thumbnail ?: video?.thumbnail.normalizeImageUrl() ?: tmdbData?.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired || releaseDate == null) null else formatEpisodeAirDateLabel(releaseDate),
        imdbRating = meta.imdbRating ?: item.info.imdbRating,
        genres = meta.genres.take(3).ifEmpty { item.info.genres },
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.info.releaseInfo
    )
    if (shouldTraceNextUpSeries(progressSeed)) {
        logNextUpDecision(
            "enrich-result contentId=${item.info.contentId} seed=${item.info.seedSeason}x${item.info.seedEpisode} " +
                "initial=${item.info.season}x${item.info.episode} final=${enrichedInfo.season}x${enrichedInfo.episode} " +
                "released=${enrichedInfo.released} hasAired=${enrichedInfo.hasAired} title=${enrichedInfo.episodeTitle}"
        )
    }
    return item.copy(info = enrichedInfo)
}

private suspend fun HomeViewModel.findNextUpEpisodeFromMetaSeed(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val contentId = progress.contentId
    val season = progress.season
    val episode = progress.episode
    if (season == null || episode == null || season == 0) {
        logNextUpDecision(
            "drop contentId=$contentId name=${progress.name} reason=missing-seed-season-episode " +
                "seed=${progress.season}x${progress.episode}"
        )
        return null
    }

    val meta = resolveMetaForProgress(progress, cwMetaCache) ?: run {
        logNextUpDecision("drop contentId=$contentId name=${progress.name} reason=no-meta-for-seed")
        return null
    }
    val nextVideo = resolveNextUpVideoFromMeta(progress, meta, showUnairedNextUp) ?: return null
    if (shouldTraceNextUpSeries(progress)) {
        logNextUpDecision(
            "next-video contentId=$contentId name=${progress.name} seed=${season}x${episode} src=${progress.source} " +
                "showUnaired=$showUnairedNextUp next=${nextVideo.season}x${nextVideo.episode} released=${nextVideo.released} title=${nextVideo.title}"
        )
    }

    return NextUpResolution(
        season = nextVideo.season ?: return null,
        episode = nextVideo.episode ?: return null,
        videoId = nextVideo.id.takeIf { it.isNotBlank() }
            ?: buildLightweightEpisodeVideoId(
                contentId,
                nextVideo.season ?: return null,
                nextVideo.episode ?: return null
            ),
        episodeTitle = nextVideo.title.takeIf { it.isNotBlank() },
        released = nextVideo.released?.trim()?.takeIf { it.isNotBlank() },
        hasAired = nextVideo.released?.let(::parseEpisodeReleaseDate)?.let { !it.isAfter(LocalDate.now(ZoneId.systemDefault())) } ?: true,
        airDateLabel = nextVideo.released?.let(::parseEpisodeReleaseDate)?.takeIf { it.isAfter(LocalDate.now(ZoneId.systemDefault())) }?.let(::formatEpisodeAirDateLabel),
        lastWatched = progress.lastWatched
    )
}

private fun resolveNextUpVideoFromMeta(
    progress: WatchProgress,
    meta: Meta
): Video? = resolveNextUpVideoFromMeta(progress, meta, showUnairedNextUp = true)

private fun resolveNextUpVideoFromMeta(
    progress: WatchProgress,
    meta: Meta,
    showUnairedNextUp: Boolean
): Video? {
    val episodes = meta.videos
        .filter { video ->
            val season = video.season
            val episode = video.episode
            season != null && episode != null && season != 0
        }
        .sortedWith(compareBy<Video>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))

    if (episodes.isEmpty()) return null

    val seedSeason = progress.season
    val seedEpisode = progress.episode
    if (seedSeason == null || seedEpisode == null) return null

    val watchedIndex = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }
    if (watchedIndex < 0) {
        logNextUpDecision(
            "drop contentId=${progress.contentId} name=${progress.name} reason=seed-not-found-in-meta seed=${seedSeason}x${seedEpisode}"
        )
        return null
    }

    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val nextVideo = episodes.drop(watchedIndex + 1).firstOrNull { video ->
        val releaseDate = parseEpisodeReleaseDate(video.released)
        val isSeasonRollover = video.season != seedSeason
        if (isSeasonRollover) {
            if (releaseDate == null) {
                logNextUpDecision(
                    "skip contentId=${progress.contentId} name=${progress.name} reason=unaired-next-season-missing-date " +
                        "seed=${seedSeason}x${seedEpisode} next=${video.season}x${video.episode}"
                )
                return@firstOrNull false
            }
            if (!releaseDate.isAfter(todayLocal)) {
                return@firstOrNull true
            }
            if (!showUnairedNextUp) {
                return@firstOrNull false
            }

            val daysUntilRelease = ChronoUnit.DAYS.between(todayLocal, releaseDate)
            val withinWindow = daysUntilRelease <= CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS
            if (!withinWindow) {
                logNextUpDecision(
                    "skip contentId=${progress.contentId} name=${progress.name} reason=unaired-next-season-too-far " +
                        "seed=${seedSeason}x${seedEpisode} next=${video.season}x${video.episode} daysUntil=$daysUntilRelease"
                )
            }
            return@firstOrNull withinWindow
        }

        val isUnaired = releaseDate?.isAfter(todayLocal) == true
        if (!isUnaired) {
            return@firstOrNull true
        }
        if (!showUnairedNextUp) {
            return@firstOrNull false
        }
        true
    }

    if (nextVideo == null) {
        logNextUpDecision(
            "drop contentId=${progress.contentId} name=${progress.name} reason=no-next-video-after-seed seed=${seedSeason}x${seedEpisode} showUnaired=$showUnairedNextUp"
        )
        return null
    }

    return nextVideo
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
                val result = withTimeoutOrNull(2_500L) {
                    metaRepository.getMetaFromPrimaryAddon(
                        type = type,
                        id = candidateId
                    ).first { it !is NetworkResult.Loading }
                } ?: continue
                meta = (result as? NetworkResult.Success<*>)?.data as? Meta
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

private fun buildLightweightEpisodeVideoId(
    contentId: String,
    season: Int,
    episode: Int
): String = "$contentId:$season:$episode"

private fun NextUpInfo.toProgressSeed(): WatchProgress {
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = seedSeason ?: season,
        episode = seedEpisode ?: episode,
        episodeTitle = episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched
    )
}

private fun isSeriesTypeCW(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
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

private suspend fun HomeViewModel.resolveNextUpTmdbData(
    progress: WatchProgress,
    meta: Meta,
    season: Int,
    episode: Int
): NextUpTmdbData? {
    if (!currentTmdbSettings.enabled) return null
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

    val fallback = NextUpTmdbData(
        thumbnail = episodeMeta?.thumbnail.normalizeImageUrl(),
        backdrop = showMeta?.backdrop.normalizeImageUrl(),
        poster = showMeta?.poster.normalizeImageUrl(),
        logo = showMeta?.logo.normalizeImageUrl(),
        name = showMeta?.localizedTitle?.trim()?.takeIf { it.isNotEmpty() },
        episodeTitle = episodeMeta?.title?.trim()?.takeIf { it.isNotEmpty() },
        airDate = episodeMeta?.airDate?.trim()?.takeIf { it.isNotEmpty() },
        overview = episodeMeta?.overview?.trim()?.takeIf { it.isNotEmpty() },
        showDescription = showMeta?.description?.trim()?.takeIf { it.isNotEmpty() }
    )

    return if (
        fallback.thumbnail == null &&
        fallback.backdrop == null &&
        fallback.poster == null &&
        fallback.airDate == null &&
        fallback.overview == null
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
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == todayLocal.year) "dMMM" else "dMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return java.text.SimpleDateFormat(pattern, locale).format(
        java.util.Date(releaseDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    )
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
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = targetSeason,
            episode = targetEpisode
        )
    }
}
