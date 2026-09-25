package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SubtitleAutoSyncEngineTest {
    @Test
    fun `audio evidence is ready at thirty seconds across two windows`() {
        val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(
            SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(1_000L, 31_000L)),
                pcmAvailable = true
            )
        )

        assertEquals(30_000L, evidence.observedMs)
        assertEquals(2, evidence.windowCount)
        assertTrue(evidence.ready)
    }

    @Test
    fun `audio evidence excludes a trailing span shorter than ten seconds`() {
        val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(
            SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(0L, 29_999L)),
                pcmAvailable = true
            )
        )

        assertEquals(20_000L, evidence.observedMs)
        assertEquals(1, evidence.windowCount)
        assertFalse(evidence.ready)
    }

    @Test
    fun `audio evidence ignores disconnected chunks too short to form windows`() {
        val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(
            SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(
                    SubtitleSyncSpan(0L, 9_000L),
                    SubtitleSyncSpan(20_000L, 29_000L),
                    SubtitleSyncSpan(40_000L, 49_000L),
                    SubtitleSyncSpan(60_000L, 69_000L)
                ),
                pcmAvailable = true
            )
        )

        assertEquals(0L, evidence.observedMs)
        assertEquals(0, evidence.windowCount)
        assertFalse(evidence.ready)
    }

    @Test
    fun `audio evidence is not ready when pcm is unavailable`() {
        val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(
            SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(0L, 60_000L)),
                pcmAvailable = false
            )
        )

        assertEquals(60_000L, evidence.observedMs)
        assertEquals(3, evidence.windowCount)
        assertFalse(evidence.ready)
    }

    @Test
    fun `audio evidence keeps every independent window instead of resampling eight`() {
        val spans = (0 until 6).map { index ->
            val start = index * 120_000L
            SubtitleSyncSpan(start, start + 60_000L)
        }
        val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(
            SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = spans,
                pcmAvailable = true
            )
        )

        assertEquals(360_000L, evidence.observedMs)
        assertEquals(18, evidence.windowCount)
        assertTrue(evidence.ready)
    }

    @Test
    fun `finds a positive constant offset`() {
        val cues = irregularCues()
        val expectedOffsetMs = 2_400
        val snapshot = speechSnapshot(cues, expectedOffsetMs)

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = snapshot,
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
        assertTrue(result.confidence >= SubtitleAutoSyncEngine.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `default search finds a fifty second offset in the local fine stage`() {
        val cues = irregularCues(baseMs = 80_000L)
        val expectedOffsetMs = 50_000

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, expectedOffsetMs)
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
        assertTrue(result.confidence >= SubtitleAutoSyncEngine.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `default fine stage covers offsets up to four minutes`() {
        val cues = irregularCues(baseMs = 300_000L)
        val expectedOffsetMs = SubtitleAutoSyncEngine.LOCAL_FINE_SEARCH_RADIUS_MS - 500

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, expectedOffsetMs)
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
    }

    @Test
    fun `finds a negative constant offset`() {
        val cues = irregularCues(baseMs = 8_000L)
        val expectedOffsetMs = -1_700
        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, expectedOffsetMs),
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
    }

    @Test
    fun `rejects periodic ambiguous evidence`() {
        val cues = (0 until 18).map { index ->
            val start = 2_000L + index * 4_000L
            SubtitleSyncCue(start, start + 1_200L, "Line $index")
        }
        val snapshot = speechSnapshot(cues, offsetMs = 2_000)

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = snapshot,
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertFalse(result.shouldApply)
        assertEquals(SubtitleAutoSyncRejection.LOW_CONFIDENCE, result.rejection)
    }

    @Test
    fun `rejects a recut with incompatible offsets`() {
        val cues = irregularCues()
        val speech = cues.map { cue ->
            val offsetMs = if (cue.startTimeMs < 38_000L) 2_400L else -3_000L
            SubtitleSyncSpan(
                startMs = cue.startTimeMs + offsetMs,
                endMs = cue.endTimeMs + offsetMs
            )
        }
        val snapshot = SubtitleSpeechSnapshot(
            speechSpans = speech,
            observedSpans = listOf(SubtitleSyncSpan(0L, 80_000L)),
            pcmAvailable = true
        )

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = snapshot,
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertFalse(result.shouldApply)
        assertEquals(SubtitleAutoSyncRejection.LOW_CONFIDENCE, result.rejection)
    }

    @Test
    fun `window agreement ignores exact matches in unrelated scenes`() {
        val expectedOffsetMs = 2_400
        val windowStarts = listOf(100_000L, 400_000L, 700_000L)
        val relativeSpeech = listOf(
            SubtitleSyncSpan(1_100L, 2_050L),
            SubtitleSyncSpan(4_300L, 5_700L),
            SubtitleSyncSpan(8_200L, 9_100L),
            SubtitleSyncSpan(12_600L, 14_250L),
            SubtitleSyncSpan(17_100L, 18_300L)
        )
        val speech = windowStarts.flatMap { windowStart ->
            relativeSpeech.map { span ->
                SubtitleSyncSpan(windowStart + span.startMs, windowStart + span.endMs)
            }
        }
        val realCues = speech.mapIndexed { index, span ->
            SubtitleSyncCue(
                startTimeMs = span.startMs - expectedOffsetMs + if (index % 2 == 0) 100L else -100L,
                endTimeMs = span.endMs - expectedOffsetMs,
                text = "Real dialogue $index"
            )
        }
        val decoyOffsets = listOf(-200_000, 250_000, -100_000)
        val decoyCues = windowStarts.flatMapIndexed { windowIndex, windowStart ->
            relativeSpeech.mapIndexed { spanIndex, span ->
                val absoluteSpeechStart = windowStart + span.startMs
                val absoluteSpeechEnd = windowStart + span.endMs
                SubtitleSyncCue(
                    startTimeMs = absoluteSpeechStart - decoyOffsets[windowIndex],
                    endTimeMs = absoluteSpeechEnd - decoyOffsets[windowIndex],
                    text = "Unrelated scene $windowIndex line $spanIndex"
                )
            }
        }
        val snapshot = SubtitleSpeechSnapshot(
            speechSpans = speech,
            observedSpans = windowStarts.map { SubtitleSyncSpan(it, it + 20_000L) },
            pcmAvailable = true
        )

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = realCues + decoyCues,
            snapshot = snapshot,
            minimumOffsetMs = -300_000,
            maximumOffsetMs = 300_000
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
        assertTrue(result.windowAgreement >= 0.60)
        assertEquals(3, result.evidenceWindows)
    }

    @Test
    fun `keeps subtitle unchanged without enough observed audio`() {
        val cues = irregularCues()
        val snapshot = SubtitleSpeechSnapshot(
            speechSpans = cues.map { SubtitleSyncSpan(it.startTimeMs, it.endTimeMs) },
            observedSpans = listOf(SubtitleSyncSpan(0L, 12_000L)),
            pcmAvailable = true
        )

        val result = SubtitleAutoSyncEngine.findBestOffset(cues, snapshot)

        assertFalse(result.shouldApply)
        assertEquals(SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO, result.rejection)
    }

    @Test
    fun `accepts dialogue containing ASS override tags without regex failure`() {
        val cues = irregularCues().mapIndexed { index, cue ->
            cue.copy(text = "{\\an8}<i>Dialogue line number $index</i>")
        }

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, offsetMs = 2_400),
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertTrue(result.shouldApply)
    }

    @Test
    fun `searches the complete subtitle timeline beyond the old three minute limit`() {
        val cues = irregularCues(baseMs = 500_000L)
        val expectedOffsetMs = -420_000

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, expectedOffsetMs)
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
    }

    @Test
    fun `coarse search refines a large non aligned offset over a full timeline`() {
        val cues = fullTimelineCues(baseMs = 500_000L)
        val expectedOffsetMs = -420_350

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, expectedOffsetMs)
        )

        assertTrue(result.shouldApply)
        assertTrue(abs(result.offsetMs - expectedOffsetMs) <= 200)
        assertTrue(result.confidence >= SubtitleAutoSyncEngine.CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `counts dialogue cues before merging adjacent activity`() {
        val cues = (0 until 12).map { index ->
            val start = 2_000L + index * 1_000L
            SubtitleSyncCue(start, start + 1_000L, "Adjacent dialogue $index")
        }
        val speech = cues.map { cue ->
            SubtitleSyncSpan(cue.startTimeMs + 2_400L, cue.endTimeMs + 2_400L)
        }
        val snapshot = SubtitleSpeechSnapshot(
            speechSpans = speech,
            observedSpans = listOf(SubtitleSyncSpan(0L, 40_000L)),
            pcmAvailable = true
        )

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = snapshot,
            minimumOffsetMs = -10_000,
            maximumOffsetMs = 10_000
        )

        assertTrue(result.rejection != SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE)
    }

    @Test
    fun `wide search does not turn periodic no overlap candidates into confidence`() {
        val cues = (0 until 180).map { index ->
            val start = 500_000L + index * 4_000L
            SubtitleSyncCue(start, start + 1_200L, "Periodic long timeline $index")
        }

        val result = SubtitleAutoSyncEngine.findBestOffset(
            cues = cues,
            snapshot = speechSnapshot(cues, offsetMs = -420_350)
        )

        assertFalse(result.shouldApply)
        assertEquals(SubtitleAutoSyncRejection.LOW_CONFIDENCE, result.rejection)
    }

    @Test
    fun `timeline entirely outside allowed delay does not collapse to six hour boundary`() {
        val cues = irregularCues()
        val farTimelineStartMs = SUBTITLE_DELAY_MAX_MS.toLong() +
            cues.maxOf { it.endTimeMs } + 10_000L
        val observed = SubtitleSyncSpan(
            startMs = farTimelineStartMs,
            endMs = farTimelineStartMs + 60_000L
        )
        val snapshot = SubtitleSpeechSnapshot(
            speechSpans = listOf(
                SubtitleSyncSpan(observed.startMs + 2_000L, observed.startMs + 8_000L),
                SubtitleSyncSpan(observed.startMs + 24_000L, observed.startMs + 30_000L)
            ),
            observedSpans = listOf(observed),
            pcmAvailable = true
        )

        val result = SubtitleAutoSyncEngine.findBestOffset(cues, snapshot)

        assertFalse(result.shouldApply)
        assertEquals(SubtitleAutoSyncRejection.LOW_CONFIDENCE, result.rejection)
        assertEquals(0, result.offsetMs)
        assertEquals(0.0, result.windowAgreement, 0.0)
    }

    private fun irregularCues(baseMs: Long = 1_500L): List<SubtitleSyncCue> {
        val starts = listOf(
            0L, 3_200L, 7_700L, 11_300L, 16_900L, 21_100L, 27_800L,
            32_200L, 38_900L, 43_000L, 49_800L, 54_600L, 61_400L, 68_100L
        )
        return starts.mapIndexed { index, start ->
            val duration = 850L + (index % 4) * 230L
            SubtitleSyncCue(
                startTimeMs = baseMs + start,
                endTimeMs = baseMs + start + duration,
                text = "Dialogue line number $index"
            )
        }
    }

    private fun fullTimelineCues(baseMs: Long): List<SubtitleSyncCue> {
        var start = baseMs
        return (0 until 150).map { index ->
            start += 2_300L + ((index * 1_791L) % 4_700L)
            val duration = 700L + ((index * 379L) % 1_400L)
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + duration,
                text = "Long timeline dialogue number $index"
            )
        }
    }

    private fun speechSnapshot(
        cues: List<SubtitleSyncCue>,
        offsetMs: Int
    ): SubtitleSpeechSnapshot {
        val speech = cues.mapIndexed { index, cue ->
            SubtitleSyncSpan(
                startMs = cue.startTimeMs + offsetMs - 80L,
                endMs = cue.endTimeMs + offsetMs + 120L + (index % 2) * 60L
            )
        }
        val observedStartMs = (speech.minOf { it.startMs } - 1_000L).coerceAtLeast(0L)
        val observedEndMs = maxOf(
            speech.maxOf { it.endMs } + 1_000L,
            observedStartMs + 80_000L
        )
        return SubtitleSpeechSnapshot(
            speechSpans = speech,
            observedSpans = listOf(SubtitleSyncSpan(observedStartMs, observedEndMs)),
            pcmAvailable = true
        )
    }
}
