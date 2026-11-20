package com.nuvio.tv.ui.util

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

class SmoothDownscaleTransformation(
    private val targetWidth: Int,
    private val targetHeight: Int
) : Transformation() {

    override val cacheKey: String = "smooth_downscale_${targetWidth}x$targetHeight"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0) return input
        val targetAspect = targetWidth.toFloat() / targetHeight
        var cropWidth = input.width
        var cropHeight = (input.width / targetAspect).roundToInt()
        if (cropHeight > input.height) {
            cropHeight = input.height
            cropWidth = (input.height * targetAspect).roundToInt()
        }
        cropWidth = cropWidth.coerceIn(1, input.width)
        cropHeight = cropHeight.coerceIn(1, input.height)

        var current = Bitmap.createBitmap(
            input,
            (input.width - cropWidth) / 2,
            (input.height - cropHeight) / 2,
            cropWidth,
            cropHeight
        )
        if (current.config != Bitmap.Config.ARGB_8888) {
            val converted = current.copy(Bitmap.Config.ARGB_8888, false)
            if (current !== input) current.recycle()
            current = converted
        }
        while (current.width >= targetWidth * 2 && current.height >= targetHeight * 2) {
            current = replace(current, input, current.width / 2, current.height / 2)
        }
        if (current.width != targetWidth || current.height != targetHeight) {
            current = replace(current, input, targetWidth, targetHeight)
        }
        return current
    }

    private fun replace(current: Bitmap, input: Bitmap, width: Int, height: Int): Bitmap {
        val next = Bitmap.createScaledBitmap(current, width, height, true)
        if (next !== current && current !== input) current.recycle()
        return next
    }
}
