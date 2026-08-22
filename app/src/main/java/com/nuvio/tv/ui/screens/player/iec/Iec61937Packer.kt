package com.nuvio.tv.ui.screens.player.iec

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Iec61937Packer {
    const val PREAMBLE1: Short = 0xF872.toShort()
    const val PREAMBLE2: Short = 0x4E1F
    const val TYPE_AC3 = 0x01
    const val TYPE_DTS1 = 0x0B
    const val TYPE_DTS2 = 0x0C
    const val TYPE_DTS3 = 0x0D
    const val TYPE_DTSHD = 0x11
    const val TYPE_EAC3 = 0x15
    const val TYPE_TRUEHD = 0x16
    const val DATA_OFFSET = 8
    const val TRUEHD_IEC_SIZE = 61440
    const val TRUEHD_LENGTH_FIELD = 61424
    const val DTSHD_START_CODE_SIZE = 12

    private val dtsHdStartCode = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFE.toByte(), 0xFE.toByte()
    )

    fun packTrueHd(matFrame: ByteArray): ByteArray {
        require(matFrame.size == TRUEHD_IEC_SIZE) {
            "MAT frame must be $TRUEHD_IEC_SIZE bytes, was ${matFrame.size}"
        }
        val out = matFrame.copyOf()
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, PREAMBLE1)
        header.putShort(2, PREAMBLE2)
        header.putShort(4, TYPE_TRUEHD.toShort())
        header.putShort(6, TRUEHD_LENGTH_FIELD.toShort())
        swapEndian16(out, DATA_OFFSET, out.size - DATA_OFFSET)
        return out
    }

    /**
     * Packs a DTS-HD / DTS:X access unit into an IEC 61937-5 burst.
     *
     * [iecPeriod] is 8192 for 8-channel 192 kHz (MA) and 2048 for 2-channel
     * 192 kHz (HR). Burst size is period * 4.
     */
    fun packDtsHd(accessUnit: ByteArray, iecPeriod: Int): ByteArray {
        val wrappedSize = DTSHD_START_CODE_SIZE + accessUnit.size
        val wrapped = ByteArray(wrappedSize)
        System.arraycopy(dtsHdStartCode, 0, wrapped, 0, dtsHdStartCode.size)
        wrapped[10] = ((accessUnit.size shr 8) and 0xFF).toByte()
        wrapped[11] = (accessUnit.size and 0xFF).toByte()
        System.arraycopy(accessUnit, 0, wrapped, DTSHD_START_CODE_SIZE, accessUnit.size)

        val subtype = when (iecPeriod) {
            512 -> 0
            1024 -> 1
            2048 -> 2
            4096 -> 3
            8192 -> 4
            16384 -> 5
            else -> 4
        }
        val burstSize = iecPeriod shl 2
        val out = ByteArray(burstSize)
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, PREAMBLE1)
        header.putShort(2, PREAMBLE2)
        header.putShort(4, (TYPE_DTSHD or (subtype shl 8)).toShort())
        val length = ((wrappedSize + 0x17) and 0xFFFFFFF0.toInt()) - 0x08
        header.putShort(6, length.toShort())
        val payloadBytes = wrappedSize + (wrappedSize and 1)
        val copy = payloadBytes.coerceAtMost(burstSize - DATA_OFFSET)
        System.arraycopy(wrapped, 0, out, DATA_OFFSET, copy.coerceAtMost(wrapped.size))
        swapEndian16(out, DATA_OFFSET, copy)
        return out
    }

    fun dtsHdIecPeriod(channelCount: Int, coreSampleCount: Int): Int {
        val samples = if (coreSampleCount > 0) coreSampleCount else 512
        return if (channelCount > 2) samples * 16 else samples * 4
    }

    fun dtsHdChannelMask(channelCount: Int): Int {
        return if (channelCount > 2) 8 else 2
    }

    private fun swapEndian16(data: ByteArray, offset: Int, length: Int) {
        val end = (offset + (length and 0x7FFFFFFE)).coerceAtMost(data.size)
        var i = offset
        while (i + 1 < end) {
            val tmp = data[i]
            data[i] = data[i + 1]
            data[i + 1] = tmp
            i += 2
        }
    }
}
