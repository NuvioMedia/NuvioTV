package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.WatchProgress

/**
 * Pure watch-progress sync merge rules used by [WatchProgressSyncService] and
 * [com.nuvio.tv.data.local.WatchProgressPreferences]. Kept free of Android /
 * Supabase so the Continue Watching resume-from-0 regressions can be reproduced
 * in unit tests.
 */
internal object WatchProgressSyncReducer {
    const val MIN_USABLE_POSITION_MS = 1_000L
    const val MIN_USABLE_PERCENT = 2f

    fun hasUsableResumeData(progress: WatchProgress): Boolean {
        if (progress.position >= MIN_USABLE_POSITION_MS) return true
        val percent = progress.progressPercent ?: return false
        return percent >= MIN_USABLE_PERCENT && percent < 100f
    }

    fun shouldPushToRemote(progress: WatchProgress): Boolean {
        val dummyCompletedSentinel = progress.position <= 1L &&
            progress.duration <= 1L &&
            progress.duration > 0L
        if (dummyCompletedSentinel) return false
        return hasUsableResumeData(progress) || progress.isCompleted()
    }

    fun episodeKey(contentId: String, season: Int, episode: Int): String {
        return "${contentId}_s${season}e${episode}"
    }

    fun isSeriesType(contentType: String): Boolean {
        return contentType.lowercase() in setOf("series", "tv")
    }

    fun canonicalizeForRemote(rawEntries: Map<String, WatchProgress>): Map<String, WatchProgress> {
        if (rawEntries.isEmpty()) return rawEntries

        val canonical = rawEntries.toMutableMap()
        rawEntries.forEach { (key, progress) ->
            val isSeriesMirrorKey = key == progress.contentId &&
                isSeriesType(progress.contentType) &&
                progress.season != null &&
                progress.episode != null
            if (!isSeriesMirrorKey) return@forEach

            val season = progress.season ?: return@forEach
            val episode = progress.episode ?: return@forEach
            val episodeProgress = rawEntries[episodeKey(progress.contentId, season, episode)]
                ?: return@forEach

            val exactMirror = progress.position == episodeProgress.position &&
                progress.duration == episodeProgress.duration &&
                progress.lastWatched == episodeProgress.lastWatched
            val episodeIsAtLeastAsFresh = episodeProgress.lastWatched >= progress.lastWatched - 1_000L

            if (exactMirror || episodeIsAtLeastAsFresh) {
                canonical.remove(key)
            }
        }

        return canonical.filterValues(::shouldPushToRemote)
    }

    fun normalizePulledEntries(
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
            .mapValues { (_, episodes) ->
                episodes.maxWithOrNull(
                    compareBy<WatchProgress> { it.lastWatched }
                        .thenBy { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            }

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

    /**
     * Newer [WatchProgress.lastWatched] wins, but an empty remote row must not
     * erase a local in-progress position. That overwrite is the Nuvio-sync
     * path that makes Continue Watching resume from 0:00.
     */
    fun shouldReplaceLocalWithRemote(
        local: WatchProgress?,
        remote: WatchProgress,
        lastSuccessfulPushMs: Long
    ): MergeDecision {
        if (local == null) return MergeDecision.AcceptRemote
        if (remote.lastWatched > local.lastWatched) {
            if (!hasUsableResumeData(remote) &&
                !remote.isCompleted() &&
                hasUsableResumeData(local)
            ) {
                return MergeDecision.KeepLocalEmptyRemote
            }
            return MergeDecision.AcceptRemote
        }
        if (local.lastWatched > remote.lastWatched && local.lastWatched > lastSuccessfulPushMs) {
            return MergeDecision.KeepLocalNewerUnsynced
        }
        return MergeDecision.KeepLocalAlreadySynced
    }

    fun shouldPreserveLocalMissingFromRemote(
        local: WatchProgress,
        lastSuccessfulPushMs: Long,
        isNonTraktId: ((String) -> Boolean)?
    ): Boolean {
        if (isNonTraktId != null && isNonTraktId(local.contentId)) return true
        return local.lastWatched > lastSuccessfulPushMs
    }

    enum class MergeDecision {
        AcceptRemote,
        KeepLocalEmptyRemote,
        KeepLocalNewerUnsynced,
        KeepLocalAlreadySynced
    }
}
