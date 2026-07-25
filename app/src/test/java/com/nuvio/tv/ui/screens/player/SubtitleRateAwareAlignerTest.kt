package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Frame-rate drift coverage for [SubtitleRateAwareAligner], using the same real movie pair as
 * [SubtitleSyncRealWorldTest]: an English track standing in for the embedded reference and an
 * already-agreeing Hebrew track standing in for the downloaded addon subtitle.
 *
 * Drift is synthesized by dividing the Hebrew timestamps by a ratio, so a track drifted by `r` is
 * correct again exactly when the aligner rescales it by `r`. That makes the expected answer known
 * rather than inferred.
 *
 * ### Why quality is scored by exact displacement
 *
 * [SubtitleSyncRealWorldTest] scores by distance to the nearest English reference cue, which is the
 * right metric there but useless here: it cannot see a cue that has been shifted onto a *different*
 * nearby cue, and rates a badly rescaled track with half its dialogue in the wrong place as
 * indistinguishable from a correct one. So these tests pair each rewritten cue with its own
 * original by text and measure how far it actually moved.
 */
class SubtitleRateAwareAlignerTest {

    private val reference by lazy { fixture("real-eng-reference.srt") }
    private val target by lazy { fixture("real-heb-target.srt") }

    private val palRatio = 23.976 / 25.0
    private val inversePalRatio = 25.0 / 23.976
    private val ntscPulldownRatio = 23.976 / 24.0
    private val inverseNtscPulldownRatio = 24.0 / 23.976

    /** Stretch factors that are not any real frame-rate conversion. */
    private val nonStandardDriftRatios =
        listOf(1.0025, 1.005, 1.01, 1.02, 0.9975, 0.995, 0.99, 0.98)

    // -------------------------------------------------------------- recovery

    @Test
    fun `recovers 25 to 23976 fps PAL drift`() {
        assertRecovers(palRatio, label = "25 -> 23.976 PAL")
    }

    @Test
    fun `recovers 23976 to 25 fps inverse PAL drift`() {
        assertRecovers(inversePalRatio, label = "23.976 -> 25 inverse PAL")
    }

    /**
     * The 0.1% NTSC pulldown ratio is the dangerous one: it is small enough that the offset-only
     * aligner does not refuse it, it silently accepts a fit that leaves tens of cues seconds out.
     */
    @Test
    fun `recovers 24 to 23976 fps pulldown drift that the offset only aligner mishandles`() {
        val drifted = target.driftedBy(ntscPulldownRatio)

        val before = SubtitleTimingAligner.align(reference.cues, drifted.cues)
        assertNotNull("expected the offset-only aligner to accept this drift, not refuse it", before)
        val beforeQuality = quality(before!!.rewrite(drifted))
        assertTrue(
            "expected the offset-only aligner to mishandle 0.1% drift, got $beforeQuality",
            beforeQuality.misplacedOverOneSecond > 25
        )

        assertRecovers(ntscPulldownRatio, label = "24 -> 23.976 pulldown")
    }

    @Test
    fun `recovers 23976 to 24 fps pulldown drift`() {
        assertRecovers(inverseNtscPulldownRatio, label = "23.976 -> 24 pulldown")
    }

    // ---------------------------------------------------------------- safety

    /**
     * The whole risk of frame-rate support. Drift that is not a real frame-rate conversion still has
     * a best-scoring ratio, but it is the wrong one, and applying it displaces most of the track by
     * seconds. No such drift may ever be rescaled.
     *
     * Note this asserts "never rescaled" rather than "always null". At -1% the plain aligner already
     * accepts the drift on its own, as a 21 segment staircase chasing the ramp; that predates
     * frame-rate support and is out of scope here. What matters is that the wrapper adds nothing to
     * it -- see [nonStandardDriftFallsBackToTheUnscaledAlignerUnchanged].
     */
    @Test
    fun `never rescales non standard drift`() {
        nonStandardDriftRatios.forEach { ratio ->
            val drifted = target.driftedBy(ratio)
            val plan = SubtitleRateAwareAligner.align(reference.cues, drifted.cues)
            if (plan != null) {
                assertEquals(
                    "rescaled non standard ${"%.4f".format(ratio)} drift by ${plan.rateRatio} " +
                        "(conf=${plan.confidence}) -> ${quality(plan.rewrite(drifted))}",
                    1.0,
                    plan.rateRatio,
                    0.0
                )
            }
        }
    }

