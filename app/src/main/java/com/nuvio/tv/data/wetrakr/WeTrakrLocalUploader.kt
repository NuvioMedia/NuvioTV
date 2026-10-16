package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WeTrakrUploadStatus { RUNNING, PAUSED, DONE, CANCELLED, FAILED }

data class WeTrakrUploadRun(
    val scope: WeTrakrAuthScope,
    val status: WeTrakrUploadStatus,
    val total: Int,
    val sent: Int = 0,
    val added: Int = 0,
    val alreadyOnWeTrakr: Int = 0,
    val notFound: Int = 0,
    val failed: Int = 0,
    val pause: WeTrakrSyncPause? = null,
    val error: WeTrakrSyncError? = null
) {
    val isActive: Boolean
        get() = status == WeTrakrUploadStatus.RUNNING || status == WeTrakrUploadStatus.PAUSED

    val percent: Int
        get() = if (total == 0) 100 else (sent * 100 / total).coerceIn(0, 100)

    val left: Int
        get() = (total - sent).coerceAtLeast(0)
}

internal class WeTrakrUploadBatch(
    val kind: WeTrakrUploadKind,
    val changes: List<WeTrakrTrackingChange>,
    val idempotencyKey: String = UUID.randomUUID().toString()
)

class WeTrakrLocalUploader(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val auth: WeTrakrAuthStore,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutableRun = MutableStateFlow<WeTrakrUploadRun?>(null)
    private var job: Job? = null
    val run: StateFlow<WeTrakrUploadRun?> = mutableRun.asStateFlow()

    fun start(scope: WeTrakrAuthScope, preview: WeTrakrUploadPreview, kinds: Set<WeTrakrUploadKind>): Boolean {
        if (job?.isActive == true || !auth.isCurrent(scope)) return false
        val batches = weTrakrUploadBatches(preview, kinds)
        if (batches.isEmpty()) return false
        mutableRun.value = WeTrakrUploadRun(scope, WeTrakrUploadStatus.RUNNING, total = batches.sumOf { it.changes.size })
        job = coroutineScope.launch { upload(scope, batches) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearFinished() {
        if (mutableRun.value?.isActive != true) mutableRun.value = null
    }

    private suspend fun upload(scope: WeTrakrAuthScope, batches: List<WeTrakrUploadBatch>) {
        try {
            batches.forEachIndexed { index, batch ->
                send(scope, batch, hasMore = index < batches.lastIndex)
                if (index < batches.lastIndex) sleep(PACE_MS)
            }
            update(scope) { it.copy(status = WeTrakrUploadStatus.DONE, pause = null) }
        } catch (error: CancellationException) {
            update(scope) { it.copy(status = WeTrakrUploadStatus.CANCELLED, pause = null) }
            throw error
        } catch (error: Exception) {
            update(scope) { it.copy(status = WeTrakrUploadStatus.FAILED, pause = null, error = error.toWeTrakrSyncError()) }
        } finally {
            if (auth.isCurrent(scope)) sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        }
    }

    private suspend fun send(scope: WeTrakrAuthScope, batch: WeTrakrUploadBatch, hasMore: Boolean) {
        val buckets = batch.kind.buckets()
        while (true) {
            auth.checkScope(scope)
            val response = try {
                sync.write(scope, buckets) { snapshot ->
                    val response = api.post(
                        "/sync/tracking",
                        weTrakrTrackingBody(
                            batch.changes,
                            batch.kind.status(),
                            allowRewatch = false.takeIf { batch.kind == WeTrakrUploadKind.WATCHED }
                        ),
                        scope,
                        idempotencyKey = batch.idempotencyKey
                    )
                    snapshot.copy(invalidatedBuckets = snapshot.invalidatedBuckets + buckets) to response
                }
            } catch (error: WeTrakrApiException) {
                if (error.status != 429) throw error
                pauseUntil(scope, error.retryAtEpochMs ?: (now() + RETRY_MS), error.toWeTrakrSyncError())
                continue
            }
            val notFound = weTrakrNotFoundCount(response.body).coerceAtMost(batch.changes.size)
            val errored = weTrakrErrored(response.body)
            val already = errored.alreadyWatched.coerceAtMost(batch.changes.size - notFound)
            val failed = errored.other.coerceAtMost(batch.changes.size - notFound - already)
            update(scope) {
                it.copy(
                    status = WeTrakrUploadStatus.RUNNING,
                    sent = it.sent + batch.changes.size,
                    added = it.added + batch.changes.size - notFound - already - failed,
                    alreadyOnWeTrakr = it.alreadyOnWeTrakr + already,
                    notFound = it.notFound + notFound,
                    failed = it.failed + failed,
                    pause = null
                )
            }
            val remaining = response.header("X-Quota-Remaining")?.trim()?.toLongOrNull()
            if (hasMore && remaining != null && remaining <= QUOTA_HEADROOM) {
                pauseUntil(scope, weTrakrQuotaResetEpochMs(response, now()), WeTrakrSyncError.QUOTA_EXCEEDED)
            }
            return
        }
    }

    private suspend fun pauseUntil(scope: WeTrakrAuthScope, untilEpochMs: Long, reason: WeTrakrSyncError) {
        val until = maxOf(untilEpochMs, now() + 1_000L)
        update(scope) { it.copy(status = WeTrakrUploadStatus.PAUSED, pause = WeTrakrSyncPause(until, reason)) }
        sleep(until - now())
        update(scope) { it.copy(status = WeTrakrUploadStatus.RUNNING, pause = null) }
    }

    private fun update(scope: WeTrakrAuthScope, block: (WeTrakrUploadRun) -> WeTrakrUploadRun) {
        val current = mutableRun.value?.takeIf { it.scope == scope } ?: return
        mutableRun.value = block(current)
    }

    private companion object {
        const val PACE_MS = 1_500L
        const val RETRY_MS = 60_000L
        const val QUOTA_HEADROOM = 100L
    }
}

internal fun weTrakrUploadBatches(preview: WeTrakrUploadPreview, kinds: Set<WeTrakrUploadKind>): List<WeTrakrUploadBatch> =
    preview.groups.filter { it.kind in kinds }.flatMap { group ->
        group.changes.chunked(WETRAKR_UPLOAD_BATCH_SIZE).map { WeTrakrUploadBatch(group.kind, it) }
    }

private fun WeTrakrUploadKind.status(): String = when (this) {
    WeTrakrUploadKind.WATCHED -> "watched"
    WeTrakrUploadKind.LIBRARY -> "planning"
}

private fun WeTrakrUploadKind.buckets(): Set<WeTrakrSyncBucket> = when (this) {
    WeTrakrUploadKind.WATCHED -> setOf(WeTrakrSyncBucket.WATCHED_MOVIES, WeTrakrSyncBucket.WATCHED_EPISODES)
    WeTrakrUploadKind.LIBRARY -> setOf(WeTrakrSyncBucket.WATCHLIST)
}
