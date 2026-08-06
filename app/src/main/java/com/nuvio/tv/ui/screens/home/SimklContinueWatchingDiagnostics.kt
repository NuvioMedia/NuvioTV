package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingDiagnosticIdentity
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import java.util.Locale

internal enum class SimklCwDiagnosticOrigin {
    CACHE,
    FRESH,
    LIVE_PLAYBACK,
    OLDER_RESOLVED,
    PREVIOUS_STATE
}

internal enum class SimklCwDiagnosticFinding {
    CARD_WITHOUT_ACTIVE_SEED,
    COMPLETED_PLAYBACK_VISIBLE,
    CONSISTENT_NEXT_UP,
    NEWER_PLAYBACK_REWATCH,
    NEXT_NOT_AFTER_SEED,
    NO_EXACT_HISTORY_FOR_CARD,
    OPEN_PLAYBACK,
    RESOLVER_RETURNED_WATCHED_EPISODE,
    SEED_BEHIND_FURTHEST,
    SEED_NOT_IN_EXACT_HISTORY,
    STALE_PLAYBACK_NOT_RECONCILED,
    WATCHED_EPISODE_FROM_CACHE
}

internal enum class SimklCwDiagnosticItemKind {
    IN_PROGRESS,
    NEXT_UP
}

internal data class SimklCwDiagnosticEpisode(
    val season: Int?,
    val episode: Int
) {
    fun display(): String = season?.let { value -> "S${value}E$episode" } ?: "E$episode"
}

internal data class SimklCwDisplayDiagnosticRecord(
    val key: String,
    val kind: SimklCwDiagnosticItemKind,
    val origin: SimklCwDiagnosticOrigin,
    val seed: SimklCwDiagnosticEpisode?,
    val displayed: SimklCwDiagnosticEpisode?,
    val exactWatchedCount: Int,
    val furthest: SimklCwDiagnosticEpisode?,
    val seedInExactHistory: Boolean?,
    val displayedInExactHistory: Boolean,
    val finding: SimklCwDiagnosticFinding
) {
    fun logLine(): String = "display key=$key item=${kind.name.lowercase()} " +
        "origin=${origin.name.lowercase()} seed=${seed?.display() ?: "none"} " +
        "shown=${displayed?.display() ?: "none"} exact=$exactWatchedCount " +
        "furthest=${furthest?.display() ?: "none"} " +
        "seedInExact=${seedInExactHistory?.toString() ?: "unknown"} " +
        "shownInExact=$displayedInExactHistory finding=${finding.name}"
}

internal data class SimklCwDisplayDiagnosticReport(
    val hasLoadedRemoteProgress: Boolean,
    val displayedCount: Int,
    val records: List<SimklCwDisplayDiagnosticRecord>
) {
    fun summaryLine(): String {
        val concerning = records.count { record ->
            record.finding !in setOf(
                SimklCwDiagnosticFinding.CONSISTENT_NEXT_UP,
                SimklCwDiagnosticFinding.NEWER_PLAYBACK_REWATCH,
                SimklCwDiagnosticFinding.OPEN_PLAYBACK
            )
        }
        return "display-summary remoteLoaded=$hasLoadedRemoteProgress visible=$displayedCount " +
            "simkl=${records.size} concerning=$concerning"
    }
}

