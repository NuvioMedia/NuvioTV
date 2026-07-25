package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * End-to-end simulation of automatic subtitle synchronization against a real movie pair:
 *
 *  - `real-eng-reference.srt` is an English track extracted from the video container. It plays the
 *    role of the embedded reference that [SubtitleReferenceCueStore] collects during playback or
 *    that [SubtitleReferenceScanner] harvests from the Matroska cue index.
 *  - `real-heb-target.srt` is a Hebrew track downloaded from an addon. It plays the role of the
 *    subtitle the user selected and wants synchronized.
 *
 * The two files happen to already agree, which makes them a good double-edged fixture: they verify
 * that synchronization recovers injected desyncs *and* that it never damages a track that was
 * already correct.
 *
 * This mirrors the alignment half of `PlayerRuntimeController.automaticallySyncSubtitle()`
 * (align -> pick best reference track -> rewrite). The file/URI half needs an Android Context and
 * is out of scope here.
 */
class SubtitleSyncRealWorldTest {

    private val reference by lazy { fixture("real-eng-reference.srt") }
    private val target by lazy { fixture("real-heb-target.srt") }

    // ---------------------------------------------------------------- baseline

    @Test
    fun `fixtures parse into a full length movie pair`() {
        assertEquals(1014, reference.cues.size)
        assertEquals(989, target.cues.size)
        assertTrue(reference.cues.last().startMs > 80 * 60_000L)
        assertTrue(target.cues.last().startMs > 80 * 60_000L)
    }

    @Test
    fun `the real pair is already aligned`() {
        val quality = quality(reference.cues, target.cues)
        assertTrue(
            "expected the source pair to already agree, got $quality",
            quality.matchedRatio > 0.9 && quality.medianAbsResidualMs < 400L
        )
    }

    /**
     * The most important safety property of the feature: running sync on a track that is already
     * correct must not make it worse. A false positive here silently destroys working subtitles.
     */
    @Test
    fun `synchronizing an already aligned track does not degrade overall quality`() {
        val before = quality(reference.cues, target.cues)
        val model = SubtitleTimingAligner.align(reference.cues, target.cues)

        if (model == null) return // Declining to touch a correct track is an acceptable outcome.

        val after = quality(reference.cues, model.rewrite(target).cues)
        assertTrue(
            "sync degraded an already correct track: before=$before after=$after",
            after.matchedRatio >= before.matchedRatio - 0.02 &&
                after.medianAbsResidualMs <= before.medianAbsResidualMs + 150L
        )
    }

    /**
     * Regression guard for a fixed defect.
     *
     * On this already-synchronized pair the aligner used to emit three segments:
     *
     *     [00:00 .. 63:03] offset =     +28 ms   (correct)
     *     [63:03 .. 63:44] offset = +27 227 ms   (garbage)
     *     [63:44 .. end  ] offset =     -10 ms   (correct)
     *
     * A single 40 second window locks onto a wrong offset candidate and, because
     * `MIN_SEGMENT_WINDOWS = 1` (SubtitleTimingAligner.kt:46), a lone window is enough to become
     * its own segment. The result shifts ~40 seconds of dialogue by 27 seconds in the middle of a
     * previously perfect track.
     *
     * A transient offset spike is also physically impossible: an inserted ad break or recap shifts
     * content and it stays shifted. An offset that jumps up and immediately back cannot describe
     * any real edit.
     */
    @Test
    fun `synchronizing an already aligned track does not invent spurious offset segments`() {
        val model = SubtitleTimingAligner.align(reference.cues, target.cues) ?: return

        model.segments.forEach { segment ->
            assertTrue(
                "sync invented a ${segment.offsetMs}ms offset over " +
                    "${(segment.targetEndMs - segment.targetStartMs) / 1000}s of an aligned track: $segment",
                abs(segment.offsetMs) <= 750L
            )
        }
    }

    // ------------------------------------------------- constant offset recovery

