package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class MdbListScrobbleReceipt(
    val target: MdbListMutationTarget,
    val action: TrackingScrobbleAction,
    val progress: Float,
    val timestamp: String,
    val playbackId: Long?
) {
    val isWatched: Boolean
        get() = action != TrackingScrobbleAction.START && progress >= MdbListProgressProjection.COMPLETION_PERCENT
}

internal fun decodeMdbListScrobbleReceipt(
    response: MdbListHttpResponse,
    target: MdbListMutationTarget,
    action: TrackingScrobbleAction,
    nowEpochMs: Long
): MdbListScrobbleReceipt {
    val body = mdbListResponseElement(response.body).objectValue()
    val acceptedActions = if (action == TrackingScrobbleAction.START) setOf("start") else setOf("pause", "stop", "scrobble")
    if (body.text("action") !in acceptedActions) throw MdbListDecodingException()
    val progress = body.text("progress")?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
        ?: throw MdbListDecodingException()
    val episode = body.objectValue("episode")
    val parent = body.objectValue(if (target.type == MdbListItemType.MOVIE) "movie" else "show")
        ?: episode?.objectValue("show")
    val media = parent?.let(::decodeMdbListMedia)?.also {
        if (!it.ids.matches(target.media.ids)) throw MdbListDecodingException()
    }?.merged(target.media) ?: target.media
    val resolved = target.copy(
        media = media,
        season = episode?.integer("season") ?: target.season,
        episode = episode?.integer("number", "episode") ?: target.episode,
        episodeTitle = episode?.text("title", "name") ?: target.episodeTitle,
        episodeTmdbId = episode?.objectValue("ids")?.number("tmdb", "tmdbid") ?: target.episodeTmdbId,
        episodeTvdbId = episode?.objectValue("ids")?.number("tvdb", "tvdbid") ?: target.episodeTvdbId
    )
    if (resolved.type == MdbListItemType.EPISODE && (resolved.season == null || resolved.season < 0 ||
        resolved.episode == null || resolved.episode < 1)) throw MdbListDecodingException()
    val timestamp = body.timestamp("watched_at", "paused_at")
        ?: body.timestamp("started_at").takeIf { action == TrackingScrobbleAction.START }
        ?: response.header("Date")?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }.getOrNull()
    } ?: Instant.ofEpochMilli(nowEpochMs).toString()
    return MdbListScrobbleReceipt(resolved, action, progress, timestamp, body.number("id")?.takeIf { it > 0 })
}

internal fun MdbListSyncSnapshot.applyScrobble(
    receipt: MdbListScrobbleReceipt,
    mediaIndex: MdbListMediaIndex? = null
): MdbListSyncSnapshot {
    val target = receipt.target
    val remainingPlayback = if (playback.any(target::matches)) playback.filterNot(target::matches) else playback
    if (receipt.action == TrackingScrobbleAction.START) {
        return if (remainingPlayback === playback) this else copy(playback = remainingPlayback)
    }
    if (!receipt.isWatched) {
        val newSession = MdbListPlayback(
            id = receipt.playbackId ?: this.playback.firstOrNull(target::matches)?.id,
            type = target.type,
            media = resolveMediaForPlayback(target.type, target.media, mediaIndex),
            progress = receipt.progress,
            updatedAt = receipt.timestamp,
            season = target.season,
            episode = target.episode,
            episodeTitle = target.episodeTitle,
            episodeTmdbId = target.episodeTmdbId,
            episodeTvdbId = target.episodeTvdbId
        )
        val normalizedPlayback = (remainingPlayback + newSession)
            .groupBy { it.id?.let { id -> "id:$id" } ?: "${it.type}:${it.media.ids.key}:${it.season}:${it.episode}" }
            .map { (_, records) -> records.maxBy { mdbListTimestamp(it.updatedAt) } }
        return copy(watched = this.watched, playback = normalizedPlayback)
    }
    if (mediaIndex != null) {
        return applyWatchedScrobbleWithIndex(target, receipt.timestamp, remainingPlayback, mediaIndex)
    }
    val resolvedTarget = target.copy(media = resolveMediaForPlayback(target.type, target.media, null))
    val watched = this.watched.filterNot { record -> target.matches(record) || resolvedTarget.matches(record) } +
        resolvedTarget.watched(receipt.timestamp)
    return copy(watched = watched, playback = remainingPlayback).normalizeMedia()
}