internal fun buildSimklCwDisplayDiagnosticReport(
    displayedItems: List<ContinueWatchingItem>,
    liveProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    watchedItems: List<WatchedItem>,
    freshNextUpItems: List<ContinueWatchingItem.NextUp>,
    olderResolvedNextUpItems: List<ContinueWatchingItem.NextUp>,
    cachedNextUpItems: List<ContinueWatchingItem.NextUp>,
    preferFurthestEpisode: Boolean,
    hasLoadedRemoteProgress: Boolean,
    aliasFor: (String) -> String = TrackingDiagnosticIdentity::alias
): SimklCwDisplayDiagnosticReport {
    val hasSimklData = liveProgress.any(WatchProgress::isSimklDiagnosticItem) ||
        nextUpSeeds.any(WatchProgress::isSimklDiagnosticItem) ||
        watchedItems.any(WatchedItem::isSimklDiagnosticItem)
    if (!hasSimklData) {
        return SimklCwDisplayDiagnosticReport(
            hasLoadedRemoteProgress = hasLoadedRemoteProgress,
            displayedCount = displayedItems.size,
            records = emptyList()
        )
    }
    val simklProgress = liveProgress.filter(WatchProgress::isSimklDiagnosticItem)
    val simklSeeds = nextUpSeeds.filter(WatchProgress::isSimklDiagnosticItem)
    val simklWatched = watchedItems.filter(WatchedItem::isSimklDiagnosticItem)
    val progressByContent = simklProgress.groupBy { progress -> progress.contentId.diagnosticKey() }
    val seedsByContent = simklSeeds.associateBy { progress -> progress.contentId.diagnosticKey() }
    val watchedByContent = simklWatched.groupBy { item -> item.contentId.diagnosticKey() }
    val simklContentKeys = buildSet {
        addAll(progressByContent.keys)
        addAll(seedsByContent.keys)
        addAll(watchedByContent.keys)
    }
    val freshKeys = freshNextUpItems.mapTo(mutableSetOf()) { item -> item.info.diagnosticKey() }
    val olderKeys = olderResolvedNextUpItems.mapTo(mutableSetOf()) { item -> item.info.diagnosticKey() }
    val cacheKeys = cachedNextUpItems.mapTo(mutableSetOf()) { item -> item.info.diagnosticKey() }
    val records = displayedItems.mapNotNull { item ->
        val contentId = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.contentId
            is ContinueWatchingItem.NextUp -> item.info.contentId
        }
        val contentKey = contentId.diagnosticKey()
        if (contentKey !in simklContentKeys) return@mapNotNull null
        val exact = watchedByContent[contentKey].orEmpty()
            .filter { watched -> watched.season != null && watched.episode != null && watched.season != 0 }
        val furthest = exact.maxWithOrNull(
            compareBy(
                { watched -> watched.season ?: -1 },
                { watched -> watched.episode ?: -1 },
                WatchedItem::watchedAt
            )
        )
        when (item) {
            is ContinueWatchingItem.NextUp -> buildNextUpDiagnosticRecord(
                item = item,
                key = aliasFor(contentId),
                seed = seedsByContent[contentKey],
                exact = exact,
                furthest = furthest,
                origin = when (item.info.diagnosticKey()) {
                    in freshKeys -> SimklCwDiagnosticOrigin.FRESH
                    in olderKeys -> SimklCwDiagnosticOrigin.OLDER_RESOLVED
                    in cacheKeys -> SimklCwDiagnosticOrigin.CACHE
                    else -> SimklCwDiagnosticOrigin.PREVIOUS_STATE
                },
                preferFurthestEpisode = preferFurthestEpisode
            )

            is ContinueWatchingItem.InProgress -> buildInProgressDiagnosticRecord(
                item = item,
                key = aliasFor(contentId),
                liveProgress = progressByContent[contentKey].orEmpty(),
                exact = exact,
                furthest = furthest
            )
        }
    }
    return SimklCwDisplayDiagnosticReport(
        hasLoadedRemoteProgress = hasLoadedRemoteProgress,
        displayedCount = displayedItems.size,
        records = records
    )
}

internal object SimklContinueWatchingDisplayLogger {
    private const val TAG = "SimklCwDiag"
    private var lastReportHash: Int? = null

    @Synchronized
    fun log(report: SimklCwDisplayDiagnosticReport) {
        if (report.records.isEmpty()) return
        val reportHash = report.hashCode()
        if (lastReportHash == reportHash) return
        lastReportHash = reportHash
        Log.d(TAG, report.summaryLine())
        report.records.forEach { record -> Log.d(TAG, record.logLine()) }
    }
}

internal fun logSimklAsyncNextUpResolution(
    seed: WatchProgress,
    resolved: ContinueWatchingItem.NextUp?,
    watchedItems: List<WatchedItem>
) {
    if (!seed.isSimklDiagnosticItem()) return
    val exact = watchedItems.filter { watched ->
        watched.isSimklDiagnosticItem() &&
            watched.contentId.equals(seed.contentId, ignoreCase = true) &&
            watched.season != null &&
            watched.episode != null &&
            watched.season != 0
    }
    val shown = resolved?.info?.let { info ->
        SimklCwDiagnosticEpisode(info.season, info.episode)
    }
    val shownInExact = shown?.let { episode ->
        exact.any { watched ->
            watched.season == episode.season && watched.episode == episode.episode
        }
    } == true
    val seedEpisode = seed.toDiagnosticEpisode()
    val finding = when {
        resolved == null -> "NO_NEXT"
        shownInExact -> "RESOLVED_WATCHED_EPISODE"
        shown != null && seedEpisode != null && shown.compareTo(seedEpisode) <= 0 ->
            "RESOLVED_NOT_AFTER_SEED"
        else -> "RESOLVED_UNWATCHED_EPISODE"
    }
    Log.d(
        "SimklCwDiag",
        "async key=${TrackingDiagnosticIdentity.alias(seed.contentId)} " +
            "seed=${seedEpisode?.display() ?: "none"} " +
            "resolved=${shown?.display() ?: "none"} exact=${exact.size} " +
            "shownInExact=$shownInExact finding=$finding"
    )
}

