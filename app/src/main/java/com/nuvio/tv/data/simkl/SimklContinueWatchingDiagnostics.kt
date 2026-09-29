package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingDiagnosticIdentity
import com.nuvio.tv.domain.model.WatchProgress
import java.util.Locale

internal enum class SimklSeedDiagnosticFlag {
    API_COUNT_EXCEEDS_EXACT_HISTORY,
    API_REPORTS_CAUGHT_UP,
    COMPLETED_STATUS,
    DROPPED_STATUS,
    NO_EXACT_EPISODE_HISTORY,
    ON_HOLD_STATUS,
    PLAYBACK_PRESENT,
    SEED_BEHIND_FURTHEST,
    SEED_MISSING,
    SEED_NOT_IN_EXACT_HISTORY,
    SUMMARY_MARKER_UNPARSEABLE,
    TIMESTAMP_TIE
}

internal data class SimklDiagnosticEpisode(
    val season: Int?,
    val episode: Int
) {
    fun display(): String = season?.let { value -> "S${value}E$episode" } ?: "E$episode"
}

internal data class SimklSeedDiagnosticRecord(
    val key: String,
    val mediaType: SimklMediaType,
    val status: SimklListStatus?,
    val apiWatchedCount: Int,
    val apiTotalCount: Int,
    val apiUnairedCount: Int,
    val returnedEpisodeRows: Int,
    val exactWatchedCount: Int,
    val seedEligibleExactCount: Int,
    val nullTimestampRows: Int,
    val apiLastWatched: SimklDiagnosticEpisode?,
    val apiNextToWatch: SimklDiagnosticEpisode?,
    val furthest: SimklDiagnosticEpisode?,
    val mostRecent: SimklDiagnosticEpisode?,
    val selected: SimklDiagnosticEpisode?,
    val selectedTimestampTieCount: Int,
    val playbackCount: Int,
    val flags: Set<SimklSeedDiagnosticFlag>
) {
    fun logLine(): String = buildString {
        append("projection key=")
        append(key)
        append(" kind=")
        append(mediaType.apiValue)
        append(" status=")
        append(status?.apiValue ?: "none")
        append(" apiWatched=")
        append(apiWatchedCount)
        append(" apiTotal=")
        append(apiTotalCount)
        append(" apiUnaired=")
        append(apiUnairedCount)
        append(" rows=")
        append(returnedEpisodeRows)
        append(" exact=")
        append(exactWatchedCount)
        append(" seedEligible=")
        append(seedEligibleExactCount)
        append(" nullWatchedAt=")
        append(nullTimestampRows)
        append(" apiLast=")
        append(apiLastWatched?.display() ?: "none")
        append(" apiNext=")
        append(apiNextToWatch?.display() ?: "none")
        append(" furthest=")
        append(furthest?.display() ?: "none")
        append(" recent=")
        append(mostRecent?.display() ?: "none")
        append(" selected=")
        append(selected?.display() ?: "none")
        append(" selectedTie=")
        append(selectedTimestampTieCount)
        append(" playback=")
        append(playbackCount)
        append(" flags=")
        append(if (flags.isEmpty()) "NONE" else flags.joinToString(",") { flag -> flag.name })
    }
}

internal data class SimklSeedDiagnosticReport(
    val preferFurthestEpisode: Boolean,
    val entryCount: Int,
    val seriesCount: Int,
    val exactWatchedCount: Int,
    val seedCount: Int,
    val playbackCount: Int,
    val records: List<SimklSeedDiagnosticRecord>
) {
    fun summaryLine(detailLimit: Int): String {
        val countGaps = records.count {
            SimklSeedDiagnosticFlag.API_COUNT_EXCEEDS_EXACT_HISTORY in it.flags
        }
        val ties = records.count { SimklSeedDiagnosticFlag.TIMESTAMP_TIE in it.flags }
        val caughtUp = records.count {
            SimklSeedDiagnosticFlag.API_REPORTS_CAUGHT_UP in it.flags
        }
        val seedMismatches = records.count {
            SimklSeedDiagnosticFlag.SEED_BEHIND_FURTHEST in it.flags ||
                SimklSeedDiagnosticFlag.SEED_NOT_IN_EXACT_HISTORY in it.flags
        }
        return "projection-summary entries=$entryCount series=$seriesCount exactEpisodes=$exactWatchedCount " +
            "seeds=$seedCount playback=$playbackCount mode=" +
            "${if (preferFurthestEpisode) "furthest" else "recent"} countGaps=$countGaps " +
            "timestampTies=$ties apiCaughtUp=$caughtUp seedMismatches=$seedMismatches " +
            "details=${records.size.coerceAtMost(detailLimit)}/${records.size}"
    }
}

