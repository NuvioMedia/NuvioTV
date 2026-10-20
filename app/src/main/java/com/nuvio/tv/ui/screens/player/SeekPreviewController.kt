package com.nuvio.tv.ui.screens.player

import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.mediaserver.SeekPreview
import com.nuvio.tv.data.mediaserver.SeekPreviewLayout
import com.nuvio.tv.data.mediaserver.ServerPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Seek-preview thumbnails for streams served by the user's own Jellyfin server.
 * Sheets come only from that server and live in memory only; other stream types are untouched.
 */
internal class SeekPreviewController(
    private val scope: CoroutineScope,
    private val serverPlayback: ServerPlayback
) {
    private val _layout = MutableStateFlow<SeekPreviewLayout?>(null)
    val layout: StateFlow<SeekPreviewLayout?> = _layout

    @Volatile
    private var source: SeekPreview? = null
    private var loadJob: Job? = null
    private val sheetFetchLock = Mutex()

    private val sheets = object : LruCache<Int, ByteArray>(SHEET_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }
    private val thumbnails = LruCache<Int, ImageBitmap>(THUMBNAIL_CACHE_ENTRIES)

    fun load(url: String?) {
        if (!BuildConfig.FEATURE_SEEK_PREVIEW_ENABLED) return
        loadJob?.cancel()
        if (url == null) {
            clear()
            return
        }
        loadJob = scope.launch {
            val preview = try {
                serverPlayback.seekPreview(url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (preview == null) {
                clear()
                return@launch
            }
            if (preview.layout.key != source?.layout?.key) {
                sheets.evictAll()
                thumbnails.evictAll()
            }
            source = preview
            _layout.value = preview.layout
        }
    }

    fun clear() {
        loadJob?.cancel()
        source = null
        _layout.value = null
        sheets.evictAll()
        thumbnails.evictAll()
    }

    suspend fun thumbnail(positionMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        val preview = source ?: return@withContext null
        val layout = preview.layout
        val index = layout.thumbnailIndexAt(positionMs)
        thumbnails.get(index)?.let { return@withContext it }
        val bytes = sheetBytes(preview, layout.sheetIndexOf(index)) ?: return@withContext null
        val bitmap = decodeThumbnail(bytes, layout, index) ?: return@withContext null
        thumbnails.put(index, bitmap)
        bitmap
    }

    private suspend fun sheetBytes(preview: SeekPreview, sheetIndex: Int): ByteArray? {
        sheets.get(sheetIndex)?.let { return it }
        return sheetFetchLock.withLock {
            sheets.get(sheetIndex) ?: preview.sheet(sheetIndex)?.also { sheets.put(sheetIndex, it) }
        }
    }

    /** Decodes just one cell of the sheet, so a 10x10 sheet is never fully expanded in memory. */
    @Suppress("DEPRECATION") // The 3-arg overload only exists on API 31+; minSdk is 24.
    private fun decodeThumbnail(bytes: ByteArray, layout: SeekPreviewLayout, index: Int): ImageBitmap? {
        val cell = layout.cellOf(index)
        val decoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val region = Rect(
                cell.column * layout.thumbWidth,
                cell.row * layout.thumbHeight,
                (cell.column + 1) * layout.thumbWidth,
                (cell.row + 1) * layout.thumbHeight
            )
            // The last sheet can be shorter than a full grid.
            if (!region.intersect(0, 0, decoder.width, decoder.height)) return null
            decoder.decodeRegion(region, null)?.asImageBitmap()
        } catch (_: Exception) {
            null
        } finally {
            decoder.recycle()
        }
    }

    private companion object {
        const val SHEET_CACHE_BYTES = 6 * 1024 * 1024
        const val THUMBNAIL_CACHE_ENTRIES = 32
    }
}
