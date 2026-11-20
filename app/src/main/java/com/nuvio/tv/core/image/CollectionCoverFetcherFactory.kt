package com.nuvio.tv.core.image

import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ImageRequest
import coil3.request.Options
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val collectionCoverKey = Extras.Key(default = false)
private const val MAX_ACTIVE_COLLECTION_COVER_FETCHES = 4

internal fun ImageRequest.Builder.collectionCover(): ImageRequest.Builder = apply {
    extras[collectionCoverKey] = true
}

internal class CollectionCoverFetcherFactory(
    private val delegate: Fetcher.Factory<Uri>
) : Fetcher.Factory<Uri> {
    private val slots = Semaphore(MAX_ACTIVE_COLLECTION_COVER_FETCHES)

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val fetcher = delegate.create(data, options, imageLoader) ?: return null
        if (options.extras[collectionCoverKey] != true) return fetcher

        // Queue cold cover fetches before starting their HTTP call timeout.
        // Coil serves decoded memory-cache hits without reaching a fetcher.
        return object : Fetcher {
            override suspend fun fetch(): FetchResult? = slots.withPermit { fetcher.fetch() }
        }
    }
}
