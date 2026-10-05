package com.nuvio.tv.data.wetrakr

import java.time.Instant

class WeTrakrSyncEngine(
    private val remote: WeTrakrSyncRemote,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun synchronize(previous: WeTrakrSyncSnapshot): WeTrakrSyncSnapshot {
        val activities = remote.activities()
        val before = previous.activities?.takeIf { previous.isInitialized }
        val invalid = previous.invalidatedBuckets
        val movies = watched(
            previous, before, activities, WeTrakrItemType.MOVIE,
            WeTrakrSyncBucket.WATCHED_MOVIES in invalid, "movies"
        )
        val episodes = watched(
            previous, before, activities, WeTrakrItemType.EPISODE,
            WeTrakrSyncBucket.WATCHED_EPISODES in invalid, "episodes"
        )
        val playback = if (before == null || WeTrakrSyncBucket.PLAYBACK in invalid ||
            activities.changed(before, "movies.all", "episodes.all", "shows.all")) {
            remote.playback(WeTrakrItemType.MOVIE) + remote.playback(WeTrakrItemType.EPISODE)
        } else previous.playback
        val watchlist = if (before == null || WeTrakrSyncBucket.WATCHLIST in invalid ||
            activities.changed(before, *WATCHLIST_STAMPS)) {
            remote.watchlist(WeTrakrItemType.MOVIE) + remote.watchlist(WeTrakrItemType.SHOW)
        } else previous.watchlist
        return previous.copy(
            watched = movies + episodes,
            playback = playback,
            watchlist = watchlist,
            activities = activities,
            checkedAtEpochMs = now(),
            isInitialized = true,
            invalidatedBuckets = emptySet()
        )
    }

    private suspend fun watched(
        previous: WeTrakrSyncSnapshot,
        before: WeTrakrActivities?,
        activities: WeTrakrActivities,
        type: WeTrakrItemType,
        invalidated: Boolean,
        section: String
    ): List<WeTrakrWatchedRecord> {
        val cached = previous.watched.filter { it.type == type }
        val watchedStamp = "$section.last_tracking_watched_at"
        val removedStamp = "$section.last_tracking_removed_at"
        if (before == null || invalidated || activities.changed(before, removedStamp)) {
            return remote.watched(type, null)
        }
        if (!activities.changed(before, watchedStamp)) return cached
        val since = before[watchedStamp] ?: return remote.watched(type, null)
        return mergeWeTrakrWatched(cached, remote.watched(type, overlap(since)))
    }

    private fun overlap(stamp: String): String =
        Instant.ofEpochMilli(weTrakrTimestamp(stamp) - OVERLAP_MS).toString()

    private companion object {
        const val OVERLAP_MS = 60_000L
        val WATCHLIST_STAMPS = arrayOf(
            "movies.last_tracking_planning_at",
            "shows.last_tracking_planning_at",
            "movies.last_tracking_removed_at",
            "shows.last_tracking_removed_at"
        )
    }
}

internal fun mergeWeTrakrWatched(
    cached: List<WeTrakrWatchedRecord>,
    changes: List<WeTrakrWatchedRecord>
): List<WeTrakrWatchedRecord> =
    cached.filterNot { record -> changes.any { it.sameItem(record) } } + changes

internal fun WeTrakrWatchedRecord.sameItem(other: WeTrakrWatchedRecord): Boolean =
    type == other.type && media.ids.matches(other.media.ids) && season == other.season && episode == other.episode