private fun buildNextUpDiagnosticRecord(
    item: ContinueWatchingItem.NextUp,
    key: String,
    seed: WatchProgress?,
    exact: List<WatchedItem>,
    furthest: WatchedItem?,
    origin: SimklCwDiagnosticOrigin,
    preferFurthestEpisode: Boolean
): SimklCwDisplayDiagnosticRecord {
    val displayed = SimklCwDiagnosticEpisode(item.info.season, item.info.episode)
    val seedEpisode = seed.toDiagnosticEpisode()
    val furthestEpisode = furthest.toDiagnosticEpisode()
    val seedInExact = seed?.let { progress ->
        exact.any { watched ->
            watched.season == progress.season &&
                watched.episode == progress.episode &&
                watched.watchedAt == progress.lastWatched
        }
    }
    val displayedInExact = exact.any { watched ->
        watched.season == displayed.season && watched.episode == displayed.episode
    }
    val finding = when {
        displayedInExact && origin in setOf(
            SimklCwDiagnosticOrigin.CACHE,
            SimklCwDiagnosticOrigin.PREVIOUS_STATE
        ) -> SimklCwDiagnosticFinding.WATCHED_EPISODE_FROM_CACHE

        displayedInExact -> SimklCwDiagnosticFinding.RESOLVER_RETURNED_WATCHED_EPISODE
        seed == null -> SimklCwDiagnosticFinding.CARD_WITHOUT_ACTIVE_SEED
        exact.isEmpty() -> SimklCwDiagnosticFinding.NO_EXACT_HISTORY_FOR_CARD
        seedInExact == false -> SimklCwDiagnosticFinding.SEED_NOT_IN_EXACT_HISTORY
        preferFurthestEpisode && seedEpisode != furthestEpisode ->
            SimklCwDiagnosticFinding.SEED_BEHIND_FURTHEST

        seedEpisode != null && displayed.compareTo(seedEpisode) <= 0 ->
            SimklCwDiagnosticFinding.NEXT_NOT_AFTER_SEED

        else -> SimklCwDiagnosticFinding.CONSISTENT_NEXT_UP
    }
    return SimklCwDisplayDiagnosticRecord(
        key = key,
        kind = SimklCwDiagnosticItemKind.NEXT_UP,
        origin = origin,
        seed = seedEpisode,
        displayed = displayed,
        exactWatchedCount = exact.size,
        furthest = furthestEpisode,
        seedInExactHistory = seedInExact,
        displayedInExactHistory = displayedInExact,
        finding = finding
    )
}

private fun buildInProgressDiagnosticRecord(
    item: ContinueWatchingItem.InProgress,
    key: String,
    liveProgress: List<WatchProgress>,
    exact: List<WatchedItem>,
    furthest: WatchedItem?
): SimklCwDisplayDiagnosticRecord {
    val displayed = item.progress.toDiagnosticEpisode()
    val live = liveProgress
        .filter { progress -> progress.season == item.progress.season && progress.episode == item.progress.episode }
        .maxByOrNull(WatchProgress::lastWatched)
    val effectiveProgress = live ?: item.progress
    val matchingWatched = exact
        .filter { watched ->
            watched.season == displayed?.season && watched.episode == displayed?.episode
        }
        .maxByOrNull(WatchedItem::watchedAt)
    val finding = when {
        effectiveProgress.progressPercentage >= WatchProgress.SIMKL_COMPLETED_THRESHOLD ->
            SimklCwDiagnosticFinding.COMPLETED_PLAYBACK_VISIBLE

        matchingWatched == null -> SimklCwDiagnosticFinding.OPEN_PLAYBACK
        matchingWatched.watchedAt >= effectiveProgress.lastWatched ->
            SimklCwDiagnosticFinding.STALE_PLAYBACK_NOT_RECONCILED

        else -> SimklCwDiagnosticFinding.NEWER_PLAYBACK_REWATCH
    }
    return SimklCwDisplayDiagnosticRecord(
        key = key,
        kind = SimklCwDiagnosticItemKind.IN_PROGRESS,
        origin = if (live == null) {
            SimklCwDiagnosticOrigin.PREVIOUS_STATE
        } else {
            SimklCwDiagnosticOrigin.LIVE_PLAYBACK
        },
        seed = null,
        displayed = displayed,
        exactWatchedCount = exact.size,
        furthest = furthest.toDiagnosticEpisode(),
        seedInExactHistory = null,
        displayedInExactHistory = matchingWatched != null,
        finding = finding
    )
}

private fun WatchProgress.isSimklDiagnosticItem(): Boolean =
    source == WatchProgress.SOURCE_SIMKL_PLAYBACK ||
        trackingProviderId.equals(TrackingProviderId.SIMKL.storageId, ignoreCase = true)

private fun WatchedItem.isSimklDiagnosticItem(): Boolean =
    trackingProviderId.equals(TrackingProviderId.SIMKL.storageId, ignoreCase = true)

private fun WatchProgress?.toDiagnosticEpisode(): SimklCwDiagnosticEpisode? =
    this?.episode?.let { episode -> SimklCwDiagnosticEpisode(season, episode) }

private fun WatchedItem?.toDiagnosticEpisode(): SimklCwDiagnosticEpisode? =
    this?.episode?.let { episode -> SimklCwDiagnosticEpisode(season, episode) }

private fun NextUpInfo.diagnosticKey(): SimklCwEpisodeKey =
    SimklCwEpisodeKey(contentId.diagnosticKey(), season, episode)

private data class SimklCwEpisodeKey(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

private fun SimklCwDiagnosticEpisode.compareTo(other: SimklCwDiagnosticEpisode): Int {
    val seasonComparison = (season ?: -1).compareTo(other.season ?: -1)
    return if (seasonComparison != 0) seasonComparison else episode.compareTo(other.episode)
}

private fun String.diagnosticKey(): String = trim().lowercase(Locale.ROOT)
