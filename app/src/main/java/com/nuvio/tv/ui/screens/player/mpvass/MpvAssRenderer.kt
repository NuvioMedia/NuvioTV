package com.nuvio.tv.ui.screens.player.mpvass

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import `is`.xyz.mpv.Utils
import java.io.File

@OptIn(UnstableApi::class)
internal class MpvAssRenderer(
    private val context: Context,
    private val fontSource: DataSource.Factory,
    private val delayUs: () -> Long,
    private val subtitlesOn: Boolean,
) : Player.Listener, VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    private val ass = AssSurface(context) { fallbackFont(context) }
    private val fontsDir = File(context.cacheDir, "attached-fonts")
    private var player: ExoPlayer? = null
    private var layout = AssLayout()
    private var sentLayout: AssLayout? = null
    private var fonts: AttachedFonts? = null
    private var chosen = false
    private var tunneling = false

    val view: View get() = ass.view

    @Volatile
    private var shown = false

    @Volatile
    private var file = 0

    @Volatile
    private var readBack: String? = if (subtitlesOn) MatroskaReader.ANY_TRACK else null

    @Volatile
    private var lastFrameUs = 0L

    @Volatile
    private var lastFrameAtNs = 0L

    init {
        fontsDir.deleteRecursively()
        if (small(context)) ass.setCacheLimit(SMALL_ASS_CACHE_MB)
    }

    fun extractors(delegate: ExtractorsFactory): ExtractorsFactory = Extractors(
        delegate = delegate,
        readBack = { readBack },
        sink = { ass.sink() },
        events = {
            val opened = file
            object : MatroskaEvents {
                override fun attachments(uri: Uri, position: Long, length: Long?) {
                    main.post { if (opened == file) loadFonts(uri, position, length) }
                }
            }
        },
    )

    fun subtitleParsers(): SubtitleParser.Factory = Scripts { key, script -> ass.addScript(key, script) }

    fun attach(player: ExoPlayer, tunneling: Boolean) {
        this.player = player
        this.tunneling = tunneling
        player.addListener(this)
        player.setVideoFrameMetadataListener(this)
        updateClock()
    }

    fun reset() {
        file++
        closeFonts()
        shown = false
        chosen = false
        readBack = if (subtitlesOn) MatroskaReader.ANY_TRACK else null
        ass.reset()
    }

    fun redraw() {
        ass.render(lastFrameUs - delayUs(), 0)
    }

    fun setStage(width: Int, height: Int, video: Rect) {
        layout = layout.copy(stageWidth = width, stageHeight = height, video = video)
        updateLayout()
    }

    fun release() {
        Choreographer.getInstance().removeFrameCallback(clock)
        player?.let {
            it.removeListener(this)
            it.clearVideoFrameMetadataListener(this)
        }
        player = null
        closeFonts()
        (view.parent as? ViewGroup)?.removeView(view)
        ass.release()
    }

    private fun loadFonts(uri: Uri, position: Long, length: Long?) {
        if (fonts != null || player == null) return
        val dir = File(fontsDir, System.nanoTime().toString())
        fonts = AttachedFonts(fontSource, uri, position, length, ass.sink(), dir, ::fontsMayLoad).also { it.start() }
    }

    private fun closeFonts() {
        fonts?.close()
        fonts = null
        fontsDir.deleteRecursively()
    }

    private fun fontsMayLoad(bytes: Long) = bytes <= SMALL_FONTS_BYTES || shown

    override fun onRenderedFirstFrame() {
        shown = true
    }

    private fun showSubtitles(tracks: Tracks) {
        val format = selectedText(tracks)
        val id = format?.id?.substringAfter(':')
        ass.select(id.takeIf { format != null && isAss(format) })
        if (chosen) readBack = id.takeIf { format != null && isAss(format) }
    }

    private fun selectedText(tracks: Tracks): Format? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSelected) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
        return null
    }

    // Each video frame, as it is handed to the screen: the subtitles follow the picture.
    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        lastFrameUs = presentationTimeUs
        lastFrameAtNs = System.nanoTime()
        ass.render(presentationTimeUs - delayUs(), releaseTimeNs)
    }

    // Tunneled frames skip the frame callbacks, so subtitles follow the playback clock.
    private val clock = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val player = player ?: return
            if (!tunneling || !player.isPlaying) return
            if (System.nanoTime() - lastFrameAtNs > FRAMES_STOPPED_NS) {
                val us = player.currentPosition * 1000
                lastFrameUs = us
                ass.render(us - delayUs(), frameTimeNanos)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateClock() {
        Choreographer.getInstance().removeFrameCallback(clock)
        if (tunneling && player?.isPlaying == true) Choreographer.getInstance().postFrameCallback(clock)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateClock()
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (!chosen && !tracks.isEmpty) chosen = true
        showSubtitles(tracks)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) return
        layout = layout.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
        updateLayout()
    }

    private fun updateLayout() {
        val next = layout
        if (next == sentLayout) return
        sentLayout = next
        ass.setLayout(next)
    }

    private companion object {
        const val FRAMES_STOPPED_NS = 100_000_000L
        const val SMALL_FONTS_BYTES = 8L shl 20
        const val SMALL_ASS_CACHE_MB = 32

        /** Under this, a 3 GB device; 4 GB ones report more. */
        const val SMALL_DEVICE_BYTES = 3_400_000_000L

        fun fallbackFont(context: Context): File {
            runCatching { Utils.copyAssets(context) }
            return File(context.filesDir, "subfont.ttf").takeIf { it.exists() } ?: File("/system/fonts/Roboto-Regular.ttf")
        }

        fun small(context: Context): Boolean {
            val activities = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
            return activities.isLowRamDevice || memory.totalMem < SMALL_DEVICE_BYTES
        }
    }
}
