package com.nuvio.tv.core.image

import coil3.Extras
import coil3.network.NetworkClient
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.ImageRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val collectionCoverKey = Extras.Key(default = false)
private const val MAX_ACTIVE_COLLECTION_COVER_FETCHES = 4

internal fun ImageRequest.Builder.collectionCover(): ImageRequest.Builder = apply {
    extras[collectionCoverKey] = true
}

internal class CollectionCoverNetworkClient(
    private val delegate: NetworkClient
) : NetworkClient {
    private val slots = Semaphore(MAX_ACTIVE_COLLECTION_COVER_FETCHES)

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (NetworkResponse) -> T
    ): T {
        if (request.extras[collectionCoverKey] != true) return delegate.executeRequest(request, block)

        // Coil reaches this point after checking its disk cache. Keep the
        // permit through the callback, where Coil consumes the response body.
        return slots.withPermit { delegate.executeRequest(request, block) }
    }
}
