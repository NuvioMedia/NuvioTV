package com.nuvio.tv.ui.screens.player

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation

@androidx.annotation.OptIn(UnstableApi::class)
internal class KeystoneZoomEffect : MatrixTransformation {
    @Volatile
    private var videoFraction = AspectScale(scaleX = 1f, scaleY = 1f)

    fun setVideoFraction(fraction: AspectScale) {
        videoFraction = fraction
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val fraction = videoFraction
        return Matrix().apply { setScale(fraction.scaleX, fraction.scaleY) }
    }
}
