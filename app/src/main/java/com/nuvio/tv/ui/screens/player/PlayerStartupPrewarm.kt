package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DoviBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Does, once per process and off the main thread, the one-time work the first playback of a
 * session would otherwise do on its way to the first frame: reading the platform codec list and
 * the display's HDR capabilities for the Dolby Vision decision, loading the libdovi bridge and
 * the decoder extensions' native libraries, listing the decoders for the usual video formats, and
 * creating the playback HTTP client. Started when a details or stream screen opens.
 */
@OptIn(UnstableApi::class)
internal object PlayerStartupPrewarm {
    private const val TAG = "PlayerStartupPrewarm"
    private val started = AtomicBoolean(false)

    // Called by name: the IAMF extension is only in the full flavor.
    private val extensionLibraries = listOf(
        "androidx.media3.decoder.ffmpeg.FfmpegLibrary",
        "androidx.media3.decoder.av1.Gav1Library",
        "androidx.media3.decoder.iamf.IamfLibrary",
        "androidx.media3.decoder.mpegh.MpeghLibrary",
    )

    private val videoMimeTypes = listOf(
        MimeTypes.VIDEO_H265,
        MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_DOLBY_VISION,
        MimeTypes.VIDEO_AV1,
        MimeTypes.VIDEO_VP9,
    )

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            runCatching { DolbyVisionBaseLayerPolicy.resolve(appContext, DoviBridge.isLibraryLoaded) }
            for (name in extensionLibraries) {
                runCatching { Class.forName(name).getMethod("isAvailable").invoke(null) }
            }
            for (mimeType in videoMimeTypes) {
                runCatching { MediaCodecUtil.getDecoderInfos(mimeType, false, false) }
            }
            runCatching { PlayerPlaybackNetworking.playbackHttpClient }
            Log.d(TAG, "Player startup work done in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }, "player-startup-prewarm").start()
    }
}
