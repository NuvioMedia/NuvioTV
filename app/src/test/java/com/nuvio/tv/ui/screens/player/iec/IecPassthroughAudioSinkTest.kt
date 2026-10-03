package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class IecPassthroughAudioSinkTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun trueHd_writesIecBurstsToTrackAndReportsContentTime() {
        val fakeTrack = FakeIecAudioTrack(sampleRate = 192_000, frameSizeBytes = 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> fakeTrack }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        var pts = 0L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            val buf = ByteBuffer.wrap(au)
            assertTrue(sink.handleBuffer(buf, pts, 1))
            pts += 833L
        }
        assertTrue(fakeTrack.written >= Iec61937Packer.TRUEHD_IEC_SIZE)
        assertEquals(0, fakeTrack.written % Iec61937Packer.TRUEHD_IEC_SIZE)
        val position = sink.getCurrentPositionUs(false)
        assertTrue("clock should advance with IEC frames, was $position", position >= 0L)
        assertTrue("clock should stay near 20 ms, was $position", position < 80_000L)
    }

    @Test
    fun trueHd_positionSubtractsOutputLatencyButNotBeforeTheAnchor() {
        val latencyUs = 10_000L
        val track = LatencyIecAudioTrack(latencyUs)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> track }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var pts = 0L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        val headUs = (track.written / 16).toLong() * C.MICROS_PER_SECOND / 192_000L
        assertTrue(headUs > latencyUs)
        assertEquals(headUs - latencyUs, sink.getCurrentPositionUs(false))
        assertEquals(headUs - latencyUs, sink.getCurrentPositionUs(true))
    }

    @Test
    fun trueHd_positionStaysOnTheAnchorWhenLatencyExceedsTheHead() {
        val track = LatencyIecAudioTrack(500_000L)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> track }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var pts = 0L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        val headUs = (track.written / 16).toLong() * C.MICROS_PER_SECOND / 192_000L
        assertTrue(headUs < 500_000L)
        assertEquals(0L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun trueHd_latencyDoesNotPullAShiftedAnchorBackwards() {
        val latencyUs = 40_000L
        val track = LatencyIecAudioTrack(latencyUs)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> track }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        val bufferPts = 1_000_000L
        val chunk = ByteBuffer.allocate(40 * 19)
        for (i in 0 until 19) {
            chunk.put(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 3))
        }
        chunk.flip()
        assertTrue(sink.handleBuffer(chunk, bufferPts, 19))
        var pts = bufferPts + 19 * 833L
        for (i in 19 until 120) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = false)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        val expectedAnchor = bufferPts + 3L * 40L * C.MICROS_PER_SECOND / 48_000L
        val headUs = (track.written / 16).toLong() * C.MICROS_PER_SECOND / 192_000L
        assertTrue(headUs > latencyUs)
        assertEquals(expectedAnchor + headUs - latencyUs, sink.getCurrentPositionUs(false))
        assertTrue(sink.getCurrentPositionUs(false) > expectedAnchor)
    }

    @Test
    fun trueHd_anchorsOnFirstAcceptedAccessUnit_notOnFirstBuffer() {
        val fakeTrack = FakeIecAudioTrack(sampleRate = 192_000, frameSizeBytes = 16)
        val events = mutableListOf<String>()
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> fakeTrack },
            onDiagnosticEvent = { events.add(it) }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        val bufferPts = 1_000_000L
        val chunk = ByteBuffer.allocate(40 * 19)
        for (i in 0 until 19) {
            chunk.put(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 3))
        }
        chunk.flip()
        assertTrue(sink.handleBuffer(chunk, bufferPts, 19))
        var pts = bufferPts + 19 * 833L
        for (i in 19 until 60) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = false)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        assertTrue(fakeTrack.written >= Iec61937Packer.TRUEHD_IEC_SIZE)

        val headUs = (fakeTrack.written / 16).toLong() * 1_000_000L / 192_000L
        val expectedAnchor = bufferPts + 3L * 40L * 1_000_000L / 48_000L
        val position = sink.getCurrentPositionUs(false)
        assertEquals(expectedAnchor + headUs, position)
        assertTrue(
            "anchoring on the buffer would have read ${bufferPts + headUs}",
            position != bufferPts + headUs
        )
        val anchor = events.single { it.startsWith("iec_anchor ") }
        assertTrue(anchor, anchor.contains("discardedAu=3"))
        assertTrue(anchor, anchor.contains("inBuffer=3"))
        assertTrue(anchor, anchor.contains("anchorPts=$expectedAnchor"))
        assertTrue(anchor, anchor.contains("deltaUs=2500"))
    }

    @Test
    fun trueHd_afterFlush_reanchorsOnTheNextAcceptedUnit() {
        val fakeTrack = FakeIecAudioTrack(sampleRate = 192_000, frameSizeBytes = 16)
        val events = mutableListOf<String>()
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> fakeTrack },
            onDiagnosticEvent = { events.add(it) }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var pts = 0L
        for (i in 0 until 30) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        assertEquals(1, events.count { it.startsWith("iec_anchor ") })

        sink.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET.toLong(), sink.getCurrentPositionUs(false))

        val seekPts = 5_000_000L
        val chunk = ByteBuffer.allocate(40 * 16)
        for (i in 0 until 16) {
            chunk.put(TrueHdMatPackerTest.trueHdAu(frameTime = 1000 + i * 40, major = i == 2))
        }
        chunk.flip()
        assertTrue(sink.handleBuffer(chunk, seekPts, 16))
        val anchor = events.filter { it.startsWith("iec_anchor ") }.last()
        val expectedAnchor = seekPts + 2L * 40L * 1_000_000L / 48_000L
        assertTrue(anchor, anchor.contains("anchorPts=$expectedAnchor"))
        assertTrue(anchor, anchor.contains("deltaUs=1666"))
    }

    @Test
    fun pcm_isForwardedWithoutIec() {
        val sink = IecPassthroughAudioSink(RecordingSink())
        sink.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2)
                .setSampleRate(48_000)
                .build(),
            0,
            null
        )
        assertFalse(sink.isIecActive)
        val buf = ByteBuffer.allocate(32)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
    }

    @Test
    fun trueHd_fallsBackWhenTrackFactoryReturnsNull() {
        val inner = RecordingSink()
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> null }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        val buf = ByteBuffer.allocate(40)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
        assertEquals(1, inner.buffers)
    }

    @Test
    fun trueHd_malformedAccessUnitHeader_dropsRemainderAndResyncs() {
        val fakeTrack = FakeIecAudioTrack(sampleRate = 192_000, frameSizeBytes = 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> fakeTrack }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()

        val junk = ByteArray(200)
        junk[0] = 0x00
        junk[1] = 0x02
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(junk), 0L, 1))

        var pts = 833L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 833L
        }
        assertTrue(
            "valid access units after junk must still produce frames",
            fakeTrack.written >= Iec61937Packer.TRUEHD_IEC_SIZE
        )
    }

    @Test
    fun iecHealth_reportsOnFirstBufferAndWhenUnderrunsChange() {
        val lines = mutableListOf<String>()
        val fakeTrack = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(fakeTrack),
            onDiagnosticEvent = { lines.add(it) }
        )
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        val first = lines.filter { it.startsWith("iec_health ") }
        assertEquals(1, first.size)
        assertTrue(first[0], first[0].contains("underruns=0"))

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 10_000L, 1))
        assertEquals(1, lines.count { it.startsWith("iec_health ") })

        fakeTrack.underruns = 1
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 20_000L, 1))
        val after = lines.filter { it.startsWith("iec_health ") }
        assertEquals(2, after.size)
        assertTrue(after[1], after[1].contains("underruns=1"))
    }

    @Test
    fun probe_startsOnlyWhenTheSinkWillUseIec() {
        val enabled = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = enabled)
        assertTrue(enabled.probeStarted)
        assertEquals(1, enabled.probeStartCount)

        val optical = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = optical, hbrIecEnabled = false)
        assertFalse(optical.probeStarted)
        assertEquals(0, optical.probeStartCount)
    }

    @Test
    fun configure_rearmsTheIecProbe_andMarksLivePassthrough() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        assertEquals(1, factory.probeStartCount)
        assertFalse(LiveDirectAudioPlayback.isPassthroughLive())

        sink.configure(dtsHdFormat(), 0, null)
        assertEquals(2, factory.probeStartCount)
        assertTrue(sink.isIecActive)
        assertTrue(LiveDirectAudioPlayback.isPassthroughLive())

        sink.reset()
        assertFalse(LiveDirectAudioPlayback.isPassthroughLive())
    }

    @Test
    fun configure_hbrRawWaitingOnIec_doesNotBlockTheProbe() {
        val factory = ReadyFactory(
            track = FakeIecAudioTrack(192_000, 16),
            readyAt = { false }
        )
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertFalse(LiveDirectAudioPlayback.isPassthroughLive())
    }

    @Test
    fun configure_coreCodecForward_marksWrappedDirectLive() {
        val factory = ReadyFactory(
            track = FakeIecAudioTrack(192_000, 16),
            readyAt = { false }
        )
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setChannelCount(6)
                .setSampleRate(48_000)
                .build(),
            0,
            null
        )
        assertFalse(sink.isIecActive)
        assertTrue(LiveDirectAudioPlayback.isPassthroughLive())
    }

    @Test
    fun configure_pcm_doesNotMarkPassthroughLive() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(2)
                .setSampleRate(48_000)
                .build(),
            0,
            null
        )
        assertFalse(sink.isIecActive)
        assertFalse(LiveDirectAudioPlayback.isPassthroughLive())
    }

    @Test
    fun dtsHd_reportsTheOpenedTrackBufferNotFourDefaultBursts() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        val fourBurstBytes = (8192 shl 2) * 4
        assertTrue(
            "opened ${factory.lastBufferSizeBytes} bytes, four bursts is $fourBurstBytes",
            factory.lastBufferSizeBytes > fourBurstBytes
        )
        val expectedUs = factory.lastBufferSizeBytes.toLong() / 16 * C.MICROS_PER_SECOND / 192_000L
        assertEquals(expectedUs, sink.getAudioTrackBufferSizeUs())
        assertEquals(
            IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS * C.MICROS_PER_SECOND / 1000,
            sink.getAudioTrackBufferSizeUs()
        )
    }

    @Test
    fun trueHd_reportsTheOpenedTrackBufferNotTwoMatFrames() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, payload = HbrPayload.MAT))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        val twoMatBytes = TrueHdMatPacker.MAT_BUFFER_SIZE * 2
        assertTrue(factory.lastBufferSizeBytes > twoMatBytes)
        assertEquals(
            IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS * C.MICROS_PER_SECOND / 1000,
            sink.getAudioTrackBufferSizeUs()
        )
    }

    @Test
    fun trueHd_44k1_opens176400WithoutWaitingForTheIecProbe() {
        val track = FakeIecAudioTrack(176_400, 16, payload = HbrPayload.MAT)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(trueHdFormat(sampleRate = 44_100), 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(176_400, factory.lastSampleRate)
        assertEquals(listOf(176_400), factory.openedRates)
        assertEquals(
            IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS * C.MICROS_PER_SECOND / 1000,
            sink.getAudioTrackBufferSizeUs()
        )
    }

    @Test
    fun trueHd_44k1_doesNotOpen192000When176400IsRefused() {
        val inner = RecordingSink()
        val factory = ReadyFactory(
            FakeIecAudioTrack(192_000, 16, payload = HbrPayload.MAT),
            refuseRate = 176_400
        )
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        val format = trueHdFormat(sampleRate = 44_100)
        sink.configure(format, 0, null)
        assertFalse(sink.isIecActive)
        assertEquals(listOf(176_400), factory.openedRates)
        assertEquals(1, inner.configured)
        assertEquals(format, inner.lastConfigured)
    }

    @Test
    fun trueHd_44k1_doesNotClaimHbrWhenOnly192000CanOpen() {
        val factory = ReadyFactory(
            FakeIecAudioTrack(192_000, 16, payload = HbrPayload.MAT),
            canOpenAt = { it == 192_000 },
            refuseRate = 176_400
        )
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = factory
        )
        val format = trueHdFormat(sampleRate = 44_100)
        assertFalse(sink.claimsHbr(format))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format))
        assertTrue(sink.claimsHbr(trueHdFormat()))
    }

    @Test
    fun trueHd_44k1_refused176400_forwardsTheAccessUnitInsteadOfA192000Clock() {
        val inner = RecordingSink()
        val factory = ReadyFactory(
            FakeIecAudioTrack(192_000, 16, payload = HbrPayload.MAT),
            refuseRate = 176_400
        )
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(trueHdFormat(sampleRate = 44_100), 0, null)
        sink.play()
        val au = TrueHdMatPackerTest.trueHdAu(frameTime = 0, major = true, ratebits = 8)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), 0L, 1))
        assertFalse(sink.isIecActive)
        assertEquals(1, inner.buffers)
        assertEquals(listOf(176_400), factory.openedRates)
    }

    @Test
    fun trueHd_48k_staysAt192000EvenWhen176400IsAvailable() {
        val factory = ReadyFactory(
            FakeIecAudioTrack(192_000, 16, payload = HbrPayload.MAT),
            readyAt = { it == 192_000 || it == 176_400 }
        )
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(192_000, factory.lastSampleRate)
        assertEquals(listOf(192_000), factory.openedRates)
    }

    @Test
    fun trueHd_44k1_matClockIsContentTimeAt176400NotTwentyMsAt192000() {
        val track = FakeIecAudioTrack(176_400, 16, payload = HbrPayload.MAT)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(trueHdFormat(sampleRate = 44_100), 0, null)
        sink.play()

        var pts = 0L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0, ratebits = 8)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 40L * C.MICROS_PER_SECOND / 44_100L
        }
        assertTrue(track.written >= Iec61937Packer.TRUEHD_IEC_SIZE)
        assertEquals(0, track.written % Iec61937Packer.TRUEHD_IEC_SIZE)
        assertEquals(176_400, factory.lastSampleRate)

        val headFrames = (track.written / 16).toLong()
        val position = sink.getCurrentPositionUs(false)
        assertEquals(headFrames * C.MICROS_PER_SECOND / 176_400L, position)
        assertTrue(
            "44.1 MAT duration at 192 kHz would be 20 ms/frame; 176.4 must not match that",
            position != headFrames * C.MICROS_PER_SECOND / 192_000L
        )
    }

    @Test
    fun trueHd_44k1_anchorsDiscardedAccessUnitsAt44100() {
        val track = FakeIecAudioTrack(176_400, 16, payload = HbrPayload.MAT)
        val events = mutableListOf<String>()
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(track),
            onDiagnosticEvent = { events.add(it) }
        )
        sink.configure(trueHdFormat(sampleRate = 44_100), 0, null)
        sink.play()

        val bufferPts = 1_000_000L
        val chunk = ByteBuffer.allocate(40 * 19)
        for (i in 0 until 19) {
            chunk.put(
                TrueHdMatPackerTest.trueHdAu(
                    frameTime = i * 40,
                    major = i == 3,
                    ratebits = 8
                )
            )
        }
        chunk.flip()
        assertTrue(sink.handleBuffer(chunk, bufferPts, 19))
        var pts = bufferPts + 19L * 40L * C.MICROS_PER_SECOND / 44_100L
        for (i in 19 until 60) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = false, ratebits = 8)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += 40L * C.MICROS_PER_SECOND / 44_100L
        }
        assertTrue(track.written >= Iec61937Packer.TRUEHD_IEC_SIZE)

        val expectedAnchor = bufferPts + 3L * 40L * C.MICROS_PER_SECOND / 44_100L
        val headUs = (track.written / 16).toLong() * C.MICROS_PER_SECOND / 176_400L
        assertEquals(expectedAnchor + headUs, sink.getCurrentPositionUs(false))
        assertTrue(
            "48 kHz family would have anchored 2500 us later, not $expectedAnchor",
            expectedAnchor != bufferPts + 3L * 40L * C.MICROS_PER_SECOND / 48_000L
        )
        val anchor = events.single { it.startsWith("iec_anchor ") }
        assertTrue(anchor, anchor.contains("discardedAu=3"))
        assertTrue(anchor, anchor.contains("anchorPts=$expectedAnchor"))
    }

    @Test
    fun trueHd_44k1_claimsHbrWhenOnly176400CanOpen() {
        val factory = ReadyFactory(
            FakeIecAudioTrack(176_400, 16, payload = HbrPayload.MAT),
            canOpenAt = { it == 176_400 }
        )
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        assertTrue(sink.claimsHbr(trueHdFormat(sampleRate = 44_100)))
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            sink.getFormatSupport(trueHdFormat(sampleRate = 44_100))
        )
        assertFalse(sink.claimsHbr(trueHdFormat()))
    }

    @Test
    fun trueHd_accessUnitSplitAcrossBuffers_stillPacks() {
        val track = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(track)
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()

        val first = TrueHdMatPackerTest.trueHdAu(frameTime = 0, major = true)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(first.copyOfRange(0, 12)), 0L, 1))
        assertTrue(sink.hasPendingData())

        val rest = ByteArray((40 - 12) + 40 * 47)
        System.arraycopy(first, 12, rest, 0, 28)
        var offset = 28
        for (i in 1 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = false)
            System.arraycopy(au, 0, rest, offset, 40)
            offset += 40
        }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(rest), 833L, 47))
        assertTrue(track.written >= Iec61937Packer.TRUEHD_IEC_SIZE)
    }

    @Test
    fun trueHd_endOfStream_dropsATrailingPartialAccessUnit() {
        val track = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(track)
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        val partial = byteArrayOf(0x00, 0x14, 0x00, 0x00, 0x00)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(partial), 0L, 1))
        assertTrue(sink.hasPendingData())
        sink.playToEndOfStream()
        assertFalse(sink.hasPendingData())
        assertTrue(sink.isEnded())
    }

    private fun trueHdFormat(sampleRate: Int = 48_000): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8)
            .setSampleRate(sampleRate)
            .build()
    }

    private fun stereoBitstreamFormat(mimeType: String): Format {
        return Format.Builder()
            .setSampleMimeType(mimeType)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
    }

    private fun dtsHdFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
    }

    private fun packedBurstBytes(): Int {
        val track = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(track))
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1)
        return track.written
    }

    @Test
    fun dtsHd_writeError_fallsBackToWrappedSink() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = -2))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)

        val payload = ByteArray(64) { 0x5A }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(payload), 1_000L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(1, inner.payloads.size)
        assertTrue(inner.payloads[0].contentEquals(payload))
        assertEquals(1_000L, inner.payloadPts[0])

        val next = ByteArray(64) { 0x11 }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(next), 2_000L, 1))
        assertEquals(2, inner.payloads.size)
        assertTrue(inner.payloads[1].contentEquals(next))
        assertEquals(2_000L, inner.payloadPts[1])
    }

    @Test
    fun dtsHd_successfulIecWrite_doesNotAlsoForwardTheAccessUnit() {
        val inner = RecordingSink()
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        )
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.isIecActive)
        assertTrue(inner.payloads.isEmpty())
    }

    @Test
    fun dtsHd_queuedAccessUnit_isForwardedWhenALaterWriteFails() {
        val inner = RecordingSink()
        val track = ThrottledIecAudioTrack(192_000, 16, capacityBytes = 0)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()

        val first = ByteArray(64) { 0x21 }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(first), 4_000L, 1))
        assertTrue(sink.isIecActive)
        assertTrue(inner.payloads.isEmpty())

        track.failWrites = true
        val second = ByteArray(64) { 0x22 }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(second), 8_000L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(2, inner.payloads.size)
        assertTrue(inner.payloads[0].contentEquals(first))
        assertEquals(4_000L, inner.payloadPts[0])
        assertTrue(inner.payloads[1].contentEquals(second))
        assertEquals(8_000L, inner.payloadPts[1])
    }

    @Test
    fun trueHd_majorSync_isForwardedWhenIecWriteFails() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = -2))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()

        val sent = mutableListOf<ByteArray>()
        for (i in 0 until 80) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            sent.add(au)
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), i * 833L, 1))
            if (!sink.isIecActive) break
        }
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(sent.size, inner.payloads.size)
        for (i in sent.indices) {
            assertTrue(inner.payloads[i].contentEquals(sent[i]))
            assertEquals(i * 833L, inner.payloadPts[i])
        }
        assertTrue(inner.payloads[0].contentEquals(sent[0]))
    }

    @Test
    fun writeError_whenWrappedSinkRefusesFormat_raisesRecoverableWriteException() {
        val inner = RecordingSink(
            innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED,
            configureThrows = true
        )
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = -6))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(dtsHdFormat()))
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        var thrown: Throwable? = null
        try {
            sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1)
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected a WriteException, got $thrown", thrown is AudioSink.WriteException)
        val write = thrown as AudioSink.WriteException
        assertTrue(write.isRecoverable)
        assertTrue(write.cause is AudioSink.ConfigurationException)
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dtsHdFormat()))
        assertFalse(sink.supportsFormat(dtsHdFormat()))
    }

    @Test
    fun dtsHd_stalledWrites_fallBackAfterStallBudget() {
        val clock = longArrayOf(0L)
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = 0))
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = factory,
            nanoTime = { clock[0] }
        )
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS - 1
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.isIecActive)
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
    }

    @Test
    fun dtsHd_stalledWritesWhilePaused_doNotCountTowardFallback() {
        val clock = longArrayOf(0L)
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = 0))
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = factory,
            nanoTime = { clock[0] }
        )
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS * 4
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.isIecActive)
        assertFalse(factory.markedUnusable)

        sink.play()
        clock[0] = 0L
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS - 1
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.isIecActive)
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
    }

    @Test
    fun dtsHd_formatSupport_promotedWhenIecReady() {
        val promoted = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = ReadyFactory(null)
        )
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, promoted.getFormatSupport(dtsHdFormat()))

        val notReady = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> null }
        )
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, notReady.getFormatSupport(dtsHdFormat()))
    }

    @Test
    fun dtsHd_beforeTheProbeLands_theWrappedSinkAnswers() {
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = ProbePendingFactory(FakeIecAudioTrack(192_000, 16))
        )
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dtsHdFormat()))
        assertFalse(sink.supportsFormat(dtsHdFormat()))
        assertFalse(sink.claimsHbr(dtsHdFormat()))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(trueHdFormat()))
        assertTrue(sink.claimsHbr(trueHdFormat()))
    }

    @Test
    fun dtsX_withoutACoreHeader_sizesBurstsFromPtsDeltas() {
        val track = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(track))
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 21_334L, 1))

        val firstBurst = Iec61937Packer.dtsHdIecPeriod(8, 512) shl 2
        val secondBurst = Iec61937Packer.dtsHdIecPeriod(8, 1024) shl 2
        assertEquals(firstBurst + secondBurst, track.written)
    }

    @Test
    fun dtsX_44k1_opens176400WhenTheProbeProvedIt() {
        val track = FakeIecAudioTrack(176_400, 16)
        val factory = ReadyFactory(track, readyAt = { it == 192_000 || it == 176_400 })
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
            .setChannelCount(8)
            .setSampleRate(44_100)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(176_400, factory.lastSampleRate)
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 11_610L, 1))
        val burst = Iec61937Packer.dtsHdIecPeriod(8, 512) shl 2
        assertEquals(2 * burst, track.written)
    }

    @Test
    fun dtsX_44k1_staysAt192000When176400WasNotProbed() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
            .setChannelCount(8)
            .setSampleRate(44_100)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(192_000, factory.lastSampleRate)
    }

    @Test
    fun dtsHd_withACoreSyncWord_keepsBurstsStableAcrossAPtsGap() {
        val track = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(track))
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()

        val au = ByteArray(64)
        au[0] = 0x7F
        au[1] = 0xFE.toByte()
        au[2] = 0x80.toByte()
        au[3] = 0x01
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), 0L, 1))
        val firstBurst = track.written
        assertTrue(firstBurst > 0)

        assertTrue(sink.handleBuffer(ByteBuffer.wrap(au.copyOf()), 500_000L, 1))
        assertEquals(2 * firstBurst, track.written)
    }

    @Test
    fun partialWrites_positionCountsEveryByte() {
        val track = FakeIecAudioTrack(192_000, 16, maxWriteChunk = 7)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(track))
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        val burstBytes = packedBurstBytes()
        assertEquals(burstBytes, track.written)

        val expectedUs = (burstBytes / 16) * 1_000_000L / 192_000L
        assertEquals(expectedUs, sink.getCurrentPositionUs(false))
    }

    @Test
    fun resetAndRelease_clearTheProbeListener_andConfigureRestoresIt() {
        var captured: (() -> Unit)? = null
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = FakeIecAudioTrack(192_000, 16)

            override fun setReadyListener(listener: (() -> Unit)?) {
                captured = listener
            }
        }
        val sink = IecPassthroughAudioSink(RecordingSink(), factory)
        assertNotNull(captured)

        sink.reset()
        assertNull(captured)

        sink.configure(trueHdFormat(), 0, null)
        assertNotNull(captured)

        sink.release()
        assertNull(captured)
    }

    @Test
    fun configure_deliversIecReadyOnce_ifProbeFinishedWhileListenerWasCleared() {
        var probeReady = false
        var deliveries = 0
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = FakeIecAudioTrack(192_000, 16)

            override fun iec61937Ready(): Boolean = probeReady
        }
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = factory,
            onIecBecameReady = { deliveries++ }
        )
        sink.getFormatSupport(dtsHdFormat())
        sink.reset()
        probeReady = true

        sink.configure(dtsHdFormat(), 0, null)
        assertEquals(1, deliveries)

        sink.configure(dtsHdFormat(), 0, null)
        assertEquals(1, deliveries)
    }

    @Test
    fun configure_doesNotDeliverIecReady_whenNoSelectionWasMadeBeforeTheProbe() {
        var deliveries = 0
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = FakeIecAudioTrack(192_000, 16)

            override fun iec61937Ready(): Boolean = true
        }
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = factory,
            onIecBecameReady = { deliveries++ }
        )
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(dtsHdFormat()))

        sink.configure(stereoBitstreamFormat(MimeTypes.AUDIO_DTS), 0, null)
        sink.configure(stereoBitstreamFormat(MimeTypes.AUDIO_AC3), 0, null)
        sink.configure(stereoBitstreamFormat(MimeTypes.AUDIO_E_AC3), 0, null)
        sink.configure(dtsHdFormat(), 0, null)
        assertEquals(0, deliveries)
    }

    @Test
    fun probeReady_doesNotNotify_whenOnlyCoreFormatsWereQueried() {
        var captured: (() -> Unit)? = null
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = null

            override fun setReadyListener(listener: (() -> Unit)?) {
                captured = listener
            }
        }
        var notified = false
        val sink = IecPassthroughAudioSink(RecordingSink(), factory) { notified = true }
        sink.getFormatSupport(stereoBitstreamFormat(MimeTypes.AUDIO_DTS))
        sink.supportsFormat(stereoBitstreamFormat(MimeTypes.AUDIO_AC3))
        sink.configure(stereoBitstreamFormat(MimeTypes.AUDIO_DTS), 0, null)
        captured!!.invoke()
        assertFalse(notified)
    }

    @Test
    fun discontinuity_reanchorsPlaybackHead() {
        val fakeTrack = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(fakeTrack))
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        repeat(10) { i ->
            assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), i * 10_000L, 1))
        }
        assertTrue(sink.getCurrentPositionUs(false) > 0L)

        sink.handleDiscontinuity()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET.toLong(), sink.getCurrentPositionUs(false))

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 1_000_000L, 1))
        val afterJump = sink.getCurrentPositionUs(false)
        assertTrue(
            "position should stay near the new start PTS, was $afterJump",
            afterJump < 1_050_000L
        )
    }

    @Test
    fun discontinuity_dropsPendingBursts() {
        val track = ThrottledIecAudioTrack(192_000, 16, capacityBytes = 32)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(track))
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(track.isFull())
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 10_000L, 1))
        assertTrue(sink.hasPendingData())

        sink.handleDiscontinuity()
        track.drain(track.written)
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 1_000_000L, 1))
        val afterJump = sink.getCurrentPositionUs(false)
        assertTrue(
            "position should stay near the new start PTS, was $afterJump",
            afterJump < 1_050_000L
        )
    }

    @Test
    fun open_reportsTrackBufferSizeNotRequestedSize() {
        val track = FakeIecAudioTrack(192_000, 16, bufferSizeBytes = 32_768)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertTrue(factory.lastBufferSizeBytes > 32_768)
        val expectedUs = 32_768L / 16 * C.MICROS_PER_SECOND / 192_000L
        assertEquals(expectedUs, sink.getAudioTrackBufferSizeUs())
    }

    @Test
    fun dtsHd_unknownChannelCount_opensEightChannelTrack() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setSampleRate(48_000)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(8, factory.lastChannelCount)
    }

    @Test
    fun tunneling_skipsIecAndForwardsToWrappedSink() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.setAudioSessionId(42)
        sink.enableTunnelingV21()
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertEquals(0, factory.openCount)
        assertTrue(inner.tunnelingEnabled)
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertEquals(1, inner.buffers)
    }

    @Test
    fun noTunneling_opensIecTrackWithSessionId() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.setAudioSessionId(42)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(42, factory.lastSessionId)
        assertEquals(1, factory.openCount)
    }

    @Test
    fun tunnelingDisabled_thenConfigure_opensIecTrack() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.enableTunnelingV21()
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        sink.disableTunneling()
        assertFalse(inner.tunnelingEnabled)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(1, factory.openCount)
    }

    @Test
    fun claimsHbr_onlyForHbrFormatsTheIecPathCanCarry() {
        val ready = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        )
        assertTrue(ready.claimsHbr(dtsHdFormat()))
        assertTrue(ready.claimsHbr(trueHdFormat()))
        assertFalse(
            ready.claimsHbr(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setChannelCount(2)
                    .setSampleRate(48_000)
                    .build()
            )
        )
        val optical = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = ReadyFactory(FakeIecAudioTrack(192_000, 16)),
            hbrIecEnabled = false
        )
        assertFalse(optical.claimsHbr(dtsHdFormat()))
        val unavailable = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> null }
        )
        assertFalse(unavailable.claimsHbr(dtsHdFormat()))
    }

    @Test
    fun tunneling_enableForwardedToWrappedSink() {
        val inner = RecordingSink()
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        )
        sink.enableTunnelingV21()
        assertTrue(inner.tunnelingEnabled)
        sink.disableTunneling()
        assertFalse(inner.tunnelingEnabled)
    }

    @Test
    fun opticalRoute_disablesHbrIec() {
        val inner = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED)
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = factory,
            hbrIecEnabled = false
        )
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dtsHdFormat()))
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertEquals(0, factory.lastChannelCount)
    }

    @Test
    fun probeReadyListener_invokesCallback() {
        var captured: (() -> Unit)? = null
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = null

            override fun setReadyListener(listener: (() -> Unit)?) {
                captured = listener
            }
        }
        var notified = false
        val sink = IecPassthroughAudioSink(RecordingSink(), factory) { notified = true }
        sink.supportsFormat(dtsHdFormat())
        captured!!.invoke()
        assertTrue(notified)
    }

    @Test
    fun endOfStream_drainsQueuedBurstsDuringIsEndedPolling() {
        val burstBytes = packedBurstBytes()
        val track = ThrottledIecAudioTrack(192_000, 16, capacityBytes = burstBytes + burstBytes / 4)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(track.isFull())
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))

        sink.playToEndOfStream()
        assertFalse(sink.isEnded())
        repeat(10) { assertFalse(sink.isEnded()) }
        assertFalse(factory.markedUnusable)

        var polls = 0
        while (!sink.isEnded() && polls < 100) {
            track.drain(burstBytes / 8)
            polls++
        }
        assertTrue("EOS drain via isEnded polling took too long", sink.isEnded())
        assertEquals(2 * burstBytes, track.written)
        assertEquals(track.written, track.consumed)
    }

    @Test
    fun endOfStream_writeErrorDuringDrain_handsEndOfStreamToWrappedSink() {
        val inner = RecordingSink()
        val track = ThrottledIecAudioTrack(192_000, 16, capacityBytes = packedBurstBytes() / 2)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))

        track.failWrites = true
        sink.playToEndOfStream()

        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertTrue("wrapped sink must own the end of stream", inner.endOfStreamRequested)
        assertTrue(sink.isEnded())
    }

    @Test
    fun endOfStream_writeErrorWhilePolling_fallsBackAndStillEnds() {
        val inner = RecordingSink()
        val track = ThrottledIecAudioTrack(192_000, 16, capacityBytes = packedBurstBytes() / 2)
        val factory = ReadyFactory(track)
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))

        sink.playToEndOfStream()
        assertFalse(sink.isEnded())
        assertTrue(sink.isIecActive)

        track.failWrites = true
        assertTrue(sink.isEnded())
        assertFalse(sink.isIecActive)
        assertTrue(inner.endOfStreamRequested)
    }

    private class ReadyFactory(
        private val track: IecAudioTrack?,
        private val readyAt: (Int) -> Boolean = { it == 192_000 },
        private val refuseRate: Int? = null,
        private val canOpenAt: (Int) -> Boolean = { true }
    ) : IecAudioTrackFactory {
        var markedUnusable = false
        var probeStarted = false
        var probeStartCount = 0
        var lastChannelCount: Int = 0
        var lastSessionId: Int = 0
        var lastBufferSizeBytes: Int = 0
        var lastSampleRate: Int = 0
        var openCount: Int = 0
        val openedRates = mutableListOf<Int>()

        override fun open(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int
        ): IecAudioTrack? {
            lastChannelCount = channelCount
            return track
        }

        override fun openHbr(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int,
            trueHd: Boolean
        ): IecAudioTrack? {
            lastChannelCount = channelCount
            lastSessionId = sessionId
            lastBufferSizeBytes = bufferSizeBytes
            lastSampleRate = sampleRate
            openCount++
            openedRates.add(sampleRate)
            if (refuseRate != null && sampleRate == refuseRate) return null
            return track
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = canOpenAt(sampleRate)
        override fun iec61937Ready(): Boolean = readyAt(192_000)
        override fun iec61937ReadyAt(sampleRate: Int): Boolean = readyAt(sampleRate)
        override fun markIecUnusable() {
            markedUnusable = true
        }

        override fun startProbe() {
            probeStarted = true
            probeStartCount++
        }
    }

    private class ProbePendingFactory(private val track: IecAudioTrack?) : IecAudioTrackFactory {
        override fun open(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int
        ): IecAudioTrack? = track

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = false
    }

    private class LatencyIecAudioTrack(
        private val latencyUs: Long
    ) : FakeIecAudioTrack(192_000, 16) {
        override fun outputLatencyUs(): Long = latencyUs
    }

    private open class FakeIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int,
        override val payload: HbrPayload = HbrPayload.IEC_BURST,
        override val bufferSizeBytes: Int = sampleRate * frameSizeBytes,
        private val fixedWriteResult: Int? = null,
        private val maxWriteChunk: Int? = null
    ) : IecAudioTrack {
        var written: Int = 0
            private set
        var underruns: Int = 0

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (fixedWriteResult != null) return fixedWriteResult
            val accepted = if (maxWriteChunk != null) minOf(size, maxWriteChunk) else size
            written += accepted
            return accepted
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (written / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = underruns
    }

    private class ThrottledIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int,
        private val capacityBytes: Int,
        var failWrites: Boolean = false
    ) : IecAudioTrack {
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = capacityBytes
        var written: Int = 0
            private set
        var consumed: Int = 0
            private set

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (failWrites) return -1
            val room = capacityBytes - (written - consumed)
            if (room <= 0) return 0
            val accepted = minOf(size, room)
            written += accepted
            return accepted
        }

        fun drain(bytes: Int) {
            consumed = minOf(consumed + bytes, written)
        }

        fun isFull(): Boolean = written - consumed >= capacityBytes

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (consumed / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class RecordingSink(
        private val innerSupport: Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
        private val configureThrows: Boolean = false
    ) : AudioSink {
        var buffers: Int = 0
            private set
        val payloads = mutableListOf<ByteArray>()
        val payloadPts = mutableListOf<Long>()
        var configured: Int = 0
            private set
        var lastConfigured: Format? = null
            private set
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = innerSupport != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int = innerSupport
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            configured++
            lastConfigured = inputFormat
            if (configureThrows) throw AudioSink.ConfigurationException("refused", inputFormat)
        }
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            payloads.add(bytes)
            payloadPts.add(presentationTimeUs)
            buffers++
            return true
        }
        var endOfStreamRequested = false
            private set
        override fun playToEndOfStream() {
            endOfStreamRequested = true
        }

        override fun isEnded(): Boolean = endOfStreamRequested
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        var tunnelingEnabled = false
            private set
        override fun enableTunnelingV21() {
            tunnelingEnabled = true
        }
        override fun disableTunneling() {
            tunnelingEnabled = false
        }
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
