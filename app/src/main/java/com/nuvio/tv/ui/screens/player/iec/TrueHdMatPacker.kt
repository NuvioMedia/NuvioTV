package com.nuvio.tv.ui.screens.player.iec

import java.util.ArrayDeque

/**
 * Packs TrueHD access units into 61440-byte MAT frames with start/middle/end
 * codes and dynamic padding. IEC burst preamble is left as 8 leading zeros;
 * [Iec61937Packer.packTrueHd] fills it and byte-swaps the payload.
 */
internal class TrueHdMatPacker {

    private val outputQueue = ArrayDeque<ByteArray>()
    // Frames handed back by recycleFrame; writeHeader takes from here before allocating.
    private val framePool = ArrayDeque<ByteArray>()
    private var buffer = EMPTY_FRAME
    private var bufferCount = 0
    private val state = MatState()

    fun reset() {
        while (outputQueue.isNotEmpty()) recycleFrame(outputQueue.poll())
        recycleFrame(buffer)
        buffer = EMPTY_FRAME
        bufferCount = 0
        state.reset()
    }

    /**
     * @return true when at least one complete MAT frame is ready in [pollFrame].
     */
    fun packAccessUnit(data: ByteArray): Boolean {
        if (data.size < 10) return false

        val isMajorSync = isMajorSync(data)
        var info: MajorSyncInfo? = null
        if (isMajorSync) {
            info = parseMajorSync(data)
            state.ratebits = info?.ratebits ?: ((data[8].toInt() and 0xFF) shr 4)
        } else if (!state.prevFrametimeValid) {
            return false
        }

        val frameTime = readU16Be(data, 2)
        var spaceSize = 0
        val frameSamples = 40 shl (state.ratebits and 7)

        state.outputTiming = (state.outputTiming + frameSamples) and 0xFFFF

        if (info?.outputTimingPresent == true) {
            if (state.outputTimingValid && info.outputTiming != state.outputTiming) {
                state.prevFrametimeValid = false
                spaceSize = frameSamples * (64 shr (state.ratebits and 7))
                var prevOutput = (info.outputTiming - frameSamples) and 0xFFFF
                if (prevOutput < frameTime) prevOutput += 0x10000
                val currentOffset = prevOutput - frameTime
                if (state.outputTimeOffset >= currentOffset) {
                    state.padding += (state.outputTimeOffset - currentOffset) *
                        (64 shr (state.ratebits and 7))
                }
            }
            state.outputTiming = info.outputTiming
            state.outputTimingValid = true
        }

        if (state.prevFrametimeValid) {
            spaceSize = ((frameTime - state.prevFrametime) and 0xFFFF) *
                (64 shr (state.ratebits and 7))
        }

        if (spaceSize < state.prevMatFramesize) {
            val align = 64 shr (state.ratebits and 7)
            spaceSize = ((state.prevMatFramesize + align - 1) / align) * align
        }

        state.padding += (spaceSize - state.prevMatFramesize)

        if (state.padding > MAT_BUFFER_SIZE * 5) {
            reset()
            state.init = true
            return false
        }

        if (state.outputTimingValid) {
            var prevOutput = (state.outputTiming - frameSamples) and 0xFFFF
            if (prevOutput < frameTime) prevOutput += 0x10000
            state.outputTimeOffset = prevOutput - frameTime
        }

        state.prevFrametime = frameTime
        state.prevFrametimeValid = true

        if (bufferCount == 0) {
            writeHeader()
            if (!state.init) {
                state.init = true
                state.matFramesize = 0
            }
        }

        while (state.padding > 0) {
            writePadding()
            if (bufferCount == MAT_BUFFER_SIZE) {
                flushPacket()
                writeHeader()
            }
        }

        var remaining = fillDataBuffer(data, 0, data.size, Type.DATA)
        if (remaining > 0 || bufferCount == MAT_BUFFER_SIZE) {
            flushPacket()
            if (remaining > 0) {
                writeHeader()
                remaining = fillDataBuffer(data, data.size - remaining, remaining, Type.DATA)
            }
        }

        state.prevMatFramesize = state.matFramesize
        state.matFramesize = 0
        return outputQueue.isNotEmpty()
    }

