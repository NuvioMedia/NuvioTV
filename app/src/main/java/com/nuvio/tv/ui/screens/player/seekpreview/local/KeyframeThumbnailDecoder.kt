package com.nuvio.tv.ui.screens.player.seekpreview.local

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Decodes single keyframes into small JPEG thumbnails.
 *
 * One codec is kept and reused with `flush()` between frames: creating a video decoder costs
 * far more than decoding one keyframe. It runs in ByteBuffer mode (no Surface, no GL) and the
 * frame is downscaled straight from its YUV planes, so the full-size picture is never converted
 * to RGB. Only software decoders are used: TV SoCs often have a single hardware instance per
 * codec, and holding it would stop playback from recreating its own decoder (track, resolution
 * or engine changes). A format no software decoder handles gets no on-device previews (Seekr
 * still covers it). All calls are serialised.
 */
internal class KeyframeThumbnailDecoder(
    private val targetWidth: Int = THUMB_WIDTH,
) {
    class Frame(val jpeg: ByteArray, val width: Int, val height: Int, val dark: Boolean)

    private var codec: MediaCodec? = null
    private var codecKey: String? = null
    private var codecInputCapacity = 0
    private var needsFlush = false
    private var released = false

    private val rejectedDecoders = mutableSetOf<String>()
    private val unsupportedFormats = mutableSetOf<String>()

    /** True once a format turned out to have no usable decoder; the caller can stop feeding it. */
    @Volatile
    var gaveUp = false
        private set

    @Synchronized
    fun decode(format: MediaFormat, data: ByteArray, offset: Int, size: Int, timeUs: Long): Frame? {
        if (released) return null
        for (attempt in 0 until 2) {
            val active = ensureCodec(format, size) ?: return null
            try {
                return decodeOnce(active, data, offset, size, timeUs)
            } catch (error: Exception) {
                Log.w(TAG, "decode failed (attempt $attempt): ${error.message}")
                releaseCodec()
            }
        }
        return null
    }

    @Synchronized
    fun release() {
        released = true
        releaseCodec()
    }

    private fun ensureCodec(format: MediaFormat, sampleSize: Int): MediaCodec? {
        val key = formatKey(format)
        val existing = codec
        if (existing != null && key == codecKey && sampleSize <= codecInputCapacity) return existing
        if (key in unsupportedFormats) return null
        releaseCodec()
        val capacity = maxOf(format.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE), sampleSize * 2, MIN_INPUT_SIZE)
        for (mime in candidateMimes(format)) {
            for (name in decoderNames(mime)) {
                if (name in rejectedDecoders) continue
                val created = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
                try {
                    created.configure(configFormat(format, mime, capacity), null, null, 0)
                    created.start()
                    codec = created
                    codecKey = key
                    codecInputCapacity = capacity
                    needsFlush = false
                    Log.i(TAG, "using $name for $mime")
                    return created
                } catch (error: Exception) {
                    // E.g. the picture is larger than this software decoder supports; try the next one.
                    Log.w(TAG, "decoder $name unavailable: ${error.message}")
                    rejectedDecoders += name
                    runCatching { created.release() }
                }
            }
        }
        unsupportedFormats += key
        gaveUp = true
        Log.i(TAG, "no software decoder for ${format.getString(MediaFormat.KEY_MIME)}")
        return null
    }

    private fun decodeOnce(codec: MediaCodec, data: ByteArray, offset: Int, size: Int, timeUs: Long): Frame? {
        if (needsFlush) codec.flush()
        needsFlush = true
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        check(inputIndex >= 0) { "no input buffer" }
        val input = codec.getInputBuffer(inputIndex) ?: error("null input buffer")
        input.clear()
        check(input.capacity() >= size) { "input buffer too small" }
        input.put(data, offset, size)
        codec.queueInputBuffer(inputIndex, 0, size, timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
        // End of stream makes decoders that hold frames for reordering release this one now.
        val eosIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (eosIndex >= 0) {
            codec.queueInputBuffer(eosIndex, 0, 0, timeUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + OUTPUT_DEADLINE_NS
        while (System.nanoTime() < deadline) {
            val outputIndex = codec.dequeueOutputBuffer(info, 20_000L)
            when {
                outputIndex >= 0 -> {
                    val frame = if (info.size > 0) {
                        codec.getOutputImage(outputIndex)?.use { image -> toThumbnail(image) }
                    } else {
                        null
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (frame != null) return frame
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
                else -> Unit // format change or try again
            }
        }
        return null
    }

    /** Nearest-neighbour downscale straight from the YUV planes (8-bit or P010) to JPEG. */
    private fun toThumbnail(image: Image): Frame? {
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        if (srcW <= 0 || srcH <= 0 || image.planes.size < 3) return null
        val outW = targetWidth
        val outH = ((targetWidth.toFloat() * srcH / srcW).roundToInt() and 1.inv()).coerceIn(64, targetWidth)
        // P010 stores each sample in 16 bits, value in the high bits: read the high byte.
        val high = if (image.format == IMAGE_FORMAT_P010) 1 else 0
        val (yPlane, uPlane, vPlane) = image.planes
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val pixels = IntArray(outW * outH)
        var lumaSum = 0L
        var lumaMax = 0
        for (y in 0 until outH) {
            val sy = crop.top + y * srcH / outH
            val yRow = sy * yPlane.rowStride
            val cRowU = (sy / 2) * uPlane.rowStride
            val cRowV = (sy / 2) * vPlane.rowStride
            for (x in 0 until outW) {
                val sx = crop.left + x * srcW / outW
                val luma = yBuf.get(yRow + sx * yPlane.pixelStride + high).toInt() and 0xFF
                val u = (uBuf.get(cRowU + (sx / 2) * uPlane.pixelStride + high).toInt() and 0xFF) - 128
                val v = (vBuf.get(cRowV + (sx / 2) * vPlane.pixelStride + high).toInt() and 0xFF) - 128
                lumaSum += luma
                if (luma > lumaMax) lumaMax = luma
                // BT.709, limited range.
                val c = 1.164f * (luma - 16)
                val r = (c + 1.793f * v).toInt().coerceIn(0, 255)
                val g = (c - 0.213f * u - 0.533f * v).toInt().coerceIn(0, 255)
                val b = (c + 2.112f * u).toInt().coerceIn(0, 255)
                pixels[y * outW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val meanLuma = (lumaSum / pixels.size).toInt()
        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(16 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return Frame(out.toByteArray(), outW, outH, dark = meanLuma < DARK_MEAN && lumaMax < DARK_MAX)
    }

    private fun releaseCodec() {
        codec?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        codec = null
        codecKey = null
        codecInputCapacity = 0
        needsFlush = false
    }

    private fun configFormat(source: MediaFormat, mime: String, capacity: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            mime,
            source.getInteger(MediaFormat.KEY_WIDTH),
            source.getInteger(MediaFormat.KEY_HEIGHT),
        )
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (source.containsKey(key)) format.setByteBuffer(key, source.getByteBuffer(key)?.duplicate())
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, capacity)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        // Best effort: never compete with the playback decoder for real-time resources.
        format.setInteger(MediaFormat.KEY_PRIORITY, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        return format
    }

    private fun candidateMimes(format: MediaFormat): List<String> {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        // Dolby Vision profiles 8.x carry an HEVC base layer that a plain HEVC decoder can show.
        return if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) {
            listOf(mime, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        } else {
            listOf(mime)
        }
    }

    private fun decoderNames(mime: String): List<String> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        return infos.filter { it.isSoftwareOnlyCompat() }.map { it.name }
    }

    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isSoftwareOnly
        } else {
            name.startsWith("OMX.google.") || name.startsWith("c2.android.")
        }

    private fun formatKey(format: MediaFormat): String = buildString {
        append(format.getString(MediaFormat.KEY_MIME)).append('|')
        append(format.intOrZero(MediaFormat.KEY_WIDTH)).append('x').append(format.intOrZero(MediaFormat.KEY_HEIGHT))
        append('|').append(format.getByteBuffer("csd-0")?.duplicate()?.let { buffer ->
            var hash = 1
            while (buffer.hasRemaining()) hash = hash * 31 + buffer.get()
            hash
        })
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    companion object {
        private const val TAG = "NuvioLocalPreviews"
        const val THUMB_WIDTH = 256
        private const val TIMEOUT_US = 200_000L
        private const val OUTPUT_DEADLINE_NS = 2_000_000_000L
        private const val MIN_INPUT_SIZE = 2 * 1024 * 1024
        private const val JPEG_QUALITY = 72
        private const val DARK_MEAN = 22
        private const val DARK_MAX = 48
        /** ImageFormat.YCBCR_P010 (API 33 constant, valid on earlier releases too). */
        private const val IMAGE_FORMAT_P010 = 0x36
    }
}