    @Test
    fun `recovers a constant offset from a full embedded reference`() {
        listOf(-45_000L, -12_500L, -2_400L, 3_700L, 15_000L, 60_000L).forEach { offsetMs ->
            val desynced = target.shiftedBy(-offsetMs)
            val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, desynced.cues)) {
                "no model produced for offset ${offsetMs}ms"
            }
            assertRecovers(offsetMs, model, desynced, label = "offset=${offsetMs}ms")
        }
    }

    // ------------------------------------------- degraded reference simulations

    /**
     * [SubtitleReferenceScanner.indexedCues] only recovers start timestamps from the Matroska cue
     * index -- no text, no real durations. This is the common ExoPlayer + MKV path.
     */
    @Test
    fun `recovers a constant offset from a matroska cue index reference`() {
        val indexReference = reference.asMatroskaIndex()
        val desynced = target.shiftedBy(-8_800L)

        val model = requireNotNull(SubtitleTimingAligner.align(indexReference, desynced.cues))
        assertRecovers(8_800L, model, desynced, label = "matroska index")
    }

    /**
     * A PGS track indexes a display packet at each cue start and a clear packet at each cue end;
     * the scanner keeps every other entry to drop the clear packets. Overlapping cues make that
     * heuristic imperfect, so this asserts the aligner tolerates a partly wrong reference.
     */
    @Test
    fun `recovers a constant offset from a pgs style index reference`() {
        val indexReference = reference.asPgsIndex()
        val desynced = target.shiftedBy(-6_250L)

        val model = requireNotNull(SubtitleTimingAligner.align(indexReference, desynced.cues))
        assertRecovers(6_250L, model, desynced, label = "pgs index", toleranceMs = 750L)
    }

    /**
     * Regression guard for a fixed defect.
     *
     * Passive capture only holds cues up to the current playback position, so a user triggering
     * sync 20 minutes in has a reference covering only the first 20 minutes. This is the only
     * reference source available when the stream is not a range-request-capable Matroska file, so
     * it is not a rare path.
     *
     * The alignment math always handled it -- with the target clipped to the same span it recovered
     * +9 142 ms against a true +9 100 ms. What rejected it was the coverage gate, which requires the matched region to reach within
     * COVERAGE_PADDING_MS of the *last* target cue. A 20 minute reference can never satisfy that
     * against an 88 minute target.
     *
     * `strongPartialConstantScore` is the escape hatch, but it never fired on real
     * data: it scored a 128 point `evenlySample` of the reference against the dense target using an
     * F1 coverage metric, so thinning the reference is punished by `reverseRatio`, and the sparse
     * index bypass (`reference.size * 3 < localTarget.size`) did not trigger either. Alignment
     * returned NULL at 15, 20, 30, 45 and even 60 minutes of this 88 minute film. Raising the
     * scoring cap so real content is never thinned fixed it.
     */
    @Test
    fun `recovers a constant offset from partial playback capture`() {
        val capturedReference = reference.capturedUpTo(20 * 60_000L)
        assertTrue("partial capture too small to be meaningful", capturedReference.size > 100)
        val desynced = target.shiftedBy(-9_100L)

        val model = requireNotNull(SubtitleTimingAligner.align(capturedReference, desynced.cues)) {
            "partial capture of ${capturedReference.size} cues produced no alignment"
        }
        assertRecovers(9_100L, model, desynced, label = "partial capture", toleranceMs = 750L)
    }

    /**
     * Companion to the partial capture test above: with the target clipped to the span the
     * reference actually covers, the same partial reference aligns correctly. This isolates the
     * failure to the coverage gate rather than the matching math, and guards the fix.
     */
    @Test
    fun `partial capture alignment math is sound when the target covers the same span`() {
        val spanMs = 20 * 60_000L
        val capturedReference = reference.capturedUpTo(spanMs)
        val desynced = target.shiftedBy(-9_100L)
        val clippedTarget = SrtDocument(desynced.cues.filter { it.startMs <= spanMs })

        val model = requireNotNull(SubtitleTimingAligner.align(capturedReference, clippedTarget.cues))

        val dominant = model.segments.maxByOrNull { it.targetEndMs - it.targetStartMs }!!
        assertTrue(
            "expected ~9100ms, got ${dominant.offsetMs}ms",
            abs(dominant.offsetMs - 9_100L) <= 500L
        )
    }

    // ------------------------------------------------------ segmented desync

    /**
     * An ad break or an inserted recap shifts everything after a point in the video, so a single
     * offset cannot describe the whole track.
     */
    @Test
    fun `recovers segmented offsets across an inserted ad break`() {
        val breakAtMs = 40 * 60_000L
        val desynced = SrtDocument(
            target.cues.map { cue ->
                val shift = if (cue.startMs < breakAtMs) 5_000L else 5_000L + 90_000L
                cue.copy(startMs = cue.startMs - shift, endMs = cue.endMs - shift)
            }
        )

        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, desynced.cues))

        assertTrue("expected multiple segments, got ${model.segments}", model.segments.size >= 2)
        assertTrue(model.segments.any { abs(it.offsetMs - 5_000L) <= 1_000L })
        assertTrue(model.segments.any { abs(it.offsetMs - 95_000L) <= 1_000L })
        assertSynchronized(model.rewrite(desynced), label = "ad break")
    }

    /**
     * A physically realisable inserted ad break, and a guard on where the boundary lands.
     *
     * Content inserted into the video shifts everything after it in *reference* time, leaving a
     * dialogue gap there while the subtitle track itself stays monotone. That is the case a viewer
     * actually hits, and it must come out essentially perfect.
     *
     * It is also the case that is sensitive to how the segment boundary is chosen. The changeover
     * sits inside a long silence with no cues to discriminate on, so picking the split by a plain
     * argmax over matched cue counts lands it several cues early -- measured at 7 misplaced lines.
     * Scoring match quality on a graded scale and resolving ties towards the widest pause is what
     * puts it back to zero, so this test exists to stop that being simplified away.
     */
    @Test
    fun `places the boundary correctly for an inserted ad break`() {
        val insertAtMs = 40 * 60_000L
        val insertLengthMs = 90_000L
        // The video gains 90s of adverts at 40 minutes, so embedded cues after that point move late.
        val referenceWithBreak = reference.cues.map { cue ->
            if (cue.startMs < insertAtMs) cue
            else cue.copy(
                startMs = cue.startMs + insertLengthMs,
                endMs = cue.endMs + insertLengthMs
            )
        }
        val desynced = target.shiftedBy(-5_000L)

        val model = requireNotNull(SubtitleTimingAligner.align(referenceWithBreak, desynced.cues))
        assertTrue("expected two segments, got ${model.segments}", model.segments.size >= 2)

        val rewritten = model.rewrite(desynced)
        val truthByText = target.cues
            .groupBy(SrtCue::text)
            .filterValues { it.size == 1 }
            .mapValues { (_, cues) ->
                val original = cues.single().startMs
                if (original < insertAtMs) original else original + insertLengthMs
            }
        val misplaced = rewritten.cues.count { cue ->
            val truth = truthByText[cue.text] ?: return@count false
            abs(cue.startMs - truth) > 1_000L
        }

        assertTrue(
            "$misplaced cues landed on the wrong side of the ad break",
            misplaced <= 2
        )
    }

    /**
     * Two independent edits, scored the way it matters: by how many lines end up in the wrong place.
     *
     * Deliberately not asserted on boundary position. An edit point usually falls inside a pause in
     * the dialogue -- one of these lands in a 174 second silence -- and anywhere within that pause
     * is equally correct, because no cue is affected either way. Asserting proximity to the nominal
     * edit would fail the algorithm for being right.
     */
    @Test
    fun `keeps cues in place across two independent edits`() {
        val firstEditMs = 25 * 60_000L
        val secondEditMs = 55 * 60_000L
        fun shiftFor(startMs: Long): Long = when {
            startMs < firstEditMs -> 0L
            startMs < secondEditMs -> 40_000L
            else -> 100_000L
        }
        val referenceWithEdits = reference.cues.map { cue ->
            val shift = shiftFor(cue.startMs)
            cue.copy(startMs = cue.startMs + shift, endMs = cue.endMs + shift)
        }

        val model = requireNotNull(SubtitleTimingAligner.align(referenceWithEdits, target.cues))
        assertEquals("expected three segments, got ${model.segments}", 3, model.segments.size)

        val truthByText = target.cues
            .groupBy(SrtCue::text)
            .filterValues { it.size == 1 }
            .mapValues { (_, cues) -> cues.single().startMs + shiftFor(cues.single().startMs) }
        val misplaced = model.rewrite(target).cues.count { cue ->
            val truth = truthByText[cue.text] ?: return@count false
            abs(cue.startMs - truth) > 1_000L
        }

        assertTrue("$misplaced cues landed in the wrong region", misplaced <= 2)
    }

    // ------------------------------------------------------------- rejection

    @Test
    fun `rejects an unrelated reference track`() {
        assertNull(
            SubtitleTimingAligner.align(fixture("unrelated-embedded.srt").cues, target.cues)
        )
    }

    @Test
    fun `rejects an offset beyond the supported range`() {
        assertNull(
            SubtitleTimingAligner.align(reference.cues, target.shiftedBy(25 * 60_000L).cues)
        )
    }

    // ------------------------------------------------------- rewrite integrity

    @Test
    fun `rewrite preserves every cue and its text`() {
        val desynced = target.shiftedBy(-11_000L)
        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, desynced.cues))

        val rewritten = model.rewrite(desynced)

        assertEquals(desynced.cues.size, rewritten.cues.size)
        // Compared as a multiset: segmented sync applies different offsets either side of a
        // boundary, so cues legitimately change relative order there. Nothing may be lost though.
        assertEquals(
            desynced.cues.groupingBy(SrtCue::text).eachCount(),
            rewritten.cues.groupingBy(SrtCue::text).eachCount()
        )
        assertEquals(rewritten.cues.sortedBy(SrtCue::startMs), rewritten.cues)
        rewritten.cues.forEach { cue ->
            assertTrue("non positive duration in $cue", cue.endMs > cue.startMs)
            assertTrue("negative start in $cue", cue.startMs >= 0L)
        }
    }

    @Test
    fun `rewrite output round trips through the srt codec`() {
        val desynced = target.shiftedBy(-7_000L)
        val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, desynced.cues))
        val rewritten = model.rewrite(desynced)

        val reparsed = SrtDocument.parse(rewritten.encode())

        assertEquals(rewritten.cues.size, reparsed.cues.size)
        assertEquals(rewritten.cues.map(SrtCue::startMs), reparsed.cues.map(SrtCue::startMs))
        assertTrue(
            "hebrew text did not survive the round trip",
            reparsed.cues.any { it.text.any { char -> char in '\u0590'..'\u05FF' } }
        )
    }

    // ------------------------------------------------------------ performance

    /**
     * Alignment runs while video is decoding, so it has a wall clock budget. This is a smoke guard
     * against complexity regressions on a realistic 1000-cue movie, not a benchmark.
     */
    @Test
    fun `aligns a full movie within a time budget`() {
        val desynced = target.shiftedBy(-9_000L)
        SubtitleTimingAligner.align(reference.cues, desynced.cues) // warm up

        val elapsedMs = (1..3).minOf {
            val startNs = System.nanoTime()
            SubtitleTimingAligner.align(reference.cues, desynced.cues)
            (System.nanoTime() - startNs) / 1_000_000L
        }

        println("[subtitle-sync] full movie alignment best of 3: ${elapsedMs}ms")
        assertTrue("alignment took ${elapsedMs}ms for a 1000 cue movie", elapsedMs < 4_000L)
    }

    // ----------------------------------------------------------------- helpers

    private fun assertRecovers(
        expectedOffsetMs: Long,
        model: SubtitleSyncModel,
        desynced: SrtDocument,
        label: String,
        toleranceMs: Long = 500L
    ) {
        val dominant = model.segments.maxByOrNull { it.targetEndMs - it.targetStartMs }
        assertNotNull("$label produced no segments", dominant)
        assertTrue(
            "$label expected ~${expectedOffsetMs}ms, got ${dominant!!.offsetMs}ms (segments=${model.segments})",
            abs(dominant.offsetMs - expectedOffsetMs) <= toleranceMs
        )
        assertSynchronized(model.rewrite(desynced), label)
    }

    /** Asserts the rewritten track actually lands on the reference timeline. */
    private fun assertSynchronized(rewritten: SrtDocument, label: String, minimumRatio: Double = 0.85) {
        val quality = quality(reference.cues, rewritten.cues)
        assertTrue(
            "$label left the track unsynchronized: $quality",
            quality.matchedRatio >= minimumRatio && quality.medianAbsResidualMs <= 500L
        )
    }

    private data class SyncQuality(val matchedRatio: Double, val medianAbsResidualMs: Long) {
        override fun toString(): String =
            "matched=${(matchedRatio * 100).roundToLong()}% medianResidual=${medianAbsResidualMs}ms"
    }

    /**
     * Measures how well [candidate] sits on [truth]'s timeline by nearest onset distance. This is a
     * better assertion than comparing a single segment offset, because a real translated track
     * splits dialogue differently and never matches one to one.
     */
    private fun quality(
        truth: List<SrtCue>,
        candidate: List<SrtCue>,
        toleranceMs: Long = 1_500L
    ): SyncQuality {
        val truthStarts = truth.map(SrtCue::startMs).distinct().sorted()
        val candidateStarts = candidate.map(SrtCue::startMs).distinct().sorted()
        if (truthStarts.isEmpty() || candidateStarts.isEmpty()) return SyncQuality(0.0, Long.MAX_VALUE)

        val residuals = candidateStarts.map { nearestDistance(truthStarts, it) }
        val matched = residuals.filter { it <= toleranceMs }.sorted()
        return SyncQuality(
            matchedRatio = matched.size.toDouble() / residuals.size,
            medianAbsResidualMs = matched.getOrNull(matched.size / 2) ?: Long.MAX_VALUE
        )
    }

    private fun nearestDistance(sortedStarts: List<Long>, valueMs: Long): Long {
        val found = sortedStarts.binarySearch { it.compareTo(valueMs) }
        if (found >= 0) return 0L
        val insertion = -found - 1
        val before = sortedStarts.getOrNull(insertion - 1)?.let { abs(valueMs - it) } ?: Long.MAX_VALUE
        val after = sortedStarts.getOrNull(insertion)?.let { abs(it - valueMs) } ?: Long.MAX_VALUE
        return minOf(before, after)
    }

    private fun SrtDocument.shiftedBy(deltaMs: Long): SrtDocument = SrtDocument(
        cues.map { it.copy(startMs = it.startMs + deltaMs, endMs = it.endMs + deltaMs) }
    )

    /** Mirrors [SubtitleReferenceScanner]: the cue index yields start timestamps only. */
    private fun SrtDocument.asMatroskaIndex(): List<SrtCue> = cues
        .map { SrtCue(it.startMs, it.startMs + 1_000L, " ") }
        .distinctBy(SrtCue::startMs)

    /** Mirrors the PGS branch: display and clear packets interleaved, every other one kept. */
    private fun SrtDocument.asPgsIndex(): List<SrtCue> = cues
        .flatMap { listOf(it.startMs, it.endMs) }
        .sorted()
        .filterIndexed { index, _ -> index % 2 == 0 }
        .map { SrtCue(it, it + 1_000L, " ") }

    /** Mirrors passive playback capture, which only sees cues already played. */
    private fun SrtDocument.capturedUpTo(positionMs: Long): List<SrtCue> =
        cues.filter { it.startMs <= positionMs }

    private fun fixture(name: String): SrtDocument {
        val resource = requireNotNull(javaClass.getResource("/subtitle-sync/$name")) {
            "Missing subtitle fixture: $name"
        }
        return SrtDocument.parse(resource.readText())
    }
}