    /** Every drift rate the brief calls out as unsupported is still refused outright. */
    @Test
    fun `refuses the non standard drift rates that were previously refused`() {
        listOf(1.0025, 1.005, 1.01, 1.02, 0.9975, 0.995, 0.98).forEach { ratio ->
            val drifted = target.driftedBy(ratio)
            val plan = SubtitleRateAwareAligner.align(reference.cues, drifted.cues)
            assertNull(
                "accepted non standard ${"%.4f".format(ratio)} drift as rateRatio=${plan?.rateRatio} " +
                    "conf=${plan?.confidence} -> ${plan?.let { quality(it.rewrite(drifted)) }}",
                plan
            )
        }
    }

    /**
     * Where a rescale is refused the caller must be left with exactly what it had before frame-rate
     * support existed -- neither a worse result nor a newly invented one.
     */
    @Test
    fun nonStandardDriftFallsBackToTheUnscaledAlignerUnchanged() {
        nonStandardDriftRatios.forEach { ratio ->
            val drifted = target.driftedBy(ratio)
            val plan = SubtitleRateAwareAligner.align(reference.cues, drifted.cues)
            val raw = SubtitleTimingAligner.align(reference.cues, drifted.cues)

            assertEquals("drift ${"%.4f".format(ratio)} diverged from the plain aligner", raw, plan?.model)
        }
    }

    /**
     * Confidence alone does not make a rescale safe. A short reference is fitted by chance: sixteen
     * cues from an unrelated title score 0.881 against this 989 cue target under a PAL ratio, which
     * is *higher* than a genuine full length recovery. Only the amount of evidence behind the fit
     * tells them apart, so a rescale that rests on a handful of cues must be refused.
     */
    @Test
    fun `refuses a rescale that rests on too little evidence`() {
        val unrelated = fixture("unrelated-embedded.srt").cues
        val temptingRatio = 25.0 / 24.0
        val tempting = requireNotNull(
            SubtitleTimingAligner.align(unrelated, target.cues.scaledBy(temptingRatio))
        ) { "fixture no longer produces the spurious fit this test guards against" }
        assertTrue(
            "spurious fit no longer clears the confidence gate, so this test proves nothing",
            tempting.confidence >= 0.80
        )
        assertTrue("spurious fit rested on ${tempting.matchedCueCount} cues", tempting.matchedCueCount < 50)

        assertNull(SubtitleRateAwareAligner.align(unrelated, target.cues))
    }

    /**
     * Guards the threshold from either side: every correct recovery must clear it comfortably and
     * every wrong candidate must fall short of it, rather than the two merely happening to land on
     * opposite sides of the line.
     */
    @Test
    fun `correct and incorrect rescales are separated by a wide confidence margin`() {
        val correct = listOf(palRatio, inversePalRatio, ntscPulldownRatio, inverseNtscPulldownRatio)
            .map { ratio ->
                requireNotNull(
                    SubtitleRateAwareAligner.align(reference.cues, target.driftedBy(ratio).cues)
                ).confidence
            }
        val incorrect = listOf(1.0025, 1.005, 1.01, 1.02).map { ratio ->
            bestRawConfidenceAcrossRatios(target.driftedBy(ratio))
        }

        println("[rate-sync] correct fits: ${correct.map { "%.4f".format(it) }}")
        println("[rate-sync] best incorrect fits: ${incorrect.map { "%.4f".format(it) }}")
        assertTrue("a correct fit scored only ${correct.min()}", correct.min() >= 0.84)
        assertTrue("an incorrect fit scored ${incorrect.max()}", incorrect.max() <= 0.76)
    }

    // --------------------------------------------------------------- control

