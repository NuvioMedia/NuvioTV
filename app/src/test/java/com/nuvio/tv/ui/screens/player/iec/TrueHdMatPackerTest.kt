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
    fun baseSampleRate_followsTheMajorSyncFamily() {
        val fortyEight = TrueHdMatPacker()
        fortyEight.packAccessUnit(trueHdAu(frameTime = 0, major = true, ratebits = 0))
        assertTrue(fortyEight.isSynced)
        assertEquals(48_000, fortyEight.baseSampleRate())

        val fortyFour = TrueHdMatPacker()
        fortyFour.packAccessUnit(trueHdAu(frameTime = 0, major = true, ratebits = 8))
        assertTrue(fortyFour.isSynced)
        assertEquals(44_100, fortyFour.baseSampleRate())
    }

    @Test
    fun fortyFourK1AccessUnits_stillEmitAMatFrame() {
        val packer = TrueHdMatPacker()
        var frames = 0
        for (i in 0 until 48) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = i == 0, ratebits = 8))
            while (packer.hasFrame()) {
                packer.pollFrame()
                frames++
            }
            if (frames > 0) break
        }
        assertTrue("expected a MAT frame within 48 AUs at 44.1 kHz", frames >= 1)
    }

    @Test
    fun gapLargerThanOneMatFrame_emitsPaddingFramesAndKeepsTheAccessUnit() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 5) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        while (packer.hasFrame()) packer.pollFrame()

        val jumped = trueHdAu(frameTime = 5 * 40 + 2_000, major = false)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)

        val frames = drain(packer)
        assertTrue("padding should span more than one MAT frame, got ${frames.size}", frames.size >= 2)
        frames.forEachIndexed { index, frame ->
            assertEquals(TrueHdMatPacker.MAT_BUFFER_SIZE, frame.size)
            assertMatCodes(frame, "gap frame $index")
        }
        assertTrue(
            "the access unit after the gap was dropped",
            recoverMarker(packer, frames, 5 * 40 + 2_000)
        )
    }

    @Test
    fun seamlessBranch_paddingPastFiveMatFrames_keepsSyncAndTheMajorUnit() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(majorWithTiming(frameTime = 0, outputTiming = 8_000))
        for (i in 1 until 6) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        while (packer.hasFrame()) packer.pollFrame()
        assertTrue(packer.isSynced)

        val branchFrameTime = 6 * 40
        val branch = majorWithTiming(frameTime = branchFrameTime, outputTiming = branchFrameTime + 40)
        mark(branch, MARKER)
        assertTrue(packer.packAccessUnit(branch))
        assertTrue(packer.isSynced)
        assertEquals(48_000, packer.baseSampleRate())

        val frames = drain(packer)
        assertTrue(
            "a branch gap over 100 ms must be packed, not dropped, got ${frames.size}",
            frames.size > 5
        )
        frames.forEachIndexed { index, frame -> assertMatCodes(frame, "branch frame $index") }
        frames.drop(1).filterNot { it.containsMarker() }.forEachIndexed { index, frame ->
            assertPaddingSilence(frame, "branch padding $index")
        }
        assertTrue(
            "major sync after the branch was dropped",
            recoverMarker(packer, frames, branchFrameTime)
        )
        assertTrue(packer.isSynced)
    }

    @Test
    fun gapPastFiveMatFrames_isPackedInsteadOfResettingSync() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 5) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        while (packer.hasFrame()) packer.pollFrame()

        val jumped = trueHdAu(frameTime = 4 * 40 + 8_000, major = false)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)

        val frames = drain(packer)
        assertTrue("expected padding across more than five MAT frames, got ${frames.size}", frames.size > 5)
        frames.forEachIndexed { index, frame -> assertMatCodes(frame, "long gap frame $index") }
        frames.drop(1).filterNot { it.containsMarker() }.forEachIndexed { index, frame ->
            assertPaddingSilence(frame, "long gap padding $index")
        }
        assertTrue(recoverMarker(packer, frames, 4 * 40 + 8_000))
        assertTrue(packer.isSynced)
    }

    @Test
    fun largestSixteenBitFrameTimeGap_packsWithinBoundsAndStaysSynced() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        drain(packer)

        val jumped = trueHdAu(frameTime = 3 * 40 + 65_535, major = false, size = 64)
        mark(jumped, MARKER)
        mark(jumped, TAIL, at = 24)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)
        assertEquals(48_000, packer.baseSampleRate())

        val frames = drain(packer)
        assertTrue("full 16-bit gap produced ${frames.size} frames", frames.size in 60..80)
        frames.forEachIndexed { index, frame -> assertMatCodes(frame, "max gap $index") }
        frames.drop(1).filterNot { it.containsMarker() || it.containsMarker(TAIL) }.forEachIndexed { index, frame ->
            assertPaddingSilence(frame, "max gap padding $index")
        }
        val seen = withFollowUps(packer, frames, 3 * 40 + 65_535, byteArrayOf(MARKER, TAIL))
        assertTrue(seen.any { it.containsMarker(MARKER) })
        assertTrue(seen.any { it.containsMarker(TAIL) })
        assertTrue(packer.isSynced)
    }

    @Test
    fun frameTimeWrap_withAMultiFrameGap_keepsSync() {
        val packer = TrueHdMatPacker()
        val origin = 60_000
        packer.packAccessUnit(trueHdAu(frameTime = origin, major = true))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = origin + i * 40, major = false))
        }
        drain(packer)

        val jumpedTime = origin + 3 * 40 + 8_000
        val jumped = trueHdAu(frameTime = jumpedTime, major = false)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)

        val frames = drain(packer)
        assertTrue("wrapped gap produced ${frames.size} frames", frames.size > 5)
        frames.forEachIndexed { index, frame -> assertMatCodes(frame, "wrap $index") }
        assertTrue(recoverMarker(packer, frames, jumpedTime))

        packer.packAccessUnit(trueHdAu(frameTime = jumpedTime + 40, major = false))
        assertTrue(packer.isSynced)
    }

    @Test
    fun gapAccessUnit_isReadFromTheGivenOffset() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        drain(packer)

        val au = trueHdAu(frameTime = 3 * 40 + 2_000, major = false, size = 80)
        mark(au, MARKER)
        val carrier = ByteArray(au.size + 30)
        carrier.fill(0x2A)
        au.copyInto(carrier, destinationOffset = 20)
        assertTrue(packer.packAccessUnit(carrier, 20, au.size))
        assertTrue(packer.isSynced)

        val frames = drain(packer)
        assertTrue(frames.size >= 2)
        frames.forEachIndexed { index, frame -> assertMatCodes(frame, "offset gap $index") }
        assertTrue(frames.none { frame -> frame.any { it == 0x2A.toByte() } })
        assertTrue(recoverMarker(packer, frames, 3 * 40 + 2_000))
    }

    @Test
    fun forwardOutputTimingJump_doesNotInventPaddingFrames() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(majorWithTiming(frameTime = 0, outputTiming = 40))
        for (i in 1 until 6) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        drain(packer)

        val branchTime = 6 * 40
        val branch = majorWithTiming(frameTime = branchTime, outputTiming = 8_000)
        mark(branch, MARKER)
        packer.packAccessUnit(branch)
        assertTrue(packer.isSynced)
        val frames = drain(packer)
        assertTrue("forward timing jump produced ${frames.size} frames", frames.size <= 1)
        assertTrue(recoverMarker(packer, frames, branchTime))
        assertTrue(packer.isSynced)
    }

    @Test
    fun outputTimingWrap_thatMatchesTheCounter_doesNotPad() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(majorWithTiming(frameTime = 0, outputTiming = 65_520))
        packer.packAccessUnit(trueHdAu(frameTime = 40, major = false))
        drain(packer)

        val matched = majorWithTiming(frameTime = 80, outputTiming = 64)
        mark(matched, MARKER)
        packer.packAccessUnit(matched)
        assertTrue(packer.isSynced)
        val frames = drain(packer)
        assertTrue("matching wrapped timing produced ${frames.size} frames", frames.size <= 1)
        assertTrue(recoverMarker(packer, frames, 80))
    }

    @Test
    fun successiveBranches_eachPadAcrossMatFrames() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(majorWithTiming(frameTime = 0, outputTiming = 6_000))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        drain(packer)

        var frameTime = 4 * 40
        val first = majorWithTiming(frameTime = frameTime, outputTiming = frameTime + 40)
        mark(first, MARKER)
        assertTrue(packer.packAccessUnit(first))
        val firstFrames = ArrayList(drain(packer))
        assertTrue("first branch produced ${firstFrames.size} frames", firstFrames.size > 5)
        firstFrames.forEach { assertMatCodes(it, "first branch") }
        var foundFirst = firstFrames.any { it.containsMarker() }
        var guard = 0
        while (!foundFirst && guard < 48) {
            frameTime += 40
            guard++
            packer.packAccessUnit(trueHdAu(frameTime = frameTime, major = false))
            val more = drain(packer)
            more.forEach { assertMatCodes(it, "after first branch") }
            if (more.any { it.containsMarker() }) foundFirst = true
        }
        assertTrue(foundFirst)

        frameTime += 40
        packer.packAccessUnit(majorWithTiming(frameTime = frameTime, outputTiming = 7_000))
        for (n in 1..3) {
            frameTime += 40
            packer.packAccessUnit(trueHdAu(frameTime = frameTime, major = false))
        }
        drain(packer)
        assertTrue(packer.isSynced)

        frameTime += 40
        val second = majorWithTiming(frameTime = frameTime, outputTiming = frameTime + 40)
        mark(second, TAIL)
        assertTrue(packer.packAccessUnit(second))
        val secondFrames = drain(packer)
        assertTrue("second branch produced ${secondFrames.size} frames", secondFrames.size > 5)
        secondFrames.forEach { assertMatCodes(it, "second branch") }
        secondFrames.drop(1).filterNot { it.containsMarker(TAIL) }.forEachIndexed { index, frame ->
            assertPaddingSilence(frame, "second padding $index")
        }
        assertTrue(recoverMarker(packer, secondFrames, frameTime, TAIL))
        assertTrue(packer.isSynced)
        assertEquals(48_000, packer.baseSampleRate())
    }

    @Test
    fun fortyFourOneGap_keepsTheSampleRateFamily() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true, ratebits = 8))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false, ratebits = 8))
        }
        drain(packer)
        assertEquals(44_100, packer.baseSampleRate())

        val jumped = trueHdAu(frameTime = 3 * 40 + 8_000, major = false, ratebits = 8)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)
        assertEquals(44_100, packer.baseSampleRate())
        val frames = drain(packer)
        assertTrue("44.1 gap produced ${frames.size} frames", frames.size > 5)
        frames.forEach { assertMatCodes(it, "44.1 gap") }
        assertTrue(recoverMarker(packer, frames, 3 * 40 + 8_000))
        assertEquals(44_100, packer.baseSampleRate())
    }

    @Test
    fun higherRateGap_stillCrossesMatFrames() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true, ratebits = 1))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 80, major = false, ratebits = 1))
        }
        drain(packer)

        val jumped = trueHdAu(frameTime = 3 * 80 + 4_000, major = false, ratebits = 1)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)
        assertEquals(48_000, packer.baseSampleRate())
        val frames = drain(packer)
        assertTrue("96 kHz-family gap produced ${frames.size} frames", frames.size >= 2)
        frames.forEach { assertMatCodes(it, "high rate gap") }
        assertTrue(recoverMarker(packer, frames, 3 * 80 + 4_000))
    }

    @Test
    fun resetDuringQueuedPadding_dropsFramesUntilTheNextMajorSync() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        val jumped = trueHdAu(frameTime = 3 * 40 + 8_000, major = false)
        mark(jumped, MARKER)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.hasFrame())

        packer.reset()
        assertTrue(!packer.hasFrame())
        assertTrue(!packer.isSynced)
        assertEquals(null, packer.pollFrame())
        assertTrue(!packer.packAccessUnit(trueHdAu(frameTime = 40, major = false)))
        assertTrue(!packer.isSynced)

        val major = trueHdAu(frameTime = 0, major = true)
        mark(major, MARKER)
        packer.packAccessUnit(major)
        assertTrue(packer.isSynced)
        assertEquals(48_000, packer.baseSampleRate())
    }

    @Test
    fun longAccessUnit_afterAGap_keepsBothEnds() {
        val packer = TrueHdMatPacker()
        packer.packAccessUnit(trueHdAu(frameTime = 0, major = true))
        for (i in 1 until 4) {
            packer.packAccessUnit(trueHdAu(frameTime = i * 40, major = false))
        }
        drain(packer)

        val jumped = trueHdAu(frameTime = 3 * 40 + 2_000, major = false, size = 4_000)
        mark(jumped, MARKER, at = 16)
        mark(jumped, TAIL)
        assertTrue(packer.packAccessUnit(jumped))
        assertTrue(packer.isSynced)
        val frames = drain(packer)
        assertTrue(frames.size >= 2)
        frames.forEach { assertMatCodes(it, "split au") }
        val seen = withFollowUps(packer, frames, 3 * 40 + 2_000, byteArrayOf(MARKER, TAIL))
        assertTrue(seen.any { it.containsMarker(MARKER) })
        assertTrue(seen.any { it.containsMarker(TAIL) })
    }

    private fun drain(packer: TrueHdMatPacker): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        while (packer.hasFrame()) frames.add(packer.pollFrame()!!)
        return frames
    }

    private fun mark(au: ByteArray, marker: Byte, at: Int = au.size - 1) {
        au[at] = marker
    }

    private fun ByteArray.containsMarker(marker: Byte = MARKER): Boolean {
        return any { it == marker }
    }

    private fun recoverMarker(
        packer: TrueHdMatPacker,
        already: List<ByteArray>,
        frameTime: Int,
        marker: Byte = MARKER
    ): Boolean {
        return withFollowUps(packer, already, frameTime, byteArrayOf(marker)).any { it.containsMarker(marker) }
    }

    private fun withFollowUps(
        packer: TrueHdMatPacker,
        already: List<ByteArray>,
        frameTime: Int,
        markers: ByteArray
    ): List<ByteArray> {
        val all = ArrayList(already)
        fun hasAll() = markers.all { marker -> all.any { it.containsMarker(marker) } }
        if (hasAll()) return all
        for (n in 1 until 64) {
            packer.packAccessUnit(trueHdAu(frameTime = frameTime + n * 40, major = false))
            while (packer.hasFrame()) {
                val frame = packer.pollFrame()!!
                assertMatCodes(frame, "follow-up")
                all.add(frame)
            }
            if (hasAll()) break
        }
        return all
    }

    private fun assertPaddingSilence(frame: ByteArray, label: String) {
        assertMatCodes(frame, label)
        for (index in frame.indices) {
            if (isMatCode(index)) continue
            if (frame[index].toInt() != 0) {
                assertEquals("$label byte $index", 0, frame[index].toInt() and 0xFF)
            }
        }
    }

    private fun isMatCode(index: Int): Boolean {
        val start = TrueHdMatPacker.BURST_HEADER_SIZE
        if (index in start until start + TrueHdMatPacker.MAT_START_CODE.size) return true
        val middleAt = 30708 + TrueHdMatPacker.BURST_HEADER_SIZE
        if (index in middleAt until middleAt + TrueHdMatPacker.MAT_MIDDLE_CODE.size) return true
        val endAt = TrueHdMatPacker.MAT_BUFFER_SIZE - TrueHdMatPacker.MAT_END_CODE.size
        return index in endAt until TrueHdMatPacker.MAT_BUFFER_SIZE
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
        private const val MARKER: Byte = 0x5A
        private val TAIL: Byte = 0xA3.toByte()

        fun trueHdAu(frameTime: Int, major: Boolean, size: Int = 40, ratebits: Int = 0): ByteArray {
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
                au[8] = (ratebits shl 4).toByte()
            }
            return au
        }

        fun majorWithTiming(frameTime: Int, outputTiming: Int, ratebits: Int = 0): ByteArray {
            val au = trueHdAu(frameTime = frameTime, major = true, size = 48, ratebits = ratebits)
            setBits(au, 32 + 128, 4, 1)
            setBits(au, 32 + 240, 1, 1)
            setBits(au, 32 + 241, 1, 1)
            setBits(au, 32 + 256, 16, outputTiming)
            return au
        }

        private fun setBits(data: ByteArray, bitIndex: Int, width: Int, value: Int) {
            for (i in 0 until width) {
                val bit = (value shr (width - 1 - i)) and 1
                val absolute = bitIndex + i
                val byteIndex = absolute / 8
                val shift = 7 - (absolute % 8)
                val mask = 1 shl shift
                data[byteIndex] = if (bit == 1) {
                    (data[byteIndex].toInt() or mask).toByte()
                } else {
                    (data[byteIndex].toInt() and mask.inv()).toByte()
                }
            }
        }

        private fun assertMatCodes(frame: ByteArray, label: String) {
            for (i in TrueHdMatPacker.MAT_START_CODE.indices) {
                assertEquals(label, TrueHdMatPacker.MAT_START_CODE[i], frame[TrueHdMatPacker.BURST_HEADER_SIZE + i])
            }
            val middleAt = 30708 + TrueHdMatPacker.BURST_HEADER_SIZE
            for (i in TrueHdMatPacker.MAT_MIDDLE_CODE.indices) {
                assertEquals(label, TrueHdMatPacker.MAT_MIDDLE_CODE[i], frame[middleAt + i])
            }
            val endAt = TrueHdMatPacker.MAT_BUFFER_SIZE - TrueHdMatPacker.MAT_END_CODE.size
            for (i in TrueHdMatPacker.MAT_END_CODE.indices) {
                assertEquals(label, TrueHdMatPacker.MAT_END_CODE[i], frame[endAt + i])
            }
        }
    }
}
