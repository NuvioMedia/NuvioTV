package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrueHdMatPackerTest {

    @Test
    fun twentyFourAccessUnits_emitOneMatFrame() {
        val packer = TrueHdMatPacker()
        var frames = 0
        var last: ByteArray? = null
        for (i in 0 until 48) {
            val packed = packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = i == 0))
            if (packed) {
                while (packer.hasFrame()) {
                    last = packer.pollFrame()
                    frames++
                }
            }
            if (frames > 0) break
        }
        assertTrue("expected a MAT frame within 48 AUs", frames >= 1)
        val frame = last!!
        assertEquals(TrueHdMatPacker.MAT_BUFFER_SIZE, frame.size)
        assertEquals(0, frame[0].toInt())
        assertEquals(0, frame[1].toInt())
        for (i in TrueHdMatPacker.MAT_START_CODE.indices) {
            assertEquals(
                TrueHdMatPacker.MAT_START_CODE[i],
                frame[TrueHdMatPacker.BURST_HEADER_SIZE + i]
            )
        }
        val middleAt = 30708 + TrueHdMatPacker.BURST_HEADER_SIZE
        for (i in TrueHdMatPacker.MAT_MIDDLE_CODE.indices) {
            assertEquals(TrueHdMatPacker.MAT_MIDDLE_CODE[i], frame[middleAt + i])
        }
        val endAt = TrueHdMatPacker.MAT_BUFFER_SIZE - TrueHdMatPacker.MAT_END_CODE.size
        for (i in TrueHdMatPacker.MAT_END_CODE.indices) {
            assertEquals(TrueHdMatPacker.MAT_END_CODE[i], frame[endAt + i])
        }
    }

    @Test
    fun accessUnitSize_readsTwelveBitWord() {
        val au = ByteArray(40)
        au[0] = 0x00
        au[1] = 0x14
        assertEquals(40, TrueHdMatPacker.trueHdAccessUnitSize(au, 0))
    }

    @Test
    fun reset_dropsQueuedFrames() {
        val packer = TrueHdMatPacker()
        for (i in 0 until 48) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = i == 0))
        }
        packer.reset()
        assertTrue(!packer.hasFrame())
        assertEquals(null, packer.pollFrame())
    }

    @Test
    fun recycledFrames_areReusedAndByteIdenticalToFreshOnes() {
        val fresh = TrueHdMatPacker()
        val pooled = TrueHdMatPacker()
        val freshFrames = ArrayList<ByteArray>()
        val pooledFrames = ArrayList<ByteArray>()
        val handedOut = java.util.IdentityHashMap<ByteArray, Boolean>()
        var reused = false
        for (i in 0 until 240) {
            val au = trueHdAu(frameTime = (i * 40) and 0xFFFF, major = i % 24 == 0)
            if (fresh.packAccessUnit(au)) {
                while (fresh.hasFrame()) freshFrames.add(fresh.pollFrame()!!)
            }
            if (pooled.packAccessUnit(au.copyOf())) {
                while (pooled.hasFrame()) {
                    val frame = pooled.pollFrame()!!
                    if (handedOut.put(frame, true) != null) reused = true
                    pooledFrames.add(frame.copyOf())
                    // Dirty the frame before handing it back: a reused frame that is not
                    // zero-filled would show this in its padding.
                    frame.fill(0x5A)
                    pooled.recycleFrame(frame)
                }
            }
        }
        assertTrue("expected several frames, got ${freshFrames.size}", freshFrames.size >= 3)
        assertEquals(freshFrames.size, pooledFrames.size)
        for (i in freshFrames.indices) {
            assertTrue("frame $i differs", freshFrames[i].contentEquals(pooledFrames[i]))
        }
        assertTrue("a recycled frame was never handed out again", reused)
    }

    @Test
    fun recycleFrame_withWrongSizesAndOverflow_doesNotBreakPacking() {
        val packer = TrueHdMatPacker()
        packer.recycleFrame(ByteArray(10))
        repeat(20) { packer.recycleFrame(ByteArray(TrueHdMatPacker.MAT_BUFFER_SIZE)) }
        for (i in 0 until 48) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = i == 0))
        }
        assertTrue(packer.hasFrame())
        assertEquals(TrueHdMatPacker.MAT_BUFFER_SIZE, packer.pollFrame()!!.size)
    }

    companion object {
        fun trueHdAu(frameTime: Int, major: Boolean, size: Int = 40): ByteArray {
            val au = ByteArray(size)
            val word = size / 2
            au[0] = ((word shr 8) and 0x0F).toByte()
            au[1] = (word and 0xFF).toByte()
            au[2] = (frameTime shr 8).toByte()
            au[3] = (frameTime and 0xFF).toByte()
            if (major) {
                au[4] = 0xF8.toByte()
                au[5] = 0x72
                au[6] = 0x6F
                au[7] = 0xBA.toByte()
                au[8] = 0x00
            }
            return au
        }
    }
}
