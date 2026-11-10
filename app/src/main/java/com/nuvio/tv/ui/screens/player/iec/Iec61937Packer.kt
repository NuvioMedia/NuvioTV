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

    fun packDtsHd(accessUnit: ByteArray, iecPeriod: Int): ByteArray {
        val out = ByteArray(iecPeriod shl 2)
        packDtsHdInto(ByteBuffer.wrap(accessUnit), iecPeriod, out)
        return out
    }

    fun packDtsHdInto(accessUnit: ByteBuffer, iecPeriod: Int, out: ByteArray) {
        val burstSize = iecPeriod shl 2
        require(out.size == burstSize) {
            "DTS-HD burst must be $burstSize bytes, was ${out.size}"
        }
        out.fill(0)
        val accessUnitSize = accessUnit.remaining()
        val wrappedSize = DTSHD_START_CODE_SIZE + accessUnitSize

        val subtype = dtsHdBurstSubtype(iecPeriod)
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0, PREAMBLE1)
        header.putShort(2, PREAMBLE2)
        header.putShort(4, (TYPE_DTSHD or (subtype shl 8)).toShort())
        val length = ((wrappedSize + 0x17) and 0xFFFFFFF0.toInt()) - 0x08
        header.putShort(6, length.toShort())
        val payloadBytes = wrappedSize + (wrappedSize and 1)
        val copy = payloadBytes.coerceAtMost(burstSize - DATA_OFFSET)
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

    /** Burst size in bytes for a plain DTS frame of this many samples, 0 when IEC 61937 has no burst type for it. */
    fun dtsCoreBurstBytes(sampleCount: Int): Int = if (dtsCoreBurstType(sampleCount) != null) sampleCount shl 2 else 0

    fun dtsCoreBurstType(sampleCount: Int): Int? = when (sampleCount) {
        512 -> TYPE_DTS1
        1024 -> TYPE_DTS2
        2048 -> TYPE_DTS3
        else -> null
    }

    /**
     * Packs one plain DTS frame into a stereo IEC 61937 burst. A frame that fills the burst exactly goes without a
     * header, as in IEC 61937-5. False when the frame does not fit, or the sample count has no burst type.
     */
    fun packDtsCoreInto(frame: ByteBuffer, sampleCount: Int, littleEndian: Boolean, out: ByteArray): Boolean {
        val type = dtsCoreBurstType(sampleCount) ?: return false
        val burstSize = sampleCount shl 2
        require(out.size == burstSize) {
            "DTS burst must be $burstSize bytes, was ${out.size}"
        }
        val size = frame.remaining()
        val dataStart = when {
            size == burstSize -> 0
            size <= burstSize - DATA_OFFSET -> DATA_OFFSET
            else -> return false
        }
        out.fill(0)
        if (dataStart == DATA_OFFSET) {
            val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(0, PREAMBLE1)
            header.putShort(2, PREAMBLE2)
            header.putShort(4, type.toShort())
            header.putShort(6, (size shl 3).toShort())
        }
        frame.get(out, dataStart, size)
        if (!littleEndian) swapEndian16(out, dataStart, size + (size and 1))
        return true
    }

    fun dtsHdIecPeriod(channelCount: Int, coreSampleCount: Int): Int {
        val samples = if (coreSampleCount > 0) coreSampleCount else 512
        return if (channelCount > 2) samples * 16 else samples * 4
    }

    fun dtsHdChannelMask(channelCount: Int): Int {
        return if (channelCount > 2) 8 else 2
    }

    fun dtsHdBurstSubtype(iecPeriod: Int): Int = when (iecPeriod) {
        512 -> 0
        1024 -> 1
        2048 -> 2
        4096 -> 3
        8192 -> 4
        16384 -> 5
        32768 -> 6
        65536 -> 7
        else -> 4
    }

    private fun swapEndian16(data: ByteArray, offset: Int, length: Int) {
        val end = (offset + (length and 0x7FFFFFFE)).coerceAtMost(data.size)
        var i = offset
        val wordEnd = end - ((end - i) % Long.SIZE_BYTES)
        if (wordEnd > i) {
            val words = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            while (i < wordEnd) {
                val v = words.getLong(i)
                words.putLong(
                    i,
                    ((v and LOW_BYTE_OF_EACH_SHORT) shl 8) or ((v ushr 8) and LOW_BYTE_OF_EACH_SHORT)
                )
                i += Long.SIZE_BYTES
            }
        }
        while (i + 1 < end) {
            val tmp = data[i]
            data[i] = data[i + 1]
            data[i + 1] = tmp
            i += 2
        }
    }

    private const val LOW_BYTE_OF_EACH_SHORT = 0x00FF00FF00FF00FFL
}