private fun MdbListSyncSnapshot.applyWatchedScrobbleWithIndex(
    target: MdbListMutationTarget,
    timestamp: String,
    remainingPlayback: List<MdbListPlayback>,
    mediaIndex: MdbListMediaIndex
): MdbListSyncSnapshot {
    val isMovie = target.type == MdbListItemType.MOVIE
    val initialResolved = resolveMediaForPlayback(target.type, target.media, mediaIndex)
    val canonicalMedia = initialResolved.merged(mediaIndex.resolveMedia(target.type, initialResolved))
    val resolvedTarget = target.copy(media = canonicalMedia)
    val newWatchedRecord = resolvedTarget.watched(timestamp)

    val updatedWatched = ArrayList<MdbListWatchedRecord>(this.watched.size + 1)
    val indexByKey = HashMap<String, Int>()

    for (record in this.watched) {
        if ((record.type == MdbListItemType.MOVIE) == isMovie && record.media.ids.matches(canonicalMedia.ids)) {
            if (target.matches(record) || resolvedTarget.matches(record)) continue
            val normalizedMedia = canonicalMedia.merged(record.media)
            val normalizedRecord = if (record.media == normalizedMedia) record else record.copy(media = normalizedMedia)
            val key = normalizedRecord.key
            val existingIdx = indexByKey[key]
            if (existingIdx == null) {
                indexByKey[key] = updatedWatched.size
                updatedWatched.add(normalizedRecord)
            } else if (mdbListTimestamp(normalizedRecord.watchedAt) > mdbListTimestamp(updatedWatched[existingIdx].watchedAt)) {
                updatedWatched[existingIdx] = normalizedRecord
            }
        } else {
            updatedWatched.add(record)
        }
    }

    val newKey = newWatchedRecord.key
    val existingNewIdx = indexByKey[newKey]
    if (existingNewIdx == null) {
        updatedWatched.add(newWatchedRecord)
    } else if (mdbListTimestamp(newWatchedRecord.watchedAt) >= mdbListTimestamp(updatedWatched[existingNewIdx].watchedAt)) {
        updatedWatched[existingNewIdx] = newWatchedRecord
    }

    val updatedPlayback = if (remainingPlayback.isEmpty()) {
        remainingPlayback
    } else {
        remainingPlayback.map { session ->
            if ((session.type == MdbListItemType.MOVIE) == isMovie && session.media.ids.matches(canonicalMedia.ids)) {
                val normalizedMedia = canonicalMedia.merged(session.media)
                if (session.media == normalizedMedia) session else session.copy(media = normalizedMedia)
            } else {
                session
            }
        }.groupBy { it.id?.let { id -> "id:$id" } ?: "${it.type}:${it.media.ids.key}:${it.season}:${it.episode}" }
            .map { (_, records) -> records.maxBy { mdbListTimestamp(it.updatedAt) } }
    }

    return copy(watched = updatedWatched, playback = updatedPlayback)
}

private fun MdbListSyncSnapshot.resolveMediaForPlayback(
    type: MdbListItemType,
    media: MdbListMedia,
    mediaIndex: MdbListMediaIndex? = null
): MdbListMedia {
    val aliases = media.ids.aliases()
    if (aliases.isEmpty()) return media
    val isMovie = type == MdbListItemType.MOVIE
    var merged = mediaIndex?.resolveMedia(type, media) ?: run {
        var acc = media
        watched.forEach { record ->
            if ((record.type == MdbListItemType.MOVIE) == isMovie && record.media.ids.aliases().any { it in aliases }) {
                acc = acc.merged(record.media)
            }
        }
        acc
    }
    playback.forEach { record ->
        if ((record.type == MdbListItemType.MOVIE) == isMovie && record.media.ids.aliases().any { it in aliases }) {
            merged = merged.merged(record.media)
        }
    }
    return merged
}
