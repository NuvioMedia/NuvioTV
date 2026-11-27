package com.nuvio.tv.core.image

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.network.ConnectivityChecker
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
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
import kotlinx.coroutines.withTimeoutOrNull
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollectionCoverNetworkClientTest {
    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun diskHitAndOrdinaryImageBypassCoverNetworkSlots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val warmUrl = "https://covers.invalid/warm.png"
        val ordinaryUrl = "https://covers.invalid/ordinary.png"
        val coldUrls = List(5) { "https://covers.invalid/cold-$it.png" }
        val coldBodies = Channel<String>(Channel.UNLIMITED)
        val releaseBodies = CompletableDeferred<Unit>()
        val imageBytes = pngImage()
        val cacheDirectory = File(context.cacheDir, "cover-test-${UUID.randomUUID()}")
        check(cacheDirectory.mkdir())
        val diskCache = DiskCache.Builder().directory(cacheDirectory.toOkioPath()).build()
        val client = object : NetworkClient {
            override suspend fun <T> executeRequest(
                request: NetworkRequest,
                block: suspend (NetworkResponse) -> T
            ): T {
                check(request.url != warmUrl) { "Warm cover unexpectedly reached the network" }
                val body = TestImageBody(imageBytes) {
                    if (request.url in coldUrls) {
                        coldBodies.send(request.url)
                        releaseBodies.await()
                    }
                }
                return body.use { block(NetworkResponse(code = 200, body = it)) }
            }
        }
        val testContext = coroutineContext.minusKey(Job)
        val loader = ImageLoader.Builder(context)
            .coroutineContext(testContext)
            .mainCoroutineContext(testContext)
            .memoryCache(null)
            .diskCache(diskCache)
            .components {
                add(
                    NetworkFetcher.Factory(
                        networkClient = { CollectionCoverNetworkClient(client) },
                        connectivityChecker = { ConnectivityChecker.ONLINE }
                    )
                )
            }
            .build()
        try {
            val editor = checkNotNull(diskCache.openEditor(warmUrl))
            diskCache.fileSystem.write(editor.metadata) {}
            diskCache.fileSystem.write(editor.data) { write(imageBytes) }
            editor.commit()
            withTimeout(10_000) {
                val blockingCovers = coldUrls.take(4).map { url ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        loader.execute(ImageRequest.Builder(context).data(url).collectionCover().build())
                    }
                }
                assertEquals(coldUrls.take(4).toSet(), List(4) { coldBodies.receive() }.toSet())
                val fifth = async(start = CoroutineStart.UNDISPATCHED) {
                    loader.execute(ImageRequest.Builder(context).data(coldUrls.last()).collectionCover().build())
                }

                val ordinary = loader.execute(ImageRequest.Builder(context).data(ordinaryUrl).build())
                assertTrue("An ordinary image should bypass the cover slots", ordinary is SuccessResult)
                val cached = withTimeoutOrNull(2_000) {
                    loader.execute(
                        ImageRequest.Builder(context)
                            .data(warmUrl)
                            .diskCacheKey(warmUrl)
                            .collectionCover()
                            .build()
                    )
                }
                assertTrue(
                    "A cached cover should decode while all four network response bodies are held",
                    cached is SuccessResult && cached.dataSource == DataSource.DISK
                )
                assertTrue("The fifth cold cover should still be queued", coldBodies.tryReceive().isFailure)

                releaseBodies.complete(Unit)
                assertTrue((blockingCovers + fifth).awaitAll().all { it is SuccessResult })
                assertEquals(coldUrls.last(), coldBodies.receive())
            }
        } finally {
            loader.shutdown()
            diskCache.shutdown()
            cacheDirectory.deleteRecursively()
        }
    }

    private fun pngImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            return ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class TestImageBody(
        private val bytes: ByteArray,
        private val beforeWrite: suspend () -> Unit
    ) : NetworkResponseBody {
        override suspend fun writeTo(sink: BufferedSink) {
            beforeWrite()
            sink.write(bytes)
        }

        override suspend fun writeTo(fileSystem: FileSystem, path: Path) {
            fileSystem.write(path) { writeTo(this) }
        }

        override fun close() = Unit
    }
}