    /**
     * True once an access unit has been accepted since the last [reset]. Until the first
     * major-sync unit arrives, [packAccessUnit] discards input, so callers anchoring a clock on
     * the stream must wait for this rather than for the first buffer.
     */
    val isSynced: Boolean
        get() = state.prevFrametimeValid

    /**
     * Base sample rate family of the stream (48 000 or 44 100). One access unit is always
     * 40 samples at this rate, whatever the shift, so its duration is 40 / baseSampleRate.
     * Meaningful once [isSynced].
     */
    fun baseSampleRate(): Int = if ((state.ratebits and 8) != 0) 44_100 else 48_000

    fun pollFrame(): ByteArray? = outputQueue.poll()

    fun hasFrame(): Boolean = outputQueue.isNotEmpty()

    // Returns a frame obtained from pollFrame once the caller is finished with it. The
    // pool is bounded; anything beyond the limit is left to the garbage collector.
    fun recycleFrame(frame: ByteArray) {
        if (frame.size == MAT_BUFFER_SIZE && framePool.size < FRAME_POOL_LIMIT) {
            framePool.add(frame)
        }
    }

    private fun writeHeader() {
        // Padding bytes are never written, so a reused frame must start all-zero.
        buffer = framePool.poll()?.also { it.fill(0) } ?: ByteArray(MAT_BUFFER_SIZE)
        val size = BURST_HEADER_SIZE + MAT_START_CODE.size
        System.arraycopy(MAT_START_CODE, 0, buffer, BURST_HEADER_SIZE, MAT_START_CODE.size)
        bufferCount = size
        state.matFramesize += size
        if (state.padding > 0) {
            if (state.padding > size) {
                state.padding -= size
                state.matFramesize = 0
            } else {
                state.matFramesize = size - state.padding
                state.padding = 0
            }
        }
    }

    private fun writePadding() {
        if (state.padding == 0) return
        val remaining = fillDataBuffer(null, 0, state.padding, Type.PADDING)
        if (remaining >= 0) {
            state.padding = remaining
            state.matFramesize = 0
        } else {
            state.padding = 0
            state.matFramesize = -remaining
        }
    }

    private fun appendData(data: ByteArray?, offset: Int, size: Int, type: Type) {
        if (type == Type.DATA && data != null && size > 0) {
            System.arraycopy(data, offset, buffer, bufferCount, size)
        }
        state.matFramesize += size
        bufferCount += size
    }

    private fun fillDataBuffer(data: ByteArray?, offset: Int, size: Int, type: Type): Int {
        if (bufferCount >= MAT_BUFFER_LIMIT) return size
        var remaining = size
        var srcOffset = offset

        if (bufferCount <= MAT_POS_MIDDLE && bufferCount + size > MAT_POS_MIDDLE) {
            val bytesBefore = MAT_POS_MIDDLE - bufferCount
            appendData(data, srcOffset, bytesBefore, type)
            remaining -= bytesBefore
            srcOffset += bytesBefore
            appendData(MAT_MIDDLE_CODE, 0, MAT_MIDDLE_CODE.size, Type.DATA)
            if (type == Type.PADDING) remaining -= MAT_MIDDLE_CODE.size
            if (remaining > 0) {
                remaining = fillDataBuffer(data, srcOffset, remaining, type)
            }
            return remaining
        }

        if (bufferCount + size >= MAT_BUFFER_LIMIT) {
            val bytesBefore = MAT_BUFFER_LIMIT - bufferCount
            appendData(data, srcOffset, bytesBefore, type)
            remaining -= bytesBefore
            appendData(MAT_END_CODE, 0, MAT_END_CODE.size, Type.DATA)
            if (type == Type.PADDING) remaining -= MAT_END_CODE.size
            return remaining
        }

        appendData(data, srcOffset, size, type)
        return 0
    }

    private fun flushPacket() {
        if (bufferCount == 0) return
        outputQueue.add(buffer)
        buffer = EMPTY_FRAME
        bufferCount = 0
    }

    private enum class Type { PADDING, DATA }

    private class MatState {
        var init: Boolean = false
        var ratebits: Int = 0
        var outputTiming: Int = 0
        var outputTimingValid: Boolean = false
        var prevFrametime: Int = 0
        var prevFrametimeValid: Boolean = false
        var matFramesize: Int = 0
        var prevMatFramesize: Int = 0
        var padding: Int = 0
        var outputTimeOffset: Int = 0

