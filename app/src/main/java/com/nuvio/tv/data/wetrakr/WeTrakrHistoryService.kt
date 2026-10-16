package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeTrakrHistoryService @Inject constructor(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository
) {
    suspend fun add(scope: WeTrakrAuthScope, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        change(scope, items.toList(), remove = false)

    suspend fun remove(scope: WeTrakrAuthScope, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        change(scope, items.map { TrackingHistoryItem(it) }, remove = true)

    private suspend fun change(
        scope: WeTrakrAuthScope,
        items: List<TrackingHistoryItem>,
        remove: Boolean
    ): TrackingMutationResult {
        val mapped = items.mapNotNull { item ->
            item.media.weTrakrTarget()?.let { target ->
                WeTrakrTrackingChange(
                    target,
                    if (remove) null else Instant.ofEpochMilli(item.watchedAtEpochMs ?: System.currentTimeMillis()).toString()
                )
            }
        }.distinctBy { "${it.target.type}:${it.target.media.ids.contentId}:${it.target.season}:${it.target.episode}" }
            .let { changes ->
                val records = sync.currentSnapshot()?.watched.orEmpty()
                if (remove || records.isEmpty()) changes else changes.filterNot { it.watchedRecently(records) }
            }
        var failed = items.size - mapped.size
        val buckets = setOf(WeTrakrSyncBucket.WATCHED_MOVIES, WeTrakrSyncBucket.WATCHED_EPISODES)
        for (batch in mapped.chunked(BATCH_SIZE)) {
            failed += sync.write(scope, buckets) { snapshot ->
                val response = api.post(
                    if (remove) "/sync/tracking/remove/all" else "/sync/tracking",
                    weTrakrTrackingBody(batch, "watched", allowRewatch = if (remove) null else true),
                    scope
                )
                val notFound = (weTrakrNotFoundCount(response.body) + weTrakrErrored(response.body).total)
                    .coerceAtMost(batch.size)
                val expandsShow = batch.any { it.target.type == WeTrakrItemType.SHOW }
                val watched = if (remove) {
                    snapshot.watched.filterNot { record ->
                        batch.any { change ->
                            change.target.matches(record) || change.target.type == WeTrakrItemType.SHOW &&
                                record.type == WeTrakrItemType.EPISODE && change.target.media.ids.matches(record.media.ids)
                        }
                    }
                } else {
                    val added = batch.filter { it.target.type != WeTrakrItemType.SHOW }
                        .map { it.target.watched(requireNotNull(it.trackedAt)) }
                    snapshot.watched.filterNot { record -> added.any { it.sameItem(record) } } + added
                }
                val invalidated = if (notFound > 0 || expandsShow) buckets else emptySet()
                snapshot.copy(
                    watched = watched,
                    invalidatedBuckets = snapshot.invalidatedBuckets + invalidated
                ) to notFound
            }
        }
        if (sync.currentSnapshot()?.invalidatedBuckets?.isNotEmpty() == true) {
            sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        }
        return TrackingMutationResult(items.size, failed)
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}

private val WETRAKR_REWATCH_WINDOW: Duration = Duration.ofHours(24)

internal fun WeTrakrTrackingChange.watchedRecently(records: List<WeTrakrWatchedRecord>): Boolean {
    if (target.type == WeTrakrItemType.SHOW) return false
    val at = trackedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return records.any { record ->
        target.matches(record) && runCatching {
            Duration.between(Instant.parse(record.watchedAt), at).abs() < WETRAKR_REWATCH_WINDOW
        }.getOrDefault(false)
    }
}
