package com.nuvio.tv.core.player

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sin

/** Reuses the original trailer fixture: generated PCM only, without remote or licensed media. */
internal fun originalPreviewWave(context: Context): File {
    val rate = 16_000; val samples = rate * 10; val dataSize = samples * 2
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
    buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
    buffer.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
    repeat(samples) { buffer.putShort((sin(2.0 * Math.PI * 440 * it / rate) * 2000).toInt().toShort()) }
    return File(context.cacheDir, "telumia-pool-${UUID.randomUUID()}.wav").apply { writeBytes(buffer.array()) }
}
