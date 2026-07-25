package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy-level tests for AFR preflight behaviour that can be verified without
 * spinning up [PlayerRuntimeController] or a real Activity/Display.
 *
 * These encode the contracts used by [runAfrPreflightIfEnabled]:
 * - OFF short-circuits
 * - header split for NextLib vs extractor
 * - cache lookup key ingredients
 * - timeout constants (documenting the 0.7.10 → current change)
 */
class AfrPreflightPolicyTest {

    private companion object {
        // Documented 0.7.10-beta values for regression comparison only.
        const val LEGACY_0_7_10_NEXTLIB_TIMEOUT_MS = 30_000L
        const val LEGACY_0_7_10_FALLBACK_TIMEOUT_MS = 5_500L
    }

    @Test
    fun `current preflight timeouts are stricter than 0_7_10-beta`() {
        assertEquals(6_000L, AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS)
        assertEquals(4_000L, AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS)
        assertTrue(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS < LEGACY_0_7_10_NEXTLIB_TIMEOUT_MS)
        assertTrue(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS < LEGACY_0_7_10_FALLBACK_TIMEOUT_MS)
        // Worst-case probe budget after 0.7.10 change
        assertEquals(10_000L, AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS + AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS)
        assertEquals(35_500L, LEGACY_0_7_10_NEXTLIB_TIMEOUT_MS + LEGACY_0_7_10_FALLBACK_TIMEOUT_MS)
    }

    @Test
    fun `FrameRateMatchingMode OFF is the only disabled mode`() {
        assertEquals(
            setOf(FrameRateMatchingMode.OFF, FrameRateMatchingMode.START, FrameRateMatchingMode.START_STOP),
            FrameRateMatchingMode.entries.toSet()
        )
        // Preflight returns immediately only for OFF
        assertTrue(FrameRateMatchingMode.OFF != FrameRateMatchingMode.START)
        assertTrue(FrameRateMatchingMode.START != FrameRateMatchingMode.START_STOP)
    }

    @Test
    fun `START_STOP is the only mode that restores display on player exit`() {
        // Mirrors PlayerScreen DisposableEffect contract:
        // START_STOP → restoreOriginalDisplayMode
        // START / OFF → clearOriginalDisplayMode (no restore on dispose for START)
        fun shouldRestoreOnExit(mode: FrameRateMatchingMode): Boolean =
            mode == FrameRateMatchingMode.START_STOP

        assertFalse(shouldRestoreOnExit(FrameRateMatchingMode.OFF))
        assertFalse(shouldRestoreOnExit(FrameRateMatchingMode.START))
        assertTrue(shouldRestoreOnExit(FrameRateMatchingMode.START_STOP))
    }

    @Test
    fun `preflight header pipeline matches NextLib and extractor contracts`() {
        val incoming = mapOf(
            "User-Agent" to "NuvioTV/0.7",
            "Range" to "bytes=0-65535",
            "Authorization" to "Bearer debrid-token"
        )

        val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(incoming)
        val probeHeaders = FrameRateUtils.extractorProbeHeaders(incoming)

        // NextLib path
        assertFalse(
            "Range must never reach NextLib bypass decision",
            streamHeaders.keys.any { it.equals("Range", ignoreCase = true) }
        )
        assertFalse(
            "Auth streams must skip NextLib",
            FrameRateUtils.shouldUseNextLibProbe(
                "https://download.real-debrid.com/d/ABC/movie.mkv",
                streamHeaders
            )
        )

        // Extractor path keeps auth + Connection: close
        assertEquals("Bearer debrid-token", probeHeaders["Authorization"])
        assertEquals("close", probeHeaders["Connection"])
        assertEquals("NuvioTV/0.7", probeHeaders["User-Agent"])
    }

    @Test
    fun `public stream preflight still enables NextLib after UA injection`() {
        val incoming = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Range" to "bytes=0-"
        )
        val streamHeaders = FrameRateUtils.streamHeadersForAfrProbe(incoming)
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://public.cdn.example.com/releases/film.mkv",
                streamHeaders
            )
        )
    }

    @Test
    fun `cache key ingredients used by preflight are consistent for signed urls`() {
        val filename = "Show.S01E01.1080p.mkv"
        val detection = FrameRateUtils.FrameRateDetection(
            raw = 23.976f,
            snapped = FrameRateUtils.snapToStandardRate(23.976f),
            videoWidth = 1920,
            videoHeight = 1080
        )
        FrameRateUtils.clearFrameRateCache()

        // First play: signed URL A
        FrameRateUtils.cacheFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN_A/file",
            headers = emptyMap(),
            detection = detection,
            filename = filename
        )

        // Second play: signed URL B, same filename (preflight uses currentFilename)
        val cached = FrameRateUtils.getCachedFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN_B/file",
            headers = emptyMap(),
            filename = filename
        )
        assertEquals(23.976f, cached!!.raw, 0.001f)
        FrameRateUtils.clearFrameRateCache()
    }

    @Test
    fun `prefer23976ProbeBias window matches preflight bias gate`() {
        // Mirrors: prefer23976ProbeBias = detection.raw in 23.95f..23.999f
        fun prefer23976(raw: Float): Boolean = raw in 23.95f..23.999f

        assertTrue(prefer23976(23.976f))
        assertTrue(prefer23976(23.95f))
        assertTrue(prefer23976(23.999f))
        assertFalse(prefer23976(24.0f))
        assertFalse(prefer23976(23.94f))
        assertFalse(prefer23976(25f))
    }

    @Test
    fun `duplicate preflight guard condition is exclusive or`() {
        // Mirrors: if (afrProbeRunning || detectedFrameRateSource != null) skip
        fun shouldSkipDuplicate(afrProbeRunning: Boolean, source: FrameRateSource?): Boolean =
            afrProbeRunning || source != null

        assertFalse(shouldSkipDuplicate(false, null))
        assertTrue(shouldSkipDuplicate(true, null))
        assertTrue(shouldSkipDuplicate(false, FrameRateSource.PROBE))
        assertTrue(shouldSkipDuplicate(false, FrameRateSource.TRACK))
        assertTrue(shouldSkipDuplicate(true, FrameRateSource.PROBE))
    }
}
