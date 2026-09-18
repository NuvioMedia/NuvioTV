package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.DtsUtil
import com.nuvio.tv.ui.screens.player.iec.DtsHdFrameDurationEstimator.Companion.DEFAULT_DTS_SAMPLE_COUNT
import com.nuvio.tv.ui.screens.player.iec.DtsHdFrameDurationEstimator.Companion.dtsSampleCountFromPtsDeltaUs
import com.nuvio.tv.ui.screens.player.iec.DtsHdFrameDurationEstimator.Companion.snapToUhdGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * DTS:X / DTS-UHD IEC burst sizing: PTS rounding, UHD grid snap, Matroska 1 ms
 * jitter, and IEC 61937-5 type-IV subtypes.
 *
 * Frame durations: ETSI TS 103 491 V1.2.1 Table 6-13, as parsed by media3 1.8.0
 * [DtsUtil.parseDtsUhdHeader].
 */
class DtsXIecBurstPeriodTest {

    @Test
    fun ptsDelta_roundsHalfUp_so21333UsIs1024() {
        assertEquals(1024, dtsSampleCountFromPtsDeltaUs(21_333L))
        assertEquals(1024, dtsSampleCountFromPtsDeltaUs(21_334L))
        assertEquals(512, dtsSampleCountFromPtsDeltaUs(10_666L))
        assertEquals(512, dtsSampleCountFromPtsDeltaUs(10_667L))
        assertEquals(480, dtsSampleCountFromPtsDeltaUs(10_000L))
    }

    @Test
    fun snapToUhdGrid_keepsLegal480AndDoesNotPowerOfTwoSnap() {
        assertEquals(480, snapToUhdGrid(480.0))
        assertEquals(512, snapToUhdGrid(528.0))
        assertEquals(512, snapToUhdGrid(511.0))
        assertEquals(1024, snapToUhdGrid(1008.0))
        assertEquals(1024, snapToUhdGrid(1056.0))
        assertEquals(2048, snapToUhdGrid(2064.0))
        assertEquals(384, snapToUhdGrid(384.0))
        // 44.1 kHz 512-sample frame as 48 kHz-equivalent (557) must not collapse.
        assertEquals(557, snapToUhdGrid(557.0))
        // 32 kHz 512-sample frame is exactly 768 at 48 kHz, a legal UHD count.
        assertEquals(768, snapToUhdGrid(768.0))
    }

    @Test
    fun estimator_matroska1ms_512SampleStreamLocksTo512() {
        val estimator = DtsHdFrameDurationEstimator()
        // First unit has no delta.
        assertEquals(512, estimator.resolveFromPts(0L))
        // 10 ms then 11 ms, the Matroska rounding of 10.667 ms. A single 10 ms
        // is within 10% of 512, so it must not emit a 480 burst.
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(512, estimator.resolveFromPts(21_000L))
        assertEquals(512, estimator.resolveFromPts(31_000L))
        assertEquals(512, estimator.resolveFromPts(42_000L))
    }

