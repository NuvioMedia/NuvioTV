package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.extractTrackingYear
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import java.time.Instant

enum class WeTrakrUploadKind { WATCHED, LIBRARY }

data class WeTrakrUnmatchedTitle(
    val kind: WeTrakrUploadKind,
    val title: String,
    val count: Int
)

class WeTrakrUploadGroup internal constructor(
    val kind: WeTrakrUploadKind,
    internal val changes: List<WeTrakrTrackingChange> = emptyList(),
    val alreadyOnWeTrakr: Int = 0,
    val unmatched: List<WeTrakrUnmatchedTitle> = emptyList(),
    val notFound: List<WeTrakrUnmatchedTitle> = emptyList()
) {
    val toUpload: Int
        get() = changes.size

    val unmatchedCount: Int
        get() = unmatched.sumOf { it.count }
}

data class WeTrakrUploadPreview(
    val watched: WeTrakrUploadGroup,
    val library: WeTrakrUploadGroup
) {
    val groups: List<WeTrakrUploadGroup>
        get() = listOf(watched, library)

    val unmatched: List<WeTrakrUnmatchedTitle>
        get() = groups.flatMap { it.unmatched }

    val notFound: List<WeTrakrUnmatchedTitle>
        get() = groups.flatMap { it.notFound }

    fun requestCount(kinds: Set<WeTrakrUploadKind>): Int = groups
        .filter { it.kind in kinds }
        .sumOf { (it.toUpload + WETRAKR_UPLOAD_BATCH_SIZE - 1) / WETRAKR_UPLOAD_BATCH_SIZE }
}

internal const val WETRAKR_UPLOAD_BATCH_SIZE = 500

internal fun weTrakrUploadPreview(
    watched: List<WatchedItem>,
    library: List<SavedLibraryItem>,
    snapshot: WeTrakrSyncSnapshot,
    progress: List<WatchProgress> = emptyList(),
    notFound: Set<String> = emptySet()
): WeTrakrUploadPreview {
    val remoteWatched = snapshot.watched.flatMapTo(HashSet()) { record ->
        record.media.ids.aliases().map { weTrakrUploadKey(record.type, it, record.season, record.episode) }
    }
    val remoteTitles = buildSet {
        snapshot.watched.forEach { record -> record.media.ids.aliases().forEach { add(titleKey(record.type, it)) } }
        snapshot.playback.forEach { record -> record.media.ids.aliases().forEach { add(titleKey(record.type, it)) } }
        snapshot.watchlist.forEach { item -> item.media.ids.aliases().forEach { add(titleKey(item.type, it)) } }
    }
    val localWatched = watched + progress.map { it.toWatchedItem() }
    val localTitles = localWatched.flatMapTo(HashSet()) { it.titleKeys() }
    return WeTrakrUploadPreview(
        watched = (watched + progress.filter { it.isCompleted() }.map { it.toWatchedItem() }).uploadGroup(
            kind = WeTrakrUploadKind.WATCHED,
            target = { it.weTrakrTarget() },
            trackedAt = { it.watchedAt },
            title = { it.title.ifBlank { it.contentId } },
            groupKey = { it.contentId },
            notFound = notFound,
            isRemote = { target ->
                target.media.ids.aliases().any { weTrakrUploadKey(target.type, it, target.season, target.episode) in remoteWatched }
            }
        ),
        library = library.uploadGroup(
            kind = WeTrakrUploadKind.LIBRARY,
            target = { it.weTrakrTarget() },
            trackedAt = { it.addedAt },
            title = { it.name.ifBlank { it.id } },
            groupKey = { it.id },
            notFound = notFound,
            isRemote = { target ->
                target.media.ids.aliases().any { titleKey(target.type, it).let { key -> key in remoteTitles || key in localTitles } }
            }
        )
    )
}

