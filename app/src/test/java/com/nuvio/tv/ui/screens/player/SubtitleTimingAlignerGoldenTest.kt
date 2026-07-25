package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Characterization test. It pins the exact output of [SubtitleTimingAligner] across a spread of
 * scenarios so that performance refactors can be proven to be behaviour preserving.
 *
 * This is deliberately a golden-file test rather than a set of hand-written assertions: its job is
 * not to say what is correct, it is to say "whatever it did before, it still does".
 *
 * To re-baseline after an intentional behaviour change:
 *
 *     ./gradlew :app:testFullDebugUnitTest --tests "*SubtitleTimingAlignerGoldenTest" \
 *         -Dsubtitle.sync.golden.write=true
 *
 * and review the resulting diff in `app/src/test/resources/subtitle-sync/golden-alignments.txt`.
 */
class SubtitleTimingAlignerGoldenTest {

    @Test
    fun `aligner output matches the recorded baseline`() {
        val actual = scenarios().joinToString("\n") { (name, model) -> "$name -> ${signature(model)}" }

        if (System.getProperty(WRITE_PROPERTY) == "true") {
            val destination = File("src/test/resources/subtitle-sync/$GOLDEN_NAME")
            destination.parentFile.mkdirs()
            destination.writeText(actual + "\n")
            println("[subtitle-sync] rebaselined ${destination.absolutePath}")
            return
        }

        val expected = requireNotNull(javaClass.getResource("/subtitle-sync/$GOLDEN_NAME")) {
            "Missing golden baseline. Re-run with -D$WRITE_PROPERTY=true to create it."
        }.readText()

        // Normalized because git may check the baseline out with CRLF on Windows.
        assertEquals(expected.normalizeLineEndings(), actual.normalizeLineEndings())
    }

    private fun String.normalizeLineEndings(): String =
        replace("\r\n", "\n").replace('\r', '\n').trim()

    // ------------------------------------------------------------------ cases

    private fun scenarios(): List<Pair<String, SubtitleSyncModel?>> {
        val realReference = fixture("real-eng-reference.srt").cues
        val realTarget = fixture("real-heb-target.srt").cues
        val cases = mutableListOf<Pair<String, SubtitleSyncModel?>>()

        fun case(name: String, reference: List<SrtCue>, target: List<SrtCue>) {
            cases += name to SubtitleTimingAligner.align(reference, target)
        }

        // Real world movie pair, already aligned, then desynced in every supported way.
        case("real/aligned", realReference, realTarget)
        listOf(-45_000L, -11_000L, -2_400L, 3_700L, 15_000L, 60_000L).forEach { shift ->
            case("real/constant$shift", realReference, realTarget.shifted(shift))
        }
        case("real/mkv-index", realReference.asIndex(), realTarget.shifted(-8_800L))
        case("real/pgs-index", realReference.asPgsIndex(), realTarget.shifted(-6_250L))
        case("real/partial20min", realReference.filter { it.startMs <= 20 * 60_000L }, realTarget.shifted(-9_100L))
        case("real/partial60min", realReference.filter { it.startMs <= 60 * 60_000L }, realTarget.shifted(-9_100L))
        case(
            "real/ad-break",
            realReference,
            realTarget.map { cue ->
                val shift = if (cue.startMs < 40 * 60_000L) 5_000L else 95_000L
                cue.copy(startMs = cue.startMs - shift, endMs = cue.endMs - shift)
            }
        )
        case("real/beyond-range", realReference, realTarget.shifted(25 * 60_000L))
        case("real/unrelated", fixture("unrelated-embedded.srt").cues, realTarget)

        // Curated fixtures.
        case("fixture/constant", fixture("episode-embedded-constant.srt").cues, fixture("episode-addon.srt").cues)
        case("fixture/recap", fixture("episode-embedded-recap.srt").cues, fixture("episode-addon.srt").cues)
        case("fixture/sdh", fixture("episode-embedded-sdh.srt").cues, fixture("episode-addon.srt").cues)
        case("fixture/ad-break", fixture("ad-break-embedded.srt").cues, fixture("ad-break-addon.srt").cues)
        case("fixture/unrelated", fixture("unrelated-embedded.srt").cues, fixture("episode-addon.srt").cues)
        case("fixture/fps-drift", fixture("fps-25-embedded.srt").cues, fixture("fps-23976-addon.srt").cues)

        // Synthetic edge cases mirroring SubtitleTimingAlignerTest.
        val dense = timeline(220, 3_000L)
        case("synthetic/sparse-index", dense.filterIndexed { i, _ -> i % 12 == 0 }.map { SrtCue(it.startMs + 3_500L, it.startMs + 4_500L, " ") }, dense)
        case("synthetic/three-regions", dense.map { cue ->
            val shift = when {
                cue.startMs < 200_000L -> 1_500L
                cue.startMs < 450_000L -> 31_500L
                else -> 46_500L
            }
            cue.copy(startMs = cue.startMs + shift, endMs = cue.endMs + shift)
        }, dense)
        case("synthetic/partial-capture", dense.drop(90).take(35).map { it.copy(startMs = it.startMs + 2_000L, endMs = it.endMs + 2_000L) }, dense)
        case("synthetic/too-sparse", timeline(5, 5_000L), timeline(5, 5_000L))

        return cases
    }

    // ---------------------------------------------------------------- helpers

    private fun signature(model: SubtitleSyncModel?): String {
        if (model == null) return "null"
        val segments = model.segments.joinToString(",") { "${it.targetStartMs}:${it.targetEndMs}:${it.offsetMs}:${round(it.confidence)}" }
        return "conf=${round(model.confidence)} matched=${model.matchedCueCount} [$segments]"
    }

    private fun round(value: Double): String = "%.6f".format(value)

    private fun List<SrtCue>.shifted(deltaMs: Long): List<SrtCue> =
        map { it.copy(startMs = it.startMs + deltaMs, endMs = it.endMs + deltaMs) }

    private fun List<SrtCue>.asIndex(): List<SrtCue> =
        map { SrtCue(it.startMs, it.startMs + 1_000L, " ") }.distinctBy(SrtCue::startMs)

    private fun List<SrtCue>.asPgsIndex(): List<SrtCue> =
        flatMap { listOf(it.startMs, it.endMs) }
            .sorted()
            .filterIndexed { index, _ -> index % 2 == 0 }
            .map { SrtCue(it, it + 1_000L, " ") }

    private fun timeline(count: Int, spacingMs: Long): List<SrtCue> {
        var cueStart = 0L
        return List(count) { index ->
            cueStart += if (index == 0) 0L else spacingMs + ((index * 7_919L) % 1_700L) - 850L
            SrtCue(cueStart, cueStart + 1_400L + (index % 4) * 120L, "Line $index")
        }
    }

    private fun fixture(name: String): SrtDocument =
        SrtDocument.parse(requireNotNull(javaClass.getResource("/subtitle-sync/$name")).readText())

    private companion object {
        const val GOLDEN_NAME = "golden-alignments.txt"
        const val WRITE_PROPERTY = "subtitle.sync.golden.write"
    }
}