        fun reset() {
            init = false
            ratebits = 0
            outputTiming = 0
            outputTimingValid = false
            prevFrametime = 0
            prevFrametimeValid = false
            matFramesize = 0
            prevMatFramesize = 0
            padding = 0
            outputTimeOffset = 0
        }
    }

    private class MajorSyncInfo(
        val ratebits: Int,
        val outputTiming: Int,
        val outputTimingPresent: Boolean
    )

    companion object {
        const val MAT_BUFFER_SIZE = 61440
        const val BURST_HEADER_SIZE = 8
        private const val MAT_BUFFER_LIMIT = MAT_BUFFER_SIZE - 24
        private const val MAT_POS_MIDDLE = 30708 + BURST_HEADER_SIZE
        private const val FORMAT_MAJOR_SYNC = 0xF8726FBA.toInt()
        private const val FRAME_POOL_LIMIT = 8
        private val EMPTY_FRAME = ByteArray(0)

        val MAT_START_CODE = byteArrayOf(
            0x07, 0x9E.toByte(), 0x00, 0x03, 0x84.toByte(), 0x01, 0x01,
            0x01, 0x80.toByte(), 0x00, 0x56, 0xA5.toByte(), 0x3B, 0xF4.toByte(),
            0x81.toByte(), 0x83.toByte(), 0x49, 0x80.toByte(), 0x77, 0xE0.toByte()
        )
        val MAT_MIDDLE_CODE = byteArrayOf(
            0xC3.toByte(), 0xC1.toByte(), 0x42, 0x49, 0x3B, 0xFA.toByte(),
            0x82.toByte(), 0x83.toByte(), 0x49, 0x80.toByte(), 0x77, 0xE0.toByte()
        )
        val MAT_END_CODE = byteArrayOf(
            0xC3.toByte(), 0xC2.toByte(), 0xC0.toByte(), 0xC4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x97.toByte(), 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        fun trueHdAccessUnitSize(data: ByteArray, offset: Int): Int {
            if (offset + 2 > data.size) return 0
            val word = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            return (word and 0x0FFF) * 2
        }

        fun isMajorSync(data: ByteArray, offset: Int = 0): Boolean {
            if (offset + 8 > data.size) return false
            val sync = readU32Be(data, offset + 4)
            return (sync and 0xFFFFFFFE.toInt()) == (FORMAT_MAJOR_SYNC and 0xFFFFFFFE.toInt())
        }

        private fun parseMajorSync(data: ByteArray): MajorSyncInfo? {
            if (data.size < 32) return null
            var majorSyncSize = 28
            if ((data[29].toInt() and 1) != 0) {
                val extensionSize = (data[30].toInt() and 0xFF) shr 4
                majorSyncSize += 2 + extensionSize * 2
            }
            if (majorSyncSize > data.size) return null
            val bits = BitReader(data, 4)
            bits.skip(32)
            val ratebits = bits.read(4)
            bits.skip(1 + 1 + 2 + 2 + 2 + 5 + 2 + 13 + 16 + 16 + 16 + 1 + 15)
            val numSubstreams = bits.read(4)
            bits.skip(4 + (majorSyncSize - 17) * 8)
            for (i in 0 until numSubstreams) {
                val extra = bits.read(1)
                bits.skip(15)
                if (extra != 0) bits.skip(16)
            }
            var outputTiming = 0
            var present = false
            for (i in 0 until numSubstreams) {
                if (bits.read(1) != 0) {
                    if (bits.read(1) != 0) {
                        bits.skip(14)
                        outputTiming = bits.read(16)
                        present = true
                    }
                }
                break
            }
            return MajorSyncInfo(ratebits, outputTiming, present)
        }

        private fun readU16Be(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }

        private fun readU32Be(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }
    }

    private class BitReader(private val data: ByteArray, startByte: Int) {
        private var bitIndex = startByte * 8

        fun skip(n: Int) {
            bitIndex += n
        }

        fun read(n: Int): Int {
            var value = 0
            repeat(n) {
                if (bitIndex >= data.size * 8) return value
                val byte = data[bitIndex / 8].toInt() and 0xFF
                val bit = (byte shr (7 - (bitIndex % 8))) and 1
                value = (value shl 1) or bit
                bitIndex++
            }
            return value
        }
    }
}
