package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.nuvio.tv.libass.media.AssHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class LibassCpuBlendSurfaceView(
    context: Context,
    private val assHandler: AssHandler
) : SurfaceView(context), SurfaceHolder.Callback {

    private class PendingFrame(val ptsUs: Long, val releaseNs: Long)

    private val surfaceLock = Any()
    private val sizeLock = Any()
    private val pending = AtomicReference<PendingFrame?>(null)
    private val forcePost = AtomicBoolean(false)
    private val srcPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val dstRect = Rect()

    @Volatile private var renderThread: HandlerThread? = null
    @Volatile private var renderHandler: Handler? = null
    @Volatile private var surfaceReady = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var lastPtsUs = NO_PTS
    @Volatile private var vsyncPeriodNs = DEFAULT_VSYNC_PERIOD_NS

    private var bitmap: Bitmap? = null
    private var appliedWidth = 0
    private var appliedHeight = 0
    private var appliedScale = 0f
    private var surfaceHasContent = false

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    fun requestRender(presentationTimeUs: Long, releaseTimeNs: Long) {
        lastPtsUs = presentationTimeUs
        val handler = renderHandler ?: return
        pending.set(PendingFrame(presentationTimeUs, releaseTimeNs))
        handler.removeMessages(MSG_RENDER)
        handler.sendEmptyMessage(MSG_RENDER)
    }

    fun invalidateSubtitles() {
        forcePost.set(true)
        val ptsUs = lastPtsUs
        if (ptsUs == NO_PTS) return
        val handler = renderHandler ?: return
        pending.compareAndSet(null, PendingFrame(ptsUs, NO_RELEASE))
        handler.removeMessages(MSG_RENDER)
        handler.sendEmptyMessage(MSG_RENDER)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        updateSurfaceSize(frame.width(), frame.height())
        val refreshRate = display?.refreshRate?.takeIf { it >= 1f } ?: DEFAULT_REFRESH_RATE
        vsyncPeriodNs = (NANOS_PER_SECOND / refreshRate).toLong()
        synchronized(surfaceLock) {
            surfaceReady = true
        }
        val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        renderThread = thread
        renderHandler = Handler(thread.looper) { message ->
            if (message.what == MSG_RENDER) {
                drainAndRender()
                true
            } else {
                false
            }
        }
        invalidateSubtitles()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateSurfaceSize(width, height)
        invalidateSubtitles()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) {
            surfaceReady = false
        }
        val handler = renderHandler
        val thread = renderThread
        renderHandler = null
        renderThread = null
        pending.set(null)
        if (handler != null) {
            handler.removeCallbacksAndMessages(null)
            handler.post { releaseBitmap() }
        }
        thread?.quitSafely()
        runCatching { thread?.join(THREAD_JOIN_TIMEOUT_MS) }
    }

    private fun updateSurfaceSize(width: Int, height: Int) {
        synchronized(sizeLock) {
            surfaceWidth = width
            surfaceHeight = height
            assHandler.setOverlaySurfaceSize(width, height)
        }
    }

    private fun drainAndRender() {
        val frame = pending.getAndSet(null) ?: return
        val force = forcePost.getAndSet(false)
        val target = prepareTarget()
        if (target == null) {
            if (force) forcePost.set(true)
            return
        }
        val render = assHandler.render
        val result = if (render == null) {
            RESULT_ERROR
        } else {
            runCatching { render.renderFrameBitmap(frame.ptsUs / 1000L, target) }
                .getOrDefault(RESULT_ERROR)
        }
        when {
            result > 0 -> {
                waitForRelease(frame.releaseNs)
                postBitmap(target)
            }
            result == 0 -> if (force) postBitmap(target)
            else -> if (surfaceHasContent || force) postClear()
        }
    }

    private fun prepareTarget(): Bitmap? {
        val scale = renderScale
        synchronized(sizeLock) {
            val width = surfaceWidth
            val height = surfaceHeight
            if (width <= 0 || height <= 0) return null
            val bitmapWidth = scaledForRender(width, scale)
            val bitmapHeight = scaledForRender(height, scale)
            val current = bitmap
            if (current != null &&
                !current.isRecycled &&
                current.width == bitmapWidth &&
                current.height == bitmapHeight &&
                appliedWidth == width &&
                appliedHeight == height &&
                appliedScale == scale
            ) {
                return current
            }
            current?.recycle()
            val created = runCatching {
                Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            bitmap = created
            if (created == null) return null
            assHandler.setOverlaySurfaceSize(width, height + FRAME_SIZE_NUDGE_PX)
            assHandler.setOverlaySurfaceSize(width, height)
            appliedWidth = width
            appliedHeight = height
            appliedScale = scale
            return created
        }
    }

    private fun waitForRelease(releaseNs: Long) {
        if (releaseNs <= 0L) return
        val waitNs = releaseNs - vsyncPeriodNs - System.nanoTime()
        if (waitNs in 1..MAX_RELEASE_WAIT_NS) {
            LockSupport.parkNanos(waitNs)
        }
    }

    private fun postBitmap(source: Bitmap) {
        synchronized(surfaceLock) {
            if (!surfaceReady) return
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                dstRect.set(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(source, null, dstRect, srcPaint)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            surfaceHasContent = true
        }
    }

    private fun postClear() {
        synchronized(surfaceLock) {
            if (!surfaceReady) return
            val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            surfaceHasContent = false
        }
    }

    private fun releaseBitmap() {
        bitmap?.recycle()
        bitmap = null
        appliedWidth = 0
        appliedHeight = 0
        appliedScale = 0f
        surfaceHasContent = false
    }

    private fun scaledForRender(value: Int, scale: Float): Int {
        val scaled = if (scale == 1f) value else (value * scale).roundToInt()
        return scaled.coerceAtLeast(1)
    }

    companion object {
        @Volatile
        var renderScale: Float = 1f

        private const val THREAD_NAME = "LibassCpuBlend"
        private const val MSG_RENDER = 1
        private const val RESULT_ERROR = -1
        private const val NO_PTS = Long.MIN_VALUE
        private const val NO_RELEASE = Long.MIN_VALUE
        private const val FRAME_SIZE_NUDGE_PX = 8
        private const val DEFAULT_REFRESH_RATE = 60f
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val DEFAULT_VSYNC_PERIOD_NS = 16_666_667L
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
        private val MAX_RELEASE_WAIT_NS = TimeUnit.MILLISECONDS.toNanos(100)
    }
}
