package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.util.UUID

/** Original red/blue AVC clip (six seconds by default), encoded locally. No downloaded media or external encoder. */
internal fun originalTimelineVideo(context: Context, durationSeconds: Int = 6): File {
    require(durationSeconds in 6..60)
    val frameCount = durationSeconds * 2
    val file = File(context.cacheDir, "telumia-timeline-test-${UUID.randomUUID()}.mp4")
    val encoder = MediaCodec.createEncoderByType("video/avc")
    var muxer: MediaMuxer? = null
    var started = false; var muxing = false; var track = -1
    try {
        encoder.configure(MediaFormat.createVideoFormat("video/avc", 96, 64).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 2)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val outputMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = outputMuxer
        encoder.start(); started = true
        val info = MediaCodec.BufferInfo()
        var outputEnded = false
        fun drain(waitUs: Long) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, waitUs)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxing); track = outputMuxer.addTrack(encoder.outputFormat); outputMuxer.start(); muxing = true
                } else if (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxing); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        outputMuxer.writeSampleData(track, buffer, info)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (outputEnded) return
                }
            }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        for (frame in 0..frameCount) {
            var index = encoder.dequeueInputBuffer(10000)
            while (index < 0) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Fixture encoder timeout" }
                drain(10000); index = encoder.dequeueInputBuffer(10000)
            }
            if (frame == frameCount) encoder.queueInputBuffer(index, 0, 0, durationSeconds * 1000000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            else {
                val image = encoder.getInputImage(index) ?: error("Fixture YUV input unavailable")
                val values = if (frame < 6) intArrayOf(81, 90, 240) else intArrayOf(41, 240, 110)
                image.planes.forEachIndexed { planeIndex, plane ->
                    val width = if (planeIndex == 0) 96 else 48
                    val height = if (planeIndex == 0) 64 else 32
                    val buffer = plane.buffer
                    val origin = buffer.position()
                    for (y in 0 until height) for (x in 0 until width)
                        buffer.put(origin + y * plane.rowStride + x * plane.pixelStride, values[planeIndex].toByte())
                }
                image.close()
                encoder.queueInputBuffer(index, 0, 96 * 64 * 3 / 2, frame * 500000L, 0)
            }
            drain(10000)
        }
        while (!outputEnded) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Fixture encoder timeout" }
            drain(10000)
        }
        outputMuxer.stop(); muxing = false
        check(file.length() in 1..1048576) { "Fixture encoding failed" }
        return file
    } catch (error: Throwable) { file.delete(); throw error }
    finally {
        if (started) runCatching { encoder.stop() }
        encoder.release()
        if (muxing) runCatching { muxer?.stop() }
        muxer?.release()
    }
}
