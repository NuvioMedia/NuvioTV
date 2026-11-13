package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Paints the letterbox bars above the libass overlay. The overlay is a SurfaceView behind the
 * window, and such a SurfaceView clears its whole area in the window, wiping out the black bars
 * painted behind the PlayerView. Only the area outside the video surface is painted.
 */
@SuppressLint("ViewConstructor")
internal class PlayerLetterboxMaskView(
    context: Context,
    private val playerView: PlayerView
) : View(context) {

    private val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
    }
    private val videoBounds = RectF()
    private val lastVideoBounds = RectF()
    private var lastWidth = -1
    private var lastHeight = -1
    private var bars: List<LetterboxBar> = emptyList()
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        refreshBars()
        true
    }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        refreshBars()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        for (bar in bars) {
            canvas.drawRect(bar.left, bar.top, bar.right, bar.bottom, paint)
        }
    }

    private fun refreshBars() {
        val surface = playerView.videoSurfaceView
        if (surface == null || !boundsInAncestor(surface, playerView, videoBounds)) {
            videoBounds.setEmpty()
        }
        if (videoBounds == lastVideoBounds && width == lastWidth && height == lastHeight) return
        lastVideoBounds.set(videoBounds)
        lastWidth = width
        lastHeight = height
        bars = letterboxBars(
            width.toFloat(), height.toFloat(),
            videoBounds.left, videoBounds.top, videoBounds.right, videoBounds.bottom
        )
        invalidate()
    }
}

internal fun PlayerView.syncLetterboxMask(enabled: Boolean) {
    val existing = findLetterboxMask()
    if (!enabled) {
        existing?.let(::removeView)
        return
    }
    if (existing != null) return
    val subtitleIndex = subtitleView?.let(::indexOfChild) ?: -1
    addView(
        PlayerLetterboxMaskView(context, this),
        if (subtitleIndex >= 0) subtitleIndex else childCount,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

private fun PlayerView.findLetterboxMask(): PlayerLetterboxMaskView? {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is PlayerLetterboxMaskView) return child
    }
    return null
}

/** Maps [view]'s laid-out and transformed bounds into [ancestor]'s coordinate space. */
private fun boundsInAncestor(view: View, ancestor: View, out: RectF): Boolean {
    if (view.width <= 0 || view.height <= 0) return false
    out.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
    var current = view
    while (current !== ancestor) {
        current.matrix.mapRect(out)
        val parent = current.parent as? View ?: return false
        out.offset(
            (current.left - parent.scrollX).toFloat(),
            (current.top - parent.scrollY).toFloat()
        )
        current = parent
    }
    return true
}

internal data class LetterboxBar(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The parts of a [width] x [height] frame outside the video rectangle. The video rectangle is
 * rounded out to whole pixels, so a bar never covers a pixel the video touches. Without a video
 * rectangle inside the frame there are no bars, so the mask never covers the whole frame.
 */
internal fun letterboxBars(
    width: Float,
    height: Float,
    videoLeft: Float,
    videoTop: Float,
    videoRight: Float,
    videoBottom: Float
): List<LetterboxBar> {
    if (
        width <= 0f || height <= 0f ||
        !videoLeft.isFinite() || !videoTop.isFinite() ||
        !videoRight.isFinite() || !videoBottom.isFinite()
    ) {
        return emptyList()
    }
    val left = floor(videoLeft).coerceIn(0f, width)
    val top = floor(videoTop).coerceIn(0f, height)
    val right = ceil(videoRight).coerceIn(0f, width)
    val bottom = ceil(videoBottom).coerceIn(0f, height)
    if (right <= left || bottom <= top) return emptyList()
    return buildList {
        if (top > 0f) add(LetterboxBar(0f, 0f, width, top))
        if (bottom < height) add(LetterboxBar(0f, bottom, width, height))
        if (left > 0f) add(LetterboxBar(0f, top, left, bottom))
        if (right < width) add(LetterboxBar(right, top, width, bottom))
    }
}
