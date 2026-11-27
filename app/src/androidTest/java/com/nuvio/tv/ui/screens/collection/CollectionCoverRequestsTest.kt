package com.nuvio.tv.ui.screens.collection

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.CollectionRowSection
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CollectionCoverRequestsTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Test
    fun visibleCoversLoadOnceWithFocusGlowEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLoader = SingletonImageLoader.get(context)
        val covers = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
            .mapIndexed { index, color ->
                Cover("Folder $index", "https://covers.invalid/folder-$index.png", color)
            }
        val coversByUrl = covers.associateBy { it.url }
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val duplicateRequest = CountDownLatch(1)
        val loader = ImageLoader.Builder(context)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val url = chain.request.data as? String ?: return chain.proceed()
                        val cover = coversByUrl[url] ?: return chain.proceed()
                        val count = requests.computeIfAbsent(cover.url) { AtomicInteger() }.incrementAndGet()
                        if (count > 1) duplicateRequest.countDown()
                        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(cover.color.toArgb())
                        }
                        return SuccessResult(bitmap.asImage(), chain.request, DataSource.MEMORY)
                    }
                })
            }
            .build()
        val collection = Collection(
            id = "test-covers",
            title = "Test covers",
            focusGlowEnabled = true,
            folders = covers.mapIndexed { index, cover ->
                CollectionFolder(
                    id = "folder-$index",
                    title = cover.title,
                    coverImageUrl = cover.url,
                    tileShape = PosterShape.POSTER,
                    hideTitle = true
                )
            }
        )
        val requestedFocus = mutableIntStateOf(-1)
        val focusedFolder = AtomicInteger(-1)

        SingletonImageLoader.setUnsafe(loader)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                NuvioTheme {
                    CollectionRowSection(
                        collection = collection,
                        onFolderClick = { _, _ -> },
                        posterCardStyle = PosterCardStyle(width = 48.dp, height = 72.dp, focusedScale = 1f),
                        focusedItemIndex = requestedFocus.intValue,
                        onItemFocused = { focusedFolder.set(it) }
                    )
                }
            }

            advanceFramesUntil { requests.keys.containsAll(coversByUrl.keys) && covers.all(::showsArtwork) }
            compose.runOnUiThread { requestedFocus.intValue = 0 }
            advanceFramesUntil { focusedFolder.get() == 0 }
            compose.runOnUiThread { requestedFocus.intValue = 1 }
            advanceFramesUntil { focusedFolder.get() == 1 }
            // The extra glow request used to run on Dispatchers.IO, outside Compose's idleness checks.
            duplicateRequest.await(1, TimeUnit.SECONDS)
            assertEquals(covers.associate { it.url to 1 }, requests.mapValues { (_, count) -> count.get() })
        } finally {
            SingletonImageLoader.setUnsafe(originalLoader)
            loader.shutdown()
        }
    }

    private fun advanceFramesUntil(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            condition()
        }
    }

    private fun showsArtwork(cover: Cover): Boolean {
        val pixels = compose.onNodeWithContentDescription(cover.title, useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2].toArgb() == cover.color.toArgb()
    }

    private data class Cover(val title: String, val url: String, val color: Color)
}
