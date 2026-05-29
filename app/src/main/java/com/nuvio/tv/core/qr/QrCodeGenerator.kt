package com.nuvio.tv.core.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
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

        // Fill white background with rounded corners
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val cornerRadius = size * 0.06f
        val bgRect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // Draw QR modules as rounded squares
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        // Determine module size by finding first dark module boundary
        var moduleSize = 1
        for (x in 0 until size) {
            if (bitMatrix[x, 0] != bitMatrix[0, 0]) {
                moduleSize = x
                break
            }
        }
        if (moduleSize < 1) moduleSize = 1

        val moduleRadius = moduleSize * 0.3f

        var x = 0
        while (x < size) {
            var y = 0
            while (y < size) {
                if (bitMatrix[x, y]) {
                    val rect = RectF(
                        x.toFloat(), y.toFloat(),
                        (x + moduleSize).toFloat().coerceAtMost(size.toFloat()),
                        (y + moduleSize).toFloat().coerceAtMost(size.toFloat())
                    )
                    canvas.drawRoundRect(rect, moduleRadius, moduleRadius, fgPaint)
                }
                y += moduleSize
            }
            x += moduleSize
        }

        // Clip the entire bitmap to rounded corners
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        val clipPath = Path().apply {
            addRoundRect(bgRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        outputCanvas.clipPath(clipPath)
        outputCanvas.drawBitmap(bitmap, 0f, 0f, null)

        return output
    }
}
