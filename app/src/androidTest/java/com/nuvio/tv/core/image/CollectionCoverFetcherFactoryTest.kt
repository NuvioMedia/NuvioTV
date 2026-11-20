package com.nuvio.tv.core.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ColorImage
import coil3.ImageLoader
import coil3.Uri
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionCoverFetcherFactoryTest {
    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun coverFetchesWaitForSlotsWithoutBlockingOrdinaryImages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coverUrls = List(6) { index -> "https://covers.invalid/cover-$index.png" }
        val ordinaryUrl = "https://covers.invalid/ordinary.png"
        val fetchesStarted = Channel<String>(Channel.UNLIMITED)
        val releaseCovers = CompletableDeferred<Unit>()
        val delegate = Fetcher.Factory<Uri> { data, _, _ ->
            Fetcher {
                val url = data.toString()
                fetchesStarted.send(url)
                if (url != ordinaryUrl) releaseCovers.await()
                ImageFetchResult(ColorImage(), isSampled = false, dataSource = DataSource.MEMORY)
            }
        }
        // Let each request reach its first suspension before starting the next one.
        val testContext = coroutineContext.minusKey(Job)
        val loader = ImageLoader.Builder(context)
            .coroutineContext(testContext)
            .mainCoroutineContext(testContext)
            .memoryCache(null)
            .diskCache(null)
            .components { add(CollectionCoverFetcherFactory(delegate)) }
            .build()
        try {
            withTimeout(10_000) {
                val requests = coverUrls.map { url ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val request = ImageRequest.Builder(context).data(url).collectionCover().build()
                        loader.execute(request)
                    }
                }
                val startedBeforeRelease = List(4) { fetchesStarted.receive() }
                assertEquals(4, startedBeforeRelease.toSet().size)
                assertTrue(startedBeforeRelease.all { it in coverUrls })

                val ordinaryRequest = ImageRequest.Builder(context).data(ordinaryUrl).build()
                assertTrue(loader.execute(ordinaryRequest) is SuccessResult)
                assertEquals(ordinaryUrl, fetchesStarted.receive())
                assertTrue(fetchesStarted.tryReceive().isFailure)

                releaseCovers.complete(Unit)
                assertTrue(requests.awaitAll().all { it is SuccessResult })
                val startedAfterRelease = List(coverUrls.size - 4) { fetchesStarted.receive() }
                assertEquals(coverUrls.toSet(), (startedBeforeRelease + startedAfterRelease).toSet())
            }
        } finally {
            loader.shutdown()
        }
    }
}
