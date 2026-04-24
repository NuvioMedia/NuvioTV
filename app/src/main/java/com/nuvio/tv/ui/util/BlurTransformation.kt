package com.nuvio.tv.ui.util

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.min

/**
 * A Coil [Transformation] that applies a smooth frosted-glass blur using a stack blur algorithm.
 * Works on all API levels without RenderScript.
 *
 * @param radius Blur radius in pixels. Higher = more blur. Clamped to 1..250.
 */
class BlurTransformation(
    private val radius: Int = 25
) : Transformation() {

    override val cacheKey: String = "stack_blur_$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val r = radius.coerceIn(1, 250)

        // Work on a mutable copy
        val bitmap = input.copy(Bitmap.Config.ARGB_8888, true)
            ?: return input

        stackBlur(bitmap, r)
        return bitmap
    }

    /**
     * In-place stack blur — produces smooth Gaussian-like results.
     * Based on the algorithm by Mario Klingemann.
     */
    private fun stackBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = radius + radius + 1
        val divSum = (div + 1) shr 1
        val divSumSq = divSum * divSum
        val mulTable = IntArray(256 * divSumSq)
        for (i in mulTable.indices) {
            mulTable[i] = i / divSumSq
        }

        val vMin = IntArray(maxOf(w, h))
        val stack = IntArray(div)

        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)

        var rSum: Int
        var gSum: Int
        var bSum: Int
        var rInSum: Int
        var gInSum: Int
        var bInSum: Int
        var rOutSum: Int
        var gOutSum: Int
        var bOutSum: Int

        var stackPointer: Int
        var stackStart: Int
        var p: Int
        var rbs: Int

        // Horizontal pass
        for (y in 0 until h) {
            rSum = 0; gSum = 0; bSum = 0
            rInSum = 0; gInSum = 0; bInSum = 0
            rOutSum = 0; gOutSum = 0; bOutSum = 0

            val yOffset = y * w
            val srcPx = pixels[yOffset]
            val sr = (srcPx ushr 16) and 0xff
            val sg = (srcPx ushr 8) and 0xff
            val sb = srcPx and 0xff

            for (i in 0..radius) {
                stack[i] = srcPx
                rbs = radius + 1 - i
                rSum += sr * rbs
                gSum += sg * rbs
                bSum += sb * rbs
                rOutSum += sr
                gOutSum += sg
                bOutSum += sb
            }

            for (i in 1..radius) {
                val px = pixels[yOffset + min(i, w - 1)]
                stack[i + radius] = px
                val cr = (px ushr 16) and 0xff
                val cg = (px ushr 8) and 0xff
                val cb = px and 0xff
                rbs = radius + 1 - i
                rSum += cr * rbs
                gSum += cg * rbs
                bSum += cb * rbs
                rInSum += cr
                gInSum += cg
                bInSum += cb
            }

            stackPointer = radius

            for (x in 0 until w) {
                r[yOffset + x] = mulTable[rSum.coerceIn(0, mulTable.size - 1)]
                g[yOffset + x] = mulTable[gSum.coerceIn(0, mulTable.size - 1)]
                b[yOffset + x] = mulTable[bSum.coerceIn(0, mulTable.size - 1)]

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                stackStart = stackPointer - radius + div
                val si = stackStart % div

                val outPx = stack[si]
                rOutSum -= (outPx ushr 16) and 0xff
                gOutSum -= (outPx ushr 8) and 0xff
                bOutSum -= outPx and 0xff

                if (y == 0) {
                    vMin[x] = min(x + radius + 1, w - 1)
                }
                p = pixels[yOffset + vMin[x]]
                stack[si] = p

                val pr = (p ushr 16) and 0xff
                val pg = (p ushr 8) and 0xff
                val pb = p and 0xff

                rInSum += pr
                gInSum += pg
                bInSum += pb

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div

                val stk = stack[stackPointer]
                val str = (stk ushr 16) and 0xff
                val stg = (stk ushr 8) and 0xff
                val stb = stk and 0xff

                rOutSum += str
                gOutSum += stg
                bOutSum += stb

                rInSum -= str
                gInSum -= stg
                bInSum -= stb
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            rSum = 0; gSum = 0; bSum = 0
            rInSum = 0; gInSum = 0; bInSum = 0
            rOutSum = 0; gOutSum = 0; bOutSum = 0

            val sr = r[x]
            val sg = g[x]
            val sb = b[x]

            for (i in 0..radius) {
                stack[i] = (sr shl 16) or (sg shl 8) or sb
                rbs = radius + 1 - i
                rSum += sr * rbs
                gSum += sg * rbs
                bSum += sb * rbs
                rOutSum += sr
                gOutSum += sg
                bOutSum += sb
            }

            for (i in 1..radius) {
                val idx = x + min(i, h - 1) * w
                val cr = r[idx]
                val cg = g[idx]
                val cb = b[idx]
                stack[i + radius] = (cr shl 16) or (cg shl 8) or cb
                rbs = radius + 1 - i
                rSum += cr * rbs
                gSum += cg * rbs
                bSum += cb * rbs
                rInSum += cr
                gInSum += cg
                bInSum += cb
            }

            stackPointer = radius

            for (y in 0 until h) {
                val outR = mulTable[rSum.coerceIn(0, mulTable.size - 1)]
                val outG = mulTable[gSum.coerceIn(0, mulTable.size - 1)]
                val outB = mulTable[bSum.coerceIn(0, mulTable.size - 1)]
                pixels[y * w + x] = (0xff shl 24) or (outR shl 16) or (outG shl 8) or outB

                rSum -= rOutSum
                gSum -= gOutSum
                bSum -= bOutSum

                stackStart = stackPointer - radius + div
                val si = stackStart % div
                val outPx = stack[si]
                rOutSum -= (outPx ushr 16) and 0xff
                gOutSum -= (outPx ushr 8) and 0xff
                bOutSum -= outPx and 0xff

                if (x == 0) {
                    vMin[y] = min(y + radius + 1, h - 1) * w
                }
                val idx = x + vMin[y]
                val pr = r[idx]
                val pg = g[idx]
                val pb = b[idx]
                stack[si] = (pr shl 16) or (pg shl 8) or pb

                rInSum += pr
                gInSum += pg
                bInSum += pb

                rSum += rInSum
                gSum += gInSum
                bSum += bInSum

                stackPointer = (stackPointer + 1) % div

                val stk = stack[stackPointer]
                val str = (stk ushr 16) and 0xff
                val stg = (stk ushr 8) and 0xff
                val stb = stk and 0xff

                rOutSum += str
                gOutSum += stg
                bOutSum += stb

                rInSum -= str
                gInSum -= stg
                bInSum -= stb
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
