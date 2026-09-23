package com.nuvio.tv.data.mdblist

import android.util.Log
import com.nuvio.tv.domain.model.MDBListRatings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MdbListRatingsLoader(
    private val client: MdbListRatingsClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class RequestKey(
        val mediaType: String,
        val imdbId: String,
        val credential: MdbListRatingsCredential
    )

    private data class CacheEntry(val ratings: MDBListRatings, val expiresAtMs: Long)

    private val lock = Any()
    private val cache = mutableMapOf<RequestKey, CacheEntry>()
    private val inFlight = mutableMapOf<RequestKey, CompletableDeferred<MDBListRatings?>>()
    private val pending = mutableMapOf<RequestKey, CompletableDeferred<MDBListRatings?>>()
    private var batchScheduled = false

    suspend fun getRatings(
        mediaType: String,
        imdbId: String,
        credential: MdbListRatingsCredential
    ): MDBListRatings? {
        client.checkCredential(credential)
        val key = RequestKey(mediaType, imdbId, credential)
        val deferred = synchronized(lock) {
            cache[key]?.let { cached ->
                if (cached.expiresAtMs > now()) return cached.ratings
                cache.remove(key)
            }
            inFlight[key] ?: CompletableDeferred<MDBListRatings?>().also { created ->
                inFlight[key] = created
                pending[key] = created
                if (!batchScheduled) {
                    batchScheduled = true
                    scope.launch {
                        delay(BATCH_WINDOW_MS)
                        flushPending()
                    }
                }
            }
        }
        val ratings = deferred.await()
        client.checkCredential(credential)
        return ratings
    }

    private suspend fun flushPending() {
        val requests = synchronized(lock) {
            batchScheduled = false
            pending.toList().also { pending.clear() }
        }
        requests.groupBy { (key, _) -> key.mediaType to key.credential }.values.forEach { group ->
            group.chunked(MAX_BATCH_SIZE).forEach { fetchBatch(it) }
        }
    }

    private suspend fun fetchBatch(batch: List<Pair<RequestKey, CompletableDeferred<MDBListRatings?>>>) {
        val first = batch.first().first
        try {
            client.checkCredential(first.credential)
            val ratings = if (batch.size == 1) {
                val media = requireNotNull(client.getMedia(first.mediaType, first.imdbId, first.credential))
                mapOf(first.imdbId to media.toRatings())
            } else {
                requireNotNull(client.getMediaBatch(first.mediaType, batch.map { it.first.imdbId }, first.credential))
                    .mapNotNull { media -> media.resolvedImdbId()?.let { it to media.toRatings() } }.toMap()
            }
            client.checkCredential(first.credential)
            synchronized(lock) {
                batch.forEach { (key, deferred) ->
                    val result = ratings[key.imdbId] ?: MDBListRatings()
                    cache[key] = CacheEntry(result, now() + CACHE_TTL_MS)
                    inFlight.remove(key)
                    deferred.complete(result)
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) Log.w("MdbListRatings", "Ratings request failed for ${batch.size} items")
            synchronized(lock) {
                batch.forEach { (key, deferred) ->
                    inFlight.remove(key)
                    if (error is CancellationException) deferred.completeExceptionally(error)
                    else deferred.complete(null)
                }
            }
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 200
        const val BATCH_WINDOW_MS = 50L
        const val CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
