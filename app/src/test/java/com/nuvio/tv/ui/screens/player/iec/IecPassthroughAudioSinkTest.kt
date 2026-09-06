package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class IecPassthroughAudioSinkTest {

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

        // A mid-stream chunk, as a sample-queue seek delivers it: three units before the major
        // sync, all in one buffer whose PTS is that of its first unit. The packer discards the
        // three, so the clock must start at the fourth unit's time, 3 x 40/48000 s later.
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

        // Seek landed two units before a major sync.
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

        // A length word of 2 (4 bytes) cannot be an access unit; the rest of the sample is junk.
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

        // Nothing changed and the interval has not elapsed: no new line.
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 10_000L, 1))
        assertEquals(1, lines.count { it.startsWith("iec_health ") })

        // An underrun is reported at once.
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

        val optical = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = optical, hbrIecEnabled = false)
        assertFalse(optical.probeStarted)
    }

    private fun trueHdFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8)
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

    // The burst the sink actually packs for one unit of this shape, so capacity math in the
    // end-of-stream tests does not depend on how the period is derived.
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

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(0, inner.buffers)

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 100L, 1))
        assertEquals(1, inner.buffers)
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
        // A recovery must not re-select IEC: the format is now answered by the wrapped sink.
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dtsHdFormat()))
        assertFalse(sink.supportsFormat(dtsHdFormat()))
    }

    @Test
    fun dtsHd_stalledWrites_fallBackAfterStallLimit() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = 0))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        repeat(IecPassthroughAudioSink.MAX_WRITE_STALLS - 2) {
            assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        }
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
    }

    @Test
    fun dtsHd_stalledWritesWhilePaused_doNotCountTowardFallback() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = 0))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)

        // Paused: the full buffer never drains, and none of these attempts may count.
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        repeat(IecPassthroughAudioSink.MAX_WRITE_STALLS * 2) {
            assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        }
        assertTrue(sink.isIecActive)
        assertFalse(factory.markedUnusable)

        // Playing: the same stalls count, and the limit still trips.
        sink.play()
        repeat(IecPassthroughAudioSink.MAX_WRITE_STALLS - 1) {
            assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        }
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
        // MAT answering canOpen says nothing about DTS-HD, which needs IEC bursts.
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

        // Nothing to parse: the first burst falls back to one default frame, the next follows
        // the PTS delta of 1024 samples at 48 kHz.
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(256), 21_334L, 1))

        val firstBurst = Iec61937Packer.dtsHdIecPeriod(8, 512) shl 2
        val secondBurst = Iec61937Packer.dtsHdIecPeriod(8, 1024) shl 2
        assertEquals(firstBurst + secondBurst, track.written)
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

        // With a core header to parse, a half-second PTS gap must not resize the burst.
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(au.copyOf()), 500_000L, 1))
        assertEquals(2 * firstBurst, track.written)
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
        IecPassthroughAudioSink(RecordingSink(), factory) { notified = true }
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

        // Identical PTS on purpose: sizing bursts from PTS deltas is covered elsewhere.
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertTrue(track.isFull())
        // Backpressure: the next buffer is refused until the track drains.
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))

        sink.playToEndOfStream()
        assertFalse(sink.isEnded())
        // A full track during the end-of-stream poll must not give up on IEC.
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

        // The track dies while the renderer is polling for the end of the stream.
        track.failWrites = true
        assertTrue(sink.isEnded())
        assertFalse(sink.isIecActive)
        assertTrue(inner.endOfStreamRequested)
    }

    private class ReadyFactory(private val track: IecAudioTrack?) : IecAudioTrackFactory {
        var markedUnusable = false
        var probeStarted = false
        var lastChannelCount: Int = 0
        var lastSessionId: Int = 0
        var openCount: Int = 0

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
            openCount++
            return track
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = true
        override fun markIecUnusable() {
            markedUnusable = true
        }

        override fun startProbe() {
            probeStarted = true
        }
    }

    // MAT answers canOpen, but the IEC61937 probe has not landed yet.
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

    private class FakeIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int,
        override val payload: HbrPayload = HbrPayload.IEC_BURST,
        private val fixedWriteResult: Int? = null
    ) : IecAudioTrack {
        var written: Int = 0
            private set
        var underruns: Int = 0

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (fixedWriteResult != null) return fixedWriteResult
            written += size
            return size
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (written / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = underruns
    }

    // A track with a real hardware buffer: writes stop when full, the head advances on drain.
    private class ThrottledIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int,
        private val capacityBytes: Int,
        var failWrites: Boolean = false
    ) : IecAudioTrack {
        override val payload: HbrPayload = HbrPayload.IEC_BURST
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
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = innerSupport != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int = innerSupport
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            if (configureThrows) throw AudioSink.ConfigurationException("refused", inputFormat)
        }
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            buffers++
            buffer.position(buffer.limit())
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
