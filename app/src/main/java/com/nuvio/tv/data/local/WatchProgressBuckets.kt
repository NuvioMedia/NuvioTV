package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.domain.model.WatchProgress

internal const val WATCH_PROGRESS_METADATA_FEATURE = "watch_progress_preferences"
internal const val WATCH_PROGRESS_RECENT_FEATURE = "watch_progress_recent_preferences"
internal const val WATCH_PROGRESS_ARCHIVE_FEATURE = "watch_progress_archive_preferences"
internal const val WATCH_PROGRESS_STORAGE_VERSION = 1
internal const val WATCH_PROGRESS_RECENT_LIMIT = 200

internal val watchProgressEntriesKey = stringPreferencesKey("watch_progress_map")
internal val watchProgressStorageVersionKey = intPreferencesKey("watch_progress_storage_version")

internal data class WatchProgressBuckets(
    val recent: Map<String, WatchProgress>,
    val archive: Map<String, WatchProgress>
)

internal fun splitWatchProgressEntries(
    entries: Map<String, WatchProgress>,
    recentLimit: Int = WATCH_PROGRESS_RECENT_LIMIT
): WatchProgressBuckets {
    val episodeContentIds = entries.values
        .asSequence()
        .filter { it.season != null && it.episode != null }
        .map { it.contentId }
        .toSet()
    val normalized = entries.filterNot { (key, progress) ->
        key == progress.contentId &&
            progress.season == null &&
            progress.episode == null &&
            progress.contentId in episodeContentIds
    }
    val sorted = normalized.entries.sortedWith(
        compareByDescending<Map.Entry<String, WatchProgress>> { it.value.lastWatched }
            .thenByDescending { it.key }
    )
    val boundedLimit = recentLimit.coerceAtLeast(0)
    val recent = LinkedHashMap<String, WatchProgress>(minOf(sorted.size, boundedLimit))
    val archive = LinkedHashMap<String, WatchProgress>((sorted.size - boundedLimit).coerceAtLeast(0))
    sorted.forEachIndexed { index, entry ->
        if (index < boundedLimit) {
            recent[entry.key] = entry.value
        } else {
            archive[entry.key] = entry.value
        }
    }
    return WatchProgressBuckets(recent = recent, archive = archive)
}

internal fun mergeWatchProgressBuckets(
    recent: Map<String, WatchProgress>,
    archive: Map<String, WatchProgress>
): MutableMap<String, WatchProgress> {
    return LinkedHashMap<String, WatchProgress>(archive.size + recent.size).apply {
        putAll(archive)
        putAll(recent)
    }
}
