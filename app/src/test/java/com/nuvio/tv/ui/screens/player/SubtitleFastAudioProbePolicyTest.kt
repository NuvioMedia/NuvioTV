package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleFastAudioProbePolicyTest {
    @Test
    fun `unknown bitrate starts conservatively at four times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = null,
            durationMs = 0L
        )

        assertEquals(4f, plan.playbackSpeed)
        assertEquals(25_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `hundred gigabyte long movie starts at two times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = 102_682_361_361L,
            durationMs = 10_144_224L
        )

        assertEquals(2f, plan.playbackSpeed)
        assertEquals(40_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `ordinary compressed stream can use eight times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = 4_000_000_000L,
            durationMs = 7_200_000L
        )

        assertEquals(8f, plan.playbackSpeed)
        assertEquals(20_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `short timed out probe backs off and extends active timeout`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(8f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = null,
            decodedStartMs = 0L,
            decodedEndMs = 15_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        val next = SubtitleFastAudioProbePolicy.afterProbe(initial, result)

        assertEquals(4f, next.playbackSpeed)
        assertEquals(25_000L, next.activeDecodeTimeoutMs)
    }

    @Test
    fun `useful timed out probe keeps its speed`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(2f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(0L, 47_000L)),
                pcmAvailable = true
            ),
            decodedStartMs = 0L,
            decodedEndMs = 47_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        assertEquals(initial, SubtitleFastAudioProbePolicy.afterProbe(initial, result))
    }

    @Test
    fun `disconnected pcm range uses observed union when deciding backoff`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(4f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(
                    SubtitleSyncSpan(0L, 5_000L),
                    SubtitleSyncSpan(45_000L, 50_000L)
                ),
                pcmAvailable = true
            ),
            decodedStartMs = 0L,
            decodedEndMs = 50_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        assertEquals(2f, SubtitleFastAudioProbePolicy.afterProbe(initial, result).playbackSpeed)
    }
}