    @Test
    fun estimator_steady480SampleStreamStaysOn480() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(480, estimator.resolveFromPts(20_000L))
        assertEquals(480, estimator.resolveFromPts(30_000L))
    }

    @Test
    fun estimator_matroska1ms_1024SampleStreamLocksTo1024() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(1024, estimator.resolveFromPts(21_000L))
        assertEquals(1024, estimator.resolveFromPts(43_000L))
        assertEquals(1024, estimator.resolveFromPts(64_000L))
    }

    @Test
    fun estimator_seekKeepsResolvedCount() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.resolveFromPts(21_334L)
        assertEquals(1024, estimator.resolvedSampleCount)
        estimator.clearPts()
        assertEquals(1024, estimator.resolveFromPts(5_000_000L))
        assertEquals(1024, estimator.resolveFromPts(5_021_000L))
    }

    @Test
    fun estimator_uhdParseFailure_reusesLastSyncDuration() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(0, estimator.sampleCountFromUhdCache())
        // 1024 samples at 48 kHz is 21333.33 us.
        estimator.rememberUhdDurationUs(21_333L)
        assertEquals(1024, estimator.sampleCountFromUhdCache())
        assertEquals(1024, estimator.resolvedSampleCount)

        estimator.clearPts()
        assertEquals(1024, estimator.sampleCountFromUhdCache())

        // A later CRC / non-sync failure must not fall back to a 10 ms PTS guess.
        assertEquals(1024, estimator.resolveFromPts(0L))
        assertEquals(1024, estimator.resolveFromPts(10_000L))
        assertEquals(1024, estimator.sampleCountFromUhdCache())

        estimator.reset()
        assertEquals(0, estimator.sampleCountFromUhdCache())
        assertEquals(512, estimator.resolveFromPts(0L))
    }

    @Test
    fun estimator_uhdNonSyncUnsetDuration_keepsPreviousSync() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.rememberUhdDurationUs(10_000L)
        assertEquals(480, estimator.sampleCountFromUhdCache())
        estimator.rememberUhdDurationUs(C.TIME_UNSET)
        assertEquals(480, estimator.sampleCountFromUhdCache())
    }

    @Test
    fun ptsDelta_outOfRange_isNotAFake512Frame() {
        // 2 ms is 96 samples; 500 ms is 24000. Neither is a DTS-UHD frame.
        assertEquals(0, dtsSampleCountFromPtsDeltaUs(2_000L))
        assertEquals(0, dtsSampleCountFromPtsDeltaUs(500_000L))
        assertEquals(DEFAULT_DTS_SAMPLE_COUNT, dtsSampleCountFromPtsDeltaUs(0L))
        assertEquals(DEFAULT_DTS_SAMPLE_COUNT, dtsSampleCountFromPtsDeltaUs(-1L))
        assertEquals(128, dtsSampleCountFromPtsDeltaUs(2_667L))
        assertEquals(8_192, dtsSampleCountFromPtsDeltaUs(170_667L))
    }

    @Test
    fun snapToUhdGrid_everyLegalUhdCountIsAFixedPoint() {
        for (samples in DtsHdFrameDurationEstimator.UHD_FRAME_SAMPLE_COUNTS) {
            assertEquals(samples, snapToUhdGrid(samples.toDouble()))
        }
        // Core DTS 256 is not on the UHD grid; leave it alone (error vs 384 is 33%).
        assertEquals(256, snapToUhdGrid(256.0))
        assertEquals(DEFAULT_DTS_SAMPLE_COUNT, snapToUhdGrid(0.0))
        assertEquals(DEFAULT_DTS_SAMPLE_COUNT, snapToUhdGrid(-8.0))
    }

    @Test
    fun estimator_duplicateOrBackwardPts_keepsTheLastBurst() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(512, estimator.resolveFromPts(9_000L))
        assertEquals(480, estimator.resolveFromPts(20_000L))
    }

    @Test
    fun estimator_unsetPts_doesNotConsumeTheNextDelta() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(C.TIME_UNSET))
        assertEquals(C.TIME_UNSET, estimator.lastPtsUs)
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(512, estimator.resolveFromPts(C.TIME_UNSET))
        assertEquals(10_000L, estimator.lastPtsUs)
        assertEquals(480, estimator.resolveFromPts(20_000L))
    }

    @Test
    fun estimator_seekWithoutDiscontinuity_hugeOrTinyGapKeepsLock() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.resolveFromPts(10_000L)
        estimator.resolveFromPts(20_000L)
        assertEquals(480, estimator.resolvedSampleCount)
        assertEquals(480, estimator.resolveFromPts(520_000L))
        assertEquals(480, estimator.resolveFromPts(522_000L))
        assertEquals(480, estimator.resolveFromPts(532_000L))
    }

    @Test
    fun estimator_384SampleStream_locksFromExact8000Us() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(384, estimator.resolveFromPts(8_000L))
        assertEquals(384, estimator.resolveFromPts(16_000L))
        assertEquals(384, estimator.resolveFromPts(24_000L))
    }

    @Test
    fun estimator_relocksWhenTheStreamChangesFrameSize() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.resolveFromPts(10_000L)
        estimator.resolveFromPts(20_000L)
        assertEquals(480, estimator.resolvedSampleCount)
        estimator.clearPts()
        assertEquals(480, estimator.resolveFromPts(0L))
        assertEquals(1024, estimator.resolveFromPts(21_334L))
        assertEquals(1024, estimator.resolveFromPts(42_668L))
    }

    @Test
    fun estimator_notePtsUnset_doesNotClearLastPts() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.notePts(C.TIME_UNSET)
        assertEquals(0L, estimator.lastPtsUs)
        assertEquals(512, estimator.resolveFromPts(10_000L))
    }

    @Test
    fun estimator_notePtsOfTheSameTimestamp_isNotAZeroDeltaFrame() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.resolveFromPts(10_000L)
        estimator.notePts(10_000L)
        assertEquals(512, estimator.resolveFromPts(10_000L))
        assertEquals(480, estimator.resolveFromPts(20_000L))
    }

    @Test
    fun estimator_observeKnownCount_overridesPtsLockImmediately() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.resolveFromPts(0L)
        estimator.resolveFromPts(10_000L)
        estimator.resolveFromPts(20_000L)
        assertEquals(480, estimator.resolvedSampleCount)
        estimator.observeKnownCount(1_024)
        assertEquals(1_024, estimator.resolvedSampleCount)
        assertEquals(1_024, estimator.resolveFromPts(20_000L))
        estimator.clearPts()
        assertEquals(1_024, estimator.resolveFromPts(0L))
    }

    @Test
    fun estimator_coreSampleCount256_isKeptOffTheUhdGrid() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.observeKnownCount(256)
        assertEquals(256, estimator.resolvedSampleCount)
        estimator.clearPts()
        assertEquals(256, estimator.resolveFromPts(0L))
        // 256 samples at 48 kHz is 5.333 ms; do not snap that header onto 384.
        assertEquals(256, estimator.resolveFromPts(5_333L))
    }

    @Test
    fun estimator_rememberUhdDurationUs_ignoresZeroAndNegative() {
        val estimator = DtsHdFrameDurationEstimator()
        estimator.rememberUhdDurationUs(10_000L)
        assertEquals(480, estimator.sampleCountFromUhdCache())
        estimator.rememberUhdDurationUs(0L)
        estimator.rememberUhdDurationUs(-8L)
        assertEquals(480, estimator.sampleCountFromUhdCache())
        assertEquals(480, estimator.resolvedSampleCount)
    }

    @Test
    fun ptsDelta_justOutsideTheLegalRange_isZero() {
        // 127 samples at 48 kHz; 8193 samples at 48 kHz.
        assertEquals(0, dtsSampleCountFromPtsDeltaUs(2_645L))
        assertEquals(0, dtsSampleCountFromPtsDeltaUs(170_688L))
    }

    @Test
    fun estimator_44k1_512SampleStream_keepsThe48kEquivalent() {
        assertEquals(557, dtsSampleCountFromPtsDeltaUs(11_610L))
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        // 8.8% is inside relock, so the first delta does not steal the default.
        assertEquals(512, estimator.resolveFromPts(11_610L))
        assertEquals(557, estimator.resolveFromPts(23_220L))
        assertEquals(557, estimator.resolveFromPts(34_830L))
        estimator.rememberUhdDurationUs(11_610L)
        assertEquals(557, estimator.sampleCountFromUhdCache())
        assertEquals(557, estimator.resolvedSampleCount)
    }

    @Test
    fun estimator_44k1Clock_512SampleStream_isNative512() {
        assertEquals(512, dtsSampleCountFromPtsDeltaUs(11_610L, 44_100))
        val estimator = DtsHdFrameDurationEstimator()
        estimator.clockSampleRate = 44_100
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(512, estimator.resolveFromPts(11_610L))
        assertEquals(512, estimator.resolveFromPts(23_220L))
        estimator.rememberUhdDurationUs(11_610L)
        assertEquals(512, estimator.sampleCountFromUhdCache())
    }

    @Test
    fun estimator_uhdDurationWithMkvJitter_snapsTheCacheToTheGrid() {
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(528, dtsSampleCountFromPtsDeltaUs(11_000L))
        estimator.rememberUhdDurationUs(11_000L)
        assertEquals(512, estimator.sampleCountFromUhdCache())
        assertEquals(512, estimator.resolvedSampleCount)
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(512, estimator.resolveFromPts(11_000L))
    }

    @Test
    fun estimator_32kHz_512SampleStream_locksTo768() {
        assertEquals(768, dtsSampleCountFromPtsDeltaUs(16_000L))
        val estimator = DtsHdFrameDurationEstimator()
        assertEquals(512, estimator.resolveFromPts(0L))
        assertEquals(768, estimator.resolveFromPts(16_000L))
        assertEquals(768, estimator.resolveFromPts(32_000L))
    }

    @Test
    fun sink_corelessDtsX_snapsNamedPtsSequencesToTheUhdGrid() {
        val samples = listOf(
            longArrayOf(0L, 21_334L) to listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024),
            longArrayOf(0L, 21_333L) to listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024),
            longArrayOf(0L, 10_000L, 21_000L) to listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 512),
            longArrayOf(0L, 21_000L, 43_000L) to listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024, 1024),
            longArrayOf(0L, 10_000L, 20_000L, 30_000L) to
                listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 480, 480),
            longArrayOf(0L, 11_610L, 23_220L, 34_830L) to
                listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 557, 557),
            longArrayOf(0L, 16_000L, 32_000L) to
                listOf(DEFAULT_DTS_SAMPLE_COUNT, 768, 768),
        )
        for ((pts, expected) in samples) {
            assertEquals("pts=${pts.toList()}", expected, corelessBurstSampleCounts(pts))
        }
    }

    @Test
    fun sink_discontinuity_reusesTheResolvedBurstPeriod() {
        val counts = corelessBurstSampleCounts(
            longArrayOf(0L, 21_334L),
            extra = { sink ->
                sink.handleDiscontinuity()
            },
            afterExtraPts = longArrayOf(8_000_000L)
        )
        assertEquals(listOf(512, 1024, 1024), counts)
    }

    @Test
    fun sink_uhdFtocSyncPrefix_isNotParsedAsACoreHeader() {
        val uhdSync = byteArrayOf(0x40, 0x41, 0x1B, 0xF2.toByte(), 0, 0, 0, 0)
        val parsedAsCore = try {
            DtsUtil.parseDtsAudioSampleCount(uhdSync)
        } catch (_: Exception) {
            Int.MIN_VALUE
        }
        assertNotEquals(
            "UHD sync must not look like a 480-sample core frame",
            480,
            parsedAsCore
        )
        assertEquals(
            listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 480),
            corelessBurstSampleCounts(longArrayOf(0L, 10_000L, 20_000L), accessUnit = uhdSync)
        )
    }

    @Test
    fun sink_uhdLittleEndianAndNonSyncPrefixes_usePtsNotACoreParse() {
        val prefixes = listOf(
            byteArrayOf(0xF2.toByte(), 0x1B, 0x41, 0x40, 0, 0, 0, 0),
            byteArrayOf(0x71, 0xC4.toByte(), 0x42, 0xE8.toByte(), 0, 0, 0, 0),
            byteArrayOf(0xE8.toByte(), 0x42, 0xC4.toByte(), 0x71, 0, 0, 0, 0),
        )
        for (prefix in prefixes) {
            val au = prefix + ByteArray(56)
            assertEquals(
                prefix.contentToString(),
                listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024),
                corelessBurstSampleCounts(longArrayOf(0L, 21_334L), accessUnit = au)
            )
        }
    }

    @Test
    fun sink_emptyOrShortAccessUnit_stillEmitsADefaultThenPtsBurst() {
        assertEquals(
            listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 480),
            corelessBurstSampleCounts(longArrayOf(0L, 10_000L, 20_000L), accessUnit = ByteArray(0))
        )
        assertEquals(
            listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 480),
            corelessBurstSampleCounts(
                longArrayOf(0L, 10_000L, 20_000L),
                accessUnit = byteArrayOf(0x00, 0x01, 0x02)
            )
        )
    }

    @Test
    fun sink_flush_keepsTheResolvedBurstPeriod() {
        val counts = corelessBurstSampleCounts(
            longArrayOf(0L, 21_334L, 42_668L),
            extra = { sink -> sink.flush() },
            afterExtraPts = longArrayOf(3_000_000L, 3_021_334L)
        )
        assertEquals(listOf(512, 1024, 1024, 1024, 1024), counts)
    }

    @Test
    fun sink_reset_clearsTheResolvedBurstPeriod() {
        val track = CountingTrack()
        val sink = corelessSink(track)
        ingest(sink, track, longArrayOf(0L, 21_334L, 42_668L))
        sink.reset()
        sink.configure(dtsXFormat(), 0, null)
        sink.play()
        assertEquals(
            listOf(DEFAULT_DTS_SAMPLE_COUNT, 512, 480),
            ingest(sink, track, longArrayOf(0L, 10_000L, 20_000L), resetWritten = true)
        )
    }

    @Test
    fun sink_hugePtsGap_doesNotResizeTo512() {
        assertEquals(
            listOf(512, 512, 480, 480),
            corelessBurstSampleCounts(longArrayOf(0L, 10_000L, 20_000L, 520_000L))
        )
    }

    @Test
    fun sink_stereoDtsX_burstBytesFollowTheTwoChannelPeriod() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .build()
        val track = CountingTrack()
        val sink = corelessSink(track, format)
        val counts = ingest(sink, track, longArrayOf(0L, 21_334L), bytesPerSample = 16)
        assertEquals(listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024), counts)
        assertEquals(2048, Iec61937Packer.dtsHdIecPeriod(2, 512))
        assertEquals(2, packedSubtype(2048))
    }

    @Test
    fun sink_fourteenBitCoreSync_ignoresAPtsGap() {
        val au = ByteArray(64)
        au[0] = 0x1F
        au[1] = 0xFF.toByte()
        au[2] = 0xE8.toByte()
        au[3] = 0x00
        val first = corelessBurstSampleCounts(longArrayOf(0L), accessUnit = au, format = dtsHdFormat()).single()
        val both = corelessBurstSampleCounts(longArrayOf(0L, 500_000L), accessUnit = au, format = dtsHdFormat())
        assertTrue(first > 0)
        assertEquals(listOf(first, first), both)
    }

    @Test
    fun sink_littleEndianCoreSync_ignoresAPtsGap() {
        val prefixes = listOf(
            byteArrayOf(0xFE.toByte(), 0x7F, 0x01, 0x80.toByte()),
            byteArrayOf(0xFF.toByte(), 0x1F, 0x00, 0xE8.toByte()),
        )
        for (prefix in prefixes) {
            val au = prefix + ByteArray(60)
            val both = corelessBurstSampleCounts(
                longArrayOf(0L, 500_000L),
                accessUnit = au,
                format = dtsHdFormat()
            )
            assertTrue(prefix.contentToString(), both[0] > 0)
            assertEquals(prefix.contentToString(), listOf(both[0], both[0]), both)
        }
    }

    @Test
    fun sink_duplicateAndBackwardPts_keepTheLockedBurst() {
        assertEquals(
            listOf(512, 512, 512, 512, 480),
            corelessBurstSampleCounts(longArrayOf(0L, 10_000L, 10_000L, 9_000L, 20_000L))
        )
    }

    @Test
    fun sink_unsetPtsInTheMiddle_doesNotStealTheLock() {
        assertEquals(
            listOf(512, 512, 512, 480),
            corelessBurstSampleCounts(longArrayOf(0L, 10_000L, C.TIME_UNSET, 20_000L))
        )
    }

    @Test
    fun sink_sixChannelDtsX_usesTheSurroundPeriod() {
        val format = dtsXFormat(channelCount = 6)
        val track = CountingTrack()
        val sink = corelessSink(track, format)
        val counts = ingest(sink, track, longArrayOf(0L, 21_334L), bytesPerSample = 64)
        assertEquals(listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024), counts)
        assertEquals(8192, Iec61937Packer.dtsHdIecPeriod(6, 512))
        assertEquals(16384, Iec61937Packer.dtsHdIecPeriod(6, 1024))
    }

    @Test
    fun sink_vndDtsUhdMime_sizesBurstsFromPts() {
        val format = Format.Builder()
            .setSampleMimeType("audio/vnd.dts.uhd")
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
        assertEquals(
            listOf(DEFAULT_DTS_SAMPLE_COUNT, 1024),
            corelessBurstSampleCounts(longArrayOf(0L, 21_334L), format = format)
        )
    }

    @Test
    fun sink_44k1With176400Iec_native512IsALegalTypeIvPeriod() {
        val format = dtsXFormat(sampleRate = 44_100)
        val track = CountingTrack()
        val sink = corelessSink(track, format, iecRates = intArrayOf(192_000, 176_400))
        val counts = ingest(sink, track, longArrayOf(0L, 11_610L, 23_220L))
        assertEquals(listOf(512, 512, 512), counts)
        assertEquals(8192, Iec61937Packer.dtsHdIecPeriod(8, 512))
        assertEquals(4, packedSubtype(8192))
    }

    @Test
    fun sink_44k1Without176400Iec_keepsThe48kEquivalent() {
        assertEquals(
            listOf(512, 512, 557),
            corelessBurstSampleCounts(
                longArrayOf(0L, 11_610L, 23_220L),
                format = dtsXFormat(sampleRate = 44_100)
            )
        )
    }

    @Test
    fun sink_discontinuityThenMatchingDelta_keepsTheResolvedBurst() {
        val counts = corelessBurstSampleCounts(
            longArrayOf(0L, 21_334L, 42_668L),
            extra = { sink -> sink.handleDiscontinuity() },
            afterExtraPts = longArrayOf(8_000_000L, 8_021_334L)
        )
        assertEquals(listOf(512, 1024, 1024, 1024, 1024), counts)
    }

    @Test
    fun packerSubtype_includesTypeIvPeriods32768And65536() {
        assertEquals(0, packedSubtype(512))
        assertEquals(1, packedSubtype(1024))
        assertEquals(2, packedSubtype(2048))
        assertEquals(3, packedSubtype(4096))
        assertEquals(4, packedSubtype(8192))
        assertEquals(5, packedSubtype(16384))
        assertEquals(6, packedSubtype(32768))
        assertEquals(7, packedSubtype(65536))
        assertEquals(6, iec61937TypeIvSubtype(32768))
        assertEquals(7, iec61937TypeIvSubtype(65536))
        assertEquals(
            6,
            Iec61937Packer.dtsHdBurstSubtype(Iec61937Packer.dtsHdIecPeriod(8, 2048))
        )
        assertEquals(
            7,
            Iec61937Packer.dtsHdBurstSubtype(Iec61937Packer.dtsHdIecPeriod(8, 4096))
        )
        // 384 and 480 × 16 are legal UHD periods but have no type-IV code.
        assertEquals(4, Iec61937Packer.dtsHdBurstSubtype(Iec61937Packer.dtsHdIecPeriod(8, 384)))
        assertEquals(4, Iec61937Packer.dtsHdBurstSubtype(Iec61937Packer.dtsHdIecPeriod(8, 480)))
        assertEquals(null, iec61937TypeIvSubtype(6_144))
        assertEquals(null, iec61937TypeIvSubtype(7_680))
        assertEquals(2048, Iec61937Packer.dtsHdIecPeriod(1, 512))
        assertEquals(8192, Iec61937Packer.dtsHdIecPeriod(3, 512))
    }

    @Test
    fun measurementReport_snappedMkvDeltasHitTheGrid() {
        val header = "samples mkvMs raw snapped period packerSub iecSub"
        val rows = DtsHdFrameDurationEstimator.UHD_FRAME_SAMPLE_COUNTS.map { samples ->
            val exactUs = samples * 1_000_000.0 / 48_000.0
            val mkvMs = (exactUs / 1000.0).roundToLong()
            val raw = dtsSampleCountFromPtsDeltaUs(mkvMs * 1_000L)
            val snapped = snapToUhdGrid(raw.toDouble())
            val period = Iec61937Packer.dtsHdIecPeriod(8, snapped)
            val packerSub = Iec61937Packer.dtsHdBurstSubtype(period)
            val iecSub = iec61937TypeIvSubtype(period)
            listOf(samples, mkvMs, raw, snapped, period, packerSub, iecSub ?: -1).joinToString(" ")
        }
        println((listOf(header) + rows).joinToString("\n"))

        assertEquals(480, snapToUhdGrid(dtsSampleCountFromPtsDeltaUs(10_000L).toDouble()))
        assertEquals(512, snapToUhdGrid(dtsSampleCountFromPtsDeltaUs(11_000L).toDouble()))
        assertEquals(1024, snapToUhdGrid(dtsSampleCountFromPtsDeltaUs(21_000L).toDouble()))
        assertEquals(2048, snapToUhdGrid(dtsSampleCountFromPtsDeltaUs(43_000L).toDouble()))
    }

    private fun iec61937TypeIvSubtype(period: Int): Int? = when (period) {
        512 -> 0
        1024 -> 1
        2048 -> 2
        4096 -> 3
        8192 -> 4
        16384 -> 5
        32768 -> 6
        65536 -> 7
        else -> null
    }

    private fun packedSubtype(iecPeriod: Int): Int {
        val packed = Iec61937Packer.packDtsHd(ByteArray(4), iecPeriod)
        return (ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt() and 0xFFFF) shr 8
    }

    private fun dtsXFormat(channelCount: Int = 8, sampleRate: Int = 48_000): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
        .setChannelCount(channelCount)
        .setSampleRate(sampleRate)
        .build()

    private fun dtsHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private fun corelessSink(
        track: CountingTrack,
        format: Format = dtsXFormat(),
        iecRates: IntArray = intArrayOf(192_000)
    ): IecPassthroughAudioSink {
        val sink = IecPassthroughAudioSink(
            sink = UnusedSink(),
            trackFactory = object : IecAudioTrackFactory {
                override fun open(
                    sampleRate: Int,
                    channelCount: Int,
                    bufferSizeBytes: Int,
                    sessionId: Int
                ): IecAudioTrack = track
                override fun iec61937Ready(): Boolean = 192_000 in iecRates
                override fun iec61937ReadyAt(sampleRate: Int): Boolean = sampleRate in iecRates
            }
        )
        sink.configure(format, 0, null)
        sink.play()
        return sink
    }

    private fun ingest(
        sink: IecPassthroughAudioSink,
        track: CountingTrack,
        ptsUs: LongArray,
        accessUnit: ByteArray = ByteArray(64),
        bytesPerSample: Int = 64,
        resetWritten: Boolean = false
    ): List<Int> {
        if (resetWritten) track.resetWritten()
        val counts = ArrayList<Int>(ptsUs.size)
        var previousWritten = track.written
        for (pts in ptsUs) {
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(accessUnit.copyOf()), pts, 1))
            val burstBytes = track.written - previousWritten
            previousWritten = track.written
            counts += burstBytes / bytesPerSample
        }
        return counts
    }

    private fun corelessBurstSampleCounts(
        ptsUs: LongArray,
        accessUnit: ByteArray = ByteArray(64),
        extra: ((IecPassthroughAudioSink) -> Unit)? = null,
        afterExtraPts: LongArray = longArrayOf(),
        format: Format = dtsXFormat()
    ): List<Int> {
        val track = CountingTrack()
        val sink = corelessSink(track, format)
        val counts = ArrayList<Int>()
        counts += ingest(sink, track, ptsUs, accessUnit)
        extra?.invoke(sink)
        if (afterExtraPts.isNotEmpty()) {
            counts += ingest(sink, track, afterExtraPts, accessUnit)
        }
        return counts
    }

    private class CountingTrack : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        var written: Int = 0
            private set

        fun resetWritten() {
            written = 0
        }

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            written += size
            return size
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (written / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class UnusedSink : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