private inline fun <T> List<T>.uploadGroup(
    kind: WeTrakrUploadKind,
    target: (T) -> WeTrakrTarget?,
    trackedAt: (T) -> Long,
    title: (T) -> String,
    groupKey: (T) -> String,
    notFound: Set<String>,
    isRemote: (WeTrakrTarget) -> Boolean
): WeTrakrUploadGroup {
    val seen = HashSet<String>()
    val changes = ArrayList<WeTrakrTrackingChange>()
    val unmatched = LinkedHashMap<String, Pair<String, Int>>()
    val missing = LinkedHashMap<String, Pair<String, Int>>()
    var already = 0
    for (item in this) {
        val resolved = target(item)
        if (resolved == null) {
            unmatched.count(groupKey(item), title(item))
            continue
        }
        if (!seen.add(resolved.uploadKey())) continue
        if (isRemote(resolved)) {
            already++
            continue
        }
        if (resolved.uploadKey() in notFound) {
            missing.count(groupKey(item), title(item))
            continue
        }
        val at = trackedAt(item).takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() }
        changes += WeTrakrTrackingChange(resolved, at)
    }
    return WeTrakrUploadGroup(
        kind = kind,
        changes = changes,
        alreadyOnWeTrakr = already,
        unmatched = unmatched.values.map { (name, count) -> WeTrakrUnmatchedTitle(kind, name, count) },
        notFound = missing.values.map { (name, count) -> WeTrakrUnmatchedTitle(kind, name, count) }
    )
}

private fun MutableMap<String, Pair<String, Int>>.count(key: String, title: String) {
    val previous = get(key)
    put(key, (previous?.first ?: title) to ((previous?.second ?: 0) + 1))
}

private fun WatchProgress.toWatchedItem() = WatchedItem(
    contentId = contentId,
    contentType = contentType,
    title = name,
    season = season,
    episode = episode,
    watchedAt = lastWatched
)

private fun WatchedItem.weTrakrTarget(): WeTrakrTarget? {
    val ids = parseTrackingExternalIds(contentId).toWeTrakrIds() ?: return null
    val media = WeTrakrMedia(ids, title = title.takeIf(String::isNotBlank))
    if (contentType.isMovieType()) return WeTrakrTarget(WeTrakrItemType.MOVIE, media)
    if (!contentType.isShowType()) return null
    val season = season?.takeIf { it >= 0 } ?: ANIME_ENTRY_SEASON.takeIf { ids.isAnimeOnly } ?: return null
    val number = episode?.takeIf { it > 0 } ?: return null
    return WeTrakrTarget(WeTrakrItemType.EPISODE, media, season, number)
}

private fun WatchedItem.titleKeys(): List<String> {
    val ids = parseTrackingExternalIds(contentId).toWeTrakrIds() ?: return emptyList()
    val type = when {
        contentType.isMovieType() -> WeTrakrItemType.MOVIE
        contentType.isShowType() -> WeTrakrItemType.SHOW
        else -> return emptyList()
    }
    return ids.aliases().map { titleKey(type, it) }
}

private fun SavedLibraryItem.weTrakrTarget(): WeTrakrTarget? {
    val ids = parseTrackingExternalIds(id).toWeTrakrIds() ?: return null
    val type = when {
        type.isMovieType() -> WeTrakrItemType.MOVIE
        type.isShowType() -> WeTrakrItemType.SHOW
        else -> return null
    }
    return WeTrakrTarget(
        type,
        WeTrakrMedia(ids, title = name.takeIf(String::isNotBlank), year = extractTrackingYear(releaseInfo), poster = poster)
    )
}

private fun String.isMovieType(): Boolean = trim().lowercase() in setOf("movie", "film")

private fun String.isShowType(): Boolean = trim().lowercase() in setOf("series", "show", "anime")

internal fun WeTrakrTarget.uploadKey(): String =
    weTrakrUploadKey(type, media.ids.contentId.orEmpty(), season, episode)

private fun titleKey(type: WeTrakrItemType, id: String): String =
    "${if (type == WeTrakrItemType.MOVIE) "movie" else "show"}|$id"