internal fun buildSimklSeedDiagnosticReport(
    snapshot: SimklSyncSnapshot,
    seeds: List<WatchProgress>,
    preferFurthestEpisode: Boolean,
    watchedProjection: SimklWatchedProjection = snapshot.toSimklWatchedProjection(),
    aliasFor: (String) -> String = TrackingDiagnosticIdentity::alias
): SimklSeedDiagnosticReport {
    val watchedByContent = watchedProjection.items
        .filter { item -> item.season != null && item.episode != null }
        .groupBy { item -> item.contentId.diagnosticKey() }
    val seedsByContent = seeds.associateBy { progress -> progress.contentId.diagnosticKey() }
    val playbackByContent = snapshot.playback
        .mapNotNull { session -> session.toWatchProgress(snapshot.entries) }
        .groupBy { progress -> progress.contentId.diagnosticKey() }
    val seedOrder = seeds.withIndex().associate { (index, seed) ->
        seed.contentId.diagnosticKey() to index
    }
    val records = snapshot.entries
        .asSequence()
        .filter { entry -> entry.mediaType != SimklMediaType.MOVIES }
        .mapNotNull { entry ->
            val contentId = entry.media?.canonicalContentId() ?: return@mapNotNull null
            val lookupKey = contentId.diagnosticKey()
            val allExact = watchedByContent[lookupKey].orEmpty()
            val exact = allExact.filter { item -> item.season != 0 }
            val selected = seedsByContent[lookupKey]
            val furthestItem = exact.maxWithOrNull(
                compareBy(
                    { item -> item.season ?: -1 },
                    { item -> item.episode ?: -1 },
                    { item -> item.watchedAt }
                )
            )
            val recentItem = exact.maxWithOrNull(
                compareBy(
                    { item -> item.watchedAt },
                    { item -> item.season ?: -1 },
                    { item -> item.episode ?: -1 }
                )
            )
            val selectedTieCount = selected?.let { seed ->
                exact.count { item -> item.watchedAt == seed.lastWatched }
            } ?: 0
            val returnedRows = entry.seasons.sumOf { season -> season.episodes.size }
            val nullTimestampRows = entry.seasons.sumOf { season ->
                season.episodes.count { episode -> parseSimklUtcEpochMs(episode.watchedAt) == null }
            }
            val selectedInHistory = selected?.let { seed ->
                exact.any { item ->
                    item.season == seed.season &&
                        item.episode == seed.episode &&
                        item.watchedAt == seed.lastWatched
                }
            } ?: false
            val furthest = furthestItem.toDiagnosticEpisode()
            val selectedEpisode = selected.toDiagnosticEpisode()
            val flags = buildSet {
                if (entry.watchedEpisodesCount > allExact.size) {
                    add(SimklSeedDiagnosticFlag.API_COUNT_EXCEEDS_EXACT_HISTORY)
                }
                if (entry.watchedEpisodesCount > 0 && allExact.isEmpty()) {
                    add(SimklSeedDiagnosticFlag.NO_EXACT_EPISODE_HISTORY)
                }
                if (entry.nextToWatch == null && entry.watchedEpisodesCount > 0) {
                    add(SimklSeedDiagnosticFlag.API_REPORTS_CAUGHT_UP)
                }
                if (entry.status == SimklListStatus.COMPLETED) {
                    add(SimklSeedDiagnosticFlag.COMPLETED_STATUS)
                }
                if (entry.status == SimklListStatus.DROPPED) {
                    add(SimklSeedDiagnosticFlag.DROPPED_STATUS)
                }
                if (entry.status == SimklListStatus.ON_HOLD) {
                    add(SimklSeedDiagnosticFlag.ON_HOLD_STATUS)
                }
                if (
                    selected == null &&
                    exact.isNotEmpty() &&
                    !entry.status.hidesContinueWatching()
                ) {
                    add(SimklSeedDiagnosticFlag.SEED_MISSING)
                }
                if (selected != null && !selectedInHistory) {
                    add(SimklSeedDiagnosticFlag.SEED_NOT_IN_EXACT_HISTORY)
                }
                if (
                    preferFurthestEpisode &&
                    selectedEpisode != null &&
                    furthest != null &&
                    selectedEpisode != furthest
                ) {
                    add(SimklSeedDiagnosticFlag.SEED_BEHIND_FURTHEST)
                }
                if (selectedTieCount > 1) {
                    add(SimklSeedDiagnosticFlag.TIMESTAMP_TIE)
                }
                if (playbackByContent[lookupKey].orEmpty().isNotEmpty()) {
                    add(SimklSeedDiagnosticFlag.PLAYBACK_PRESENT)
                }
                if (
                    (entry.lastWatched != null && parseSimklEpisodeMarker(entry.lastWatched) == null) ||
                    (entry.nextToWatch != null && parseSimklEpisodeMarker(entry.nextToWatch) == null)
                ) {
                    add(SimklSeedDiagnosticFlag.SUMMARY_MARKER_UNPARSEABLE)
                }
            }
            val record = SimklSeedDiagnosticRecord(
                key = aliasFor(contentId),
                mediaType = entry.mediaType,
                status = entry.status,
                apiWatchedCount = entry.watchedEpisodesCount,
                apiTotalCount = entry.totalEpisodesCount,
                apiUnairedCount = entry.notAiredEpisodesCount,
                returnedEpisodeRows = returnedRows,
                exactWatchedCount = allExact.size,
                seedEligibleExactCount = exact.size,
                nullTimestampRows = nullTimestampRows,
                apiLastWatched = parseSimklEpisodeMarker(entry.lastWatched),
                apiNextToWatch = parseSimklEpisodeMarker(entry.nextToWatch),
                furthest = furthest,
                mostRecent = recentItem.toDiagnosticEpisode(),
                selected = selectedEpisode,
                selectedTimestampTieCount = selectedTieCount,
                playbackCount = playbackByContent[lookupKey].orEmpty().size,
                flags = flags
            )
            (seedOrder[lookupKey] ?: Int.MAX_VALUE) to record
        }
        .sortedWith(
            compareBy<Pair<Int, SimklSeedDiagnosticRecord>> { (order, _) -> order }
                .thenByDescending { (_, record) -> record.flags.size }
        )
        .map { (_, record) -> record }
        .toList()
    return SimklSeedDiagnosticReport(
        preferFurthestEpisode = preferFurthestEpisode,
        entryCount = snapshot.entries.size,
        seriesCount = records.size,
        exactWatchedCount = watchedProjection.items.count {
            it.season != null && it.episode != null && it.season != 0
        },
        seedCount = seeds.size,
        playbackCount = snapshot.playback.size,
        records = records
    )
}

private fun String.diagnosticKey(): String = trim().lowercase(Locale.ROOT)

private fun com.nuvio.tv.domain.model.WatchedItem?.toDiagnosticEpisode(): SimklDiagnosticEpisode? =
    this?.episode?.let { episode -> SimklDiagnosticEpisode(season, episode) }

private fun WatchProgress?.toDiagnosticEpisode(): SimklDiagnosticEpisode? =
    this?.episode?.let { episode -> SimklDiagnosticEpisode(season, episode) }

private fun parseSimklEpisodeMarker(value: String?): SimklDiagnosticEpisode? {
    val match = value?.trim()?.let(SIMKL_EPISODE_MARKER::matchEntire) ?: return null
    val season = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull()
    val episode = match.groupValues[2].toIntOrNull() ?: return null
    return SimklDiagnosticEpisode(season, episode)
}

private val SIMKL_EPISODE_MARKER = Regex("(?i)^(?:S(\\d+))?E(\\d+)$")