    @Test
    fun `leaves an already correct track unscaled and undamaged`() {
        val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, target.cues))

        assertEquals("an already correct track was rescaled", 1.0, plan.rateRatio, 0.0)
        val quality = quality(plan.rewrite(target))
        assertTrue("rescaling damaged a correct track: $quality", quality.misplacedOverOneSecond == 0)
        assertTrue("rescaling damaged a correct track: $quality", quality.medianErrorMs <= 100L)
    }

    /**
     * The unscaled path must stay identical to what [SubtitleTimingAligner] produces on its own, so
     * that wrapping changes nothing for the common case.
     */
    @Test
    fun `unscaled plans delegate unchanged to the offset only aligner`() {
        listOf(0L, -11_000L, 3_700L, 60_000L).forEach { shift ->
            val shifted = target.shiftedBy(shift)
            val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, shifted.cues))
            val model = requireNotNull(SubtitleTimingAligner.align(reference.cues, shifted.cues))

            assertEquals(1.0, plan.rateRatio, 0.0)
            assertEquals(model, plan.model)
            assertEquals(model.rewrite(shifted).cues, plan.rewrite(shifted).cues)
        }
    }

    @Test
    fun `still refuses an unrelated reference track`() {
        assertNull(
            SubtitleRateAwareAligner.align(fixture("unrelated-embedded.srt").cues, target.cues)
        )
    }

    @Test
    fun `still refuses an offset beyond the supported range`() {
        assertNull(
            SubtitleRateAwareAligner.align(reference.cues, target.shiftedBy(25 * 60_000L).cues)
        )
    }

    // -------------------------------------------------------------- rewrite

    /**
     * The emitted subtitle must carry the rescale, not just the segment offsets. Applying only the
     * offsets would look plausible at the start of the film and be minutes out by the end, so this
     * checks the exact `round(start * ratio) + offset` composition per cue rather than sampling
     * quality.
     */
    @Test
    fun `rewrite applies the scale before the segment offset`() {
        val drifted = target.driftedBy(palRatio)
        val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, drifted.cues))
        assertTrue("expected a rescaled plan", plan.isRescaled)

        val rewritten = plan.rewrite(drifted).cues.associateBy(SrtCue::text)
        val uniqueTexts = drifted.cues.uniqueTexts()

        drifted.cues.filter { it.text in uniqueTexts }.forEach { cue ->
            val scaledStartMs = (cue.startMs * plan.rateRatio).roundToLong()
            val segment = plan.model.segments.firstOrNull {
                scaledStartMs >= it.targetStartMs && scaledStartMs < it.targetEndMs
            } ?: return@forEach
            assertEquals(
                "cue at ${cue.startMs}ms was not scaled then offset",
                scaledStartMs + segment.offsetMs,
                requireNotNull(rewritten[cue.text]).startMs
            )
        }
    }

    @Test
    fun `rewrite of a rescaled plan preserves every cue and its text`() {
        val drifted = target.driftedBy(palRatio)
        val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, drifted.cues))

        val rewritten = plan.rewrite(drifted)

        assertEquals(drifted.cues.size, rewritten.cues.size)
        assertEquals(
            drifted.cues.groupingBy(SrtCue::text).eachCount(),
            rewritten.cues.groupingBy(SrtCue::text).eachCount()
        )
        assertEquals(rewritten.cues.sortedBy(SrtCue::startMs), rewritten.cues)
        rewritten.cues.forEach { cue ->
            assertTrue("non positive duration in $cue", cue.endMs > cue.startMs)
            assertTrue("negative start in $cue", cue.startMs >= 0L)
        }
    }

    /**
     * A rescaled track's durations must be rescaled too, otherwise a PAL conversion leaves every
     * cue on screen 4% too long and the last ones overlap their successors.
     */
    @Test
    fun `rewrite rescales cue durations as well as onsets`() {
        val drifted = target.driftedBy(palRatio)
        val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, drifted.cues))
        val rewritten = plan.rewrite(drifted).cues.associateBy(SrtCue::text)

        val uniqueTexts = drifted.cues.uniqueTexts()
        drifted.cues.filter { it.text in uniqueTexts }.forEach { cue ->
            val expectedDurationMs = ((cue.endMs - cue.startMs) * plan.rateRatio).roundToLong()
            val actualDurationMs = requireNotNull(rewritten[cue.text])
                .let { it.endMs - it.startMs }
            assertTrue(
                "duration ${actualDurationMs}ms was not rescaled from ${cue.endMs - cue.startMs}ms",
                abs(actualDurationMs - expectedDurationMs) <= 1L
            )
        }
    }

    // ----------------------------------------------------------- performance

    /**
     * The rate scan costs a full alignment per ratio, so a correctly framed subtitle must
     * short-circuit on the very first one. This shares the budget of the plain aligner's own
     * perf guard in [SubtitleSyncRealWorldTest]; scanning all ratios would blow well past it.
     */
    @Test
    fun `an already correct track pays nothing for frame rate support`() {
        SubtitleRateAwareAligner.align(reference.cues, target.cues) // warm up

        val elapsedMs = (1..3).minOf {
            val startNs = System.nanoTime()
            SubtitleRateAwareAligner.align(reference.cues, target.cues)
            (System.nanoTime() - startNs) / 1_000_000L
        }

        println("[rate-sync] unscaled short circuit best of 3: ${elapsedMs}ms")
        assertTrue("no-drift alignment took ${elapsedMs}ms", elapsedMs < 4_000L)
    }

    // --------------------------------------------------------------- helpers

    private fun assertRecovers(driftRatio: Double, label: String) {
        val drifted = target.driftedBy(driftRatio)
        val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, drifted.cues)) {
            "$label was refused outright"
        }

        assertEquals("$label picked the wrong ratio", driftRatio, plan.rateRatio, 1e-9)
        val quality = quality(plan.rewrite(drifted))
        println("[rate-sync] $label -> ratio=${plan.rateRatio} conf=${"%.4f".format(plan.confidence)} " +
            "segments=${plan.model.segments.size} $quality")
        assertTrue("$label left cues displaced: $quality", quality.misplacedOverOneSecond == 0)
        assertTrue("$label median error too high: $quality", quality.medianErrorMs <= 100L)
    }

    /** Best confidence any ratio can reach on [drifted], guard ignored. */
    private fun bestRawConfidenceAcrossRatios(drifted: SrtDocument): Double =
        listOf(
            1.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0,
            24.0 / 25.0, 25.0 / 23.976, 23.976 / 25.0
        ).mapNotNull { ratio ->
            SubtitleTimingAligner.align(reference.cues, drifted.cues.scaledBy(ratio))?.confidence
        }.maxOrNull() ?: 0.0

    private fun List<SrtCue>.scaledBy(ratio: Double): List<SrtCue> = map {
        it.copy(
            startMs = (it.startMs * ratio).roundToLong(),
            endMs = (it.endMs * ratio).roundToLong()
        )
    }

    private data class SyncQuality(val medianErrorMs: Long, val misplacedOverOneSecond: Int, val scored: Int) {
        override fun toString(): String =
            "median=${medianErrorMs}ms misplaced=$misplacedOverOneSecond/$scored"
    }

    /**
     * Exact per-cue displacement against the undrifted original, paired by text. Texts occurring
     * more than once are skipped because they cannot be paired unambiguously; 941 of the 989
     * Hebrew cues are unique.
     */
    private fun quality(rewritten: SrtDocument): SyncQuality {
        val uniqueTexts = target.cues.uniqueTexts()
        val truth = target.cues.filter { it.text in uniqueTexts }.associate { it.text to it.startMs }
        val errors = rewritten.cues
            .mapNotNull { cue -> truth[cue.text]?.let { abs(cue.startMs - it) } }
            .sorted()
        require(errors.size > 900) { "scored only ${errors.size} cues" }
        return SyncQuality(
            medianErrorMs = errors[errors.size / 2],
            misplacedOverOneSecond = errors.count { it > 1_000L },
            scored = errors.size
        )
    }

    private fun List<SrtCue>.uniqueTexts(): Set<String> =
        groupingBy(SrtCue::text).eachCount().filterValues { it == 1 }.keys

    /**
     * Retimes the document as if it had been authored for a frame rate `ratio` away, so that
     * rescaling it *by* `ratio` restores the original.
     */
    private fun SrtDocument.driftedBy(ratio: Double): SrtDocument = SrtDocument(
        cues.map { it.copy(startMs = (it.startMs / ratio).toLong(), endMs = (it.endMs / ratio).toLong()) }
    )

    private fun SrtDocument.shiftedBy(deltaMs: Long): SrtDocument = SrtDocument(
        cues.map { it.copy(startMs = it.startMs + deltaMs, endMs = it.endMs + deltaMs) }
    )

    private fun fixture(name: String): SrtDocument =
        SrtDocument.parse(requireNotNull(javaClass.getResource("/subtitle-sync/$name")).readText())
}
