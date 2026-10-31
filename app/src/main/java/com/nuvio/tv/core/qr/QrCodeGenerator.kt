package com.nuvio.tv.core.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    fun generate(content: String, size: Int = 512, margin: Int = 4): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to margin.coerceAtLeast(0))
        )

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Clip to rounded corners up front so modules never draw past the rounded background
        val cornerRadius = size * 0.06f
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.clipPath(
            Path().apply { addRoundRect(bgRect, cornerRadius, cornerRadius, Path.Direction.CW) }
        )
        canvas.drawColor(Color.WHITE)

        val fgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        // Draw one rect per QR module instead of per pixel; the matrix is scaled by the writer
        val grid = moduleGrid(bitMatrix)
        val step = grid.moduleSize
        for (row in 0 until grid.modules) {
            val y = grid.top + row * step
            for (col in 0 until grid.modules) {
                val x = grid.left + col * step
                if (bitMatrix[x, y]) {
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + step).toFloat(),
                        (y + step).toFloat(),
                        fgPaint
                    )
                }
            }
        }

        return bitmap
    }

    internal data class ModuleGrid(val left: Int, val top: Int, val moduleSize: Int, val modules: Int)

    /**
     * Locates the scaled module grid inside [matrix]. The top-left finder pattern starts the
     * symbol and is 7 modules wide, which gives the module size without assuming any padding.
     */
    internal fun moduleGrid(matrix: BitMatrix): ModuleGrid {
        // [left, top, width, height]; the finder patterns pin all four edges of the symbol
        val bounds = matrix.enclosingRectangle ?: return ModuleGrid(0, 0, 1, 0)
        val left = bounds[0]
        val top = bounds[1]
        var x = left
        while (x < matrix.width && matrix[x, top]) x++
        val moduleSize = ((x - left) / 7).coerceAtLeast(1)
        return ModuleGrid(left, top, moduleSize, bounds[2] / moduleSize)
    }
}
