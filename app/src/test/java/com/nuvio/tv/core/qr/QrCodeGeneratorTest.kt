package com.nuvio.tv.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {

    private val url = "https://nuvio.tv/device?code=ABC123&nonce=0123456789abcdef"

    @Test
    fun `module grid matches the encoded symbol for the login margin`() {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 420, 420, mapOf(EncodeHintType.MARGIN to 1))
        val symbol = Encoder.encode(url, ErrorCorrectionLevel.L).matrix

        val grid = QrCodeGenerator.moduleGrid(matrix)

        // Row 0 is quiet zone, which is what made the old scan fall back to 1 px modules
        assertTrue(grid.moduleSize > 1)
        assertEquals(symbol.width, grid.modules)
        for (row in 0 until symbol.height) {
            for (col in 0 until symbol.width) {
                val x = grid.left + col * grid.moduleSize
                val y = grid.top + row * grid.moduleSize
                assertEquals("module $col,$row", symbol.get(col, row) == 1.toByte(), matrix[x, y])
            }
        }
    }

    @Test
    fun `module grid handles zero margin`() {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 0))
        val symbol = Encoder.encode(url, ErrorCorrectionLevel.L).matrix

        val grid = QrCodeGenerator.moduleGrid(matrix)

        assertEquals(symbol.width, grid.modules)
        assertEquals(512 / symbol.width, grid.moduleSize)
    }
}
