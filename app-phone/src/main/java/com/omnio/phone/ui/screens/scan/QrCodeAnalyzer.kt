package com.omnio.phone.ui.screens.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX [ImageAnalysis.Analyzer] that decodes QR codes from each frame using ZXing.
 * The first non-blank result triggers [onResult] and the analyzer disarms itself so the
 * callback fires only once per scanner-screen lifetime.
 */
class QrCodeAnalyzer(
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    private val armed = AtomicBoolean(true)

    override fun analyze(image: ImageProxy) {
        if (!armed.get()) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: run {
                image.close()
                return
            }
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decodeWithState(bitmap) }
                .getOrElse { error ->
                    if (error is NotFoundException) null else throw error
                }
            val text = result?.text?.takeIf { it.isNotBlank() }
            if (text != null && armed.compareAndSet(true, false)) {
                onResult(text)
            }
        } finally {
            reader.reset()
            image.close()
        }
    }
}
