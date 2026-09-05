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
        return packTrueHdInPlace(matFrame.copyOf())
    }

    // As packTrueHd, but writes the preamble and the byte swap into matFrame itself and
    // returns it. The caller gives up the frame's original content.
    fun packTrueHdInPlace(matFrame: ByteArray): ByteArray {
        require(matFrame.size == TRUEHD_IEC_SIZE) {
            "MAT frame must be $TRUEHD_IEC_SIZE bytes, was ${matFrame.size}"
        }
        val header = ByteBuffer.wrap(matFrame).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, PREAMBLE1)
        header.putShort(2, PREAMBLE2)
        header.putShort(4, TYPE_TRUEHD.toShort())
        header.putShort(6, TRUEHD_LENGTH_FIELD.toShort())
        swapEndian16(matFrame, DATA_OFFSET, matFrame.size - DATA_OFFSET)
        return matFrame
    }

    /**
     * Packs a DTS-HD / DTS:X access unit into an IEC 61937-5 burst.
     *
     * [iecPeriod] is 8192 for 8-channel 192 kHz (MA) and 2048 for 2-channel
     * 192 kHz (HR). Burst size is period * 4.
     */
    fun packDtsHd(accessUnit: ByteArray, iecPeriod: Int): ByteArray {
        val out = ByteArray(iecPeriod shl 2)
        packDtsHdInto(ByteBuffer.wrap(accessUnit), iecPeriod, out)
        return out
    }

    // Packs the access unit (position to limit) into out, which must be iecPeriod * 4 bytes.
    // out is zero-filled first so a recycled burst carries nothing from its previous use;
    // the access unit is consumed. Output is byte-identical to packDtsHd.
    fun packDtsHdInto(accessUnit: ByteBuffer, iecPeriod: Int, out: ByteArray) {
        val burstSize = iecPeriod shl 2
        require(out.size == burstSize) {
            "DTS-HD burst must be $burstSize bytes, was ${out.size}"
        }
        out.fill(0)
        val accessUnitSize = accessUnit.remaining()
        val wrappedSize = DTSHD_START_CODE_SIZE + accessUnitSize

        val subtype = when (iecPeriod) {
            512 -> 0
            1024 -> 1
            2048 -> 2
            4096 -> 3
            8192 -> 4
            16384 -> 5
            else -> 4
        }
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, PREAMBLE1)
        header.putShort(2, PREAMBLE2)
        header.putShort(4, (TYPE_DTSHD or (subtype shl 8)).toShort())
        val length = ((wrappedSize + 0x17) and 0xFFFFFFF0.toInt()) - 0x08
        header.putShort(6, length.toShort())
        val payloadBytes = wrappedSize + (wrappedSize and 1)
        val copy = payloadBytes.coerceAtMost(burstSize - DATA_OFFSET)
        // The first min(copy, wrappedSize) bytes of [start code, size hi, size lo, access unit],
        // written straight into the burst instead of through an intermediate array.
        val wrappedCopy = copy.coerceAtMost(wrappedSize)
        System.arraycopy(
            dtsHdStartCode, 0, out, DATA_OFFSET, wrappedCopy.coerceAtMost(dtsHdStartCode.size)
        )
        if (wrappedCopy > 10) out[DATA_OFFSET + 10] = ((accessUnitSize shr 8) and 0xFF).toByte()
        if (wrappedCopy > 11) out[DATA_OFFSET + 11] = (accessUnitSize and 0xFF).toByte()
        val accessUnitCopy = (wrappedCopy - DTSHD_START_CODE_SIZE).coerceAtLeast(0)
        accessUnit.get(out, DATA_OFFSET + DTSHD_START_CODE_SIZE, accessUnitCopy)
        accessUnit.position(accessUnit.limit())
        swapEndian16(out, DATA_OFFSET, copy)
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
