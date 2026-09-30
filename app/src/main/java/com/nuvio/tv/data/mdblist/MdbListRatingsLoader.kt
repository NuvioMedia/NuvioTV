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
        val mediaProvider: String,
        val mediaType: String,
        val mediaId: String,
        val credential: MdbListRatingsCredential
    )

    private data class CacheEntry(val ratings: MDBListRatings, val expiresAtMs: Long)

    private val lock = Any()
    private val cache = mutableMapOf<RequestKey, CacheEntry>()
    private val inFlight = mutableMapOf<RequestKey, CompletableDeferred<MDBListRatings?>>()
    private val pending = mutableMapOf<RequestKey, CompletableDeferred<MDBListRatings?>>()
    private var batchScheduled = false

    suspend fun getRatings(
        mediaProvider: String,
        mediaType: String,
        mediaId: String,
        credential: MdbListRatingsCredential
    ): MDBListRatings? {
        client.checkCredential(credential)
        val key = RequestKey(mediaProvider, mediaType, mediaId, credential)
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

    suspend fun getRatings(
        mediaType: String,
        imdbId: String,
        credential: MdbListRatingsCredential
    ): MDBListRatings? = getRatings("imdb", mediaType, imdbId, credential)

    private suspend fun flushPending() {
        val requests = synchronized(lock) {
            batchScheduled = false
            pending.toList().also { pending.clear() }
        }
        requests.groupBy { (key, _) -> Triple(key.mediaProvider, key.mediaType, key.credential) }
            .values.forEach { group ->
                group.chunked(MAX_BATCH_SIZE).forEach { fetchBatch(it) }
            }
    }

    private suspend fun fetchBatch(batch: List<Pair<RequestKey, CompletableDeferred<MDBListRatings?>>>) {
        val first = batch.first().first
        try {
            client.checkCredential(first.credential)
            val ratings = if (batch.size == 1) {
                val media = requireNotNull(client.getMedia(first.mediaProvider, first.mediaType, first.mediaId, first.credential))
                mapOf(first.mediaId to media.toRatings())
            } else {
                val provider = first.mediaProvider
                requireNotNull(client.getMediaBatch(provider, first.mediaType, batch.map { it.first.mediaId }, first.credential))
                    .mapNotNull { media ->
                        val responseId = resolveResponseId(media, provider) ?: return@mapNotNull null
                        responseId to media.toRatings()
                    }.toMap()
            }
            client.checkCredential(first.credential)
            synchronized(lock) {
                batch.forEach { (key, deferred) ->
                    val result = ratings[key.mediaId] ?: MDBListRatings()
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

    private fun resolveResponseId(
        media: com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto,
        provider: String
    ): String? {
        if (provider == "imdb") return media.resolvedImdbId()
        return media.ids?.get(provider)?.toString()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 200
        const val BATCH_WINDOW_MS = 50L
        const val CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
