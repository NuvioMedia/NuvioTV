package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Regression suite for Auto Frame Rate (AFR) pure logic in [FrameRateUtils].
 *
 * Covers behaviour introduced after 0.7.10-beta:
 * - NextLib header bypass (auth vs synthetic UA/Connection/Range)
 * - FPS cache keys (filename, query stripping, header sensitivity)
 * - snapToStandardRate / cinema rate buckets
 * - live / mkv / scheme gating for NextLib
 */
class FrameRateUtilsAfrTest {

    @Before
    fun setUp() {
        FrameRateUtils.clearFrameRateCache()
        FrameRateUtils.clearOriginalDisplayMode()
    }

    @After
    fun tearDown() {
        FrameRateUtils.clearFrameRateCache()
        FrameRateUtils.clearOriginalDisplayMode()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // snapToStandardRate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `snap maps NTSC film band to 23_976`() {
        assertNear(24000f / 1001f, FrameRateUtils.snapToStandardRate(23.976f))
        assertNear(24000f / 1001f, FrameRateUtils.snapToStandardRate(23.97f))
        assertNear(24000f / 1001f, FrameRateUtils.snapToStandardRate(23.90f))
        assertNear(24000f / 1001f, FrameRateUtils.snapToStandardRate(23.987f))
    }

    @Test
    fun `snap maps true cinema 24 band`() {
        assertEquals(24f, FrameRateUtils.snapToStandardRate(24.0f), 0.0001f)
        assertEquals(24f, FrameRateUtils.snapToStandardRate(23.99f), 0.0001f)
        assertEquals(24f, FrameRateUtils.snapToStandardRate(24.1f), 0.0001f)
    }

    @Test
    fun `snap maps PAL and NTSC TV rates`() {
        assertEquals(25f, FrameRateUtils.snapToStandardRate(25.0f), 0.0001f)
        assertEquals(25f, FrameRateUtils.snapToStandardRate(24.95f), 0.0001f)
        assertNear(30000f / 1001f, FrameRateUtils.snapToStandardRate(29.97f))
        assertEquals(30f, FrameRateUtils.snapToStandardRate(30.0f), 0.0001f)
        assertEquals(50f, FrameRateUtils.snapToStandardRate(50.0f), 0.0001f)
        assertNear(60000f / 1001f, FrameRateUtils.snapToStandardRate(59.94f))
        assertEquals(60f, FrameRateUtils.snapToStandardRate(60.0f), 0.0001f)
    }

    @Test
    fun `snap leaves non-standard and invalid rates unchanged`() {
        assertEquals(0f, FrameRateUtils.snapToStandardRate(0f), 0f)
        assertEquals(-1f, FrameRateUtils.snapToStandardRate(-1f), 0f)
        assertEquals(48f, FrameRateUtils.snapToStandardRate(48f), 0.0001f)
        assertEquals(120f, FrameRateUtils.snapToStandardRate(120f), 0.0001f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // hasNextLibBlockingHeaders / shouldUseNextLibProbe — 0.7.10 regression
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `synthetic headers alone do not block NextLib — UA Connection Range`() {
        assertFalse(
            "User-Agent alone must not block NextLib (0.7.10 parity regression)",
            FrameRateUtils.hasNextLibBlockingHeaders(mapOf("User-Agent" to "NuvioTV/1.0"))
        )
        assertFalse(
            FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Connection" to "close"))
        )
        assertFalse(
            FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Range" to "bytes=0-1"))
        )
        assertFalse(
            FrameRateUtils.hasNextLibBlockingHeaders(
                mapOf(
                    "User-Agent" to "NuvioTV/1.0",
                    "Connection" to "close",
                    "Range" to "bytes=0-"
                )
            )
        )
        // Case-insensitive header names
        assertFalse(
            FrameRateUtils.hasNextLibBlockingHeaders(
                mapOf("user-agent" to "x", "connection" to "close", "range" to "bytes=0-")
            )
        )
    }

    @Test
    fun `auth and cookie headers block NextLib`() {
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Authorization" to "Bearer tok")))
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Cookie" to "session=abc")))
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("X-Auth-Token" to "secret")))
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Proxy-Authorization" to "Basic x")))
        // Auth mixed with synthetic headers still blocks
        assertTrue(
            FrameRateUtils.hasNextLibBlockingHeaders(
                mapOf(
                    "User-Agent" to "NuvioTV",
                    "Authorization" to "Bearer tok",
                    "Connection" to "close"
                )
            )
        )
    }

    @Test
    fun `blank header values do not block NextLib`() {
        assertFalse(
            FrameRateUtils.hasNextLibBlockingHeaders(
                mapOf("Authorization" to "", "Cookie" to "   ")
            )
        )
    }

    @Test
    fun `Accept-like headers block NextLib today — documented current policy`() {
        // Not necessarily ideal long-term, but this locks current behaviour so
        // accidental changes to the allowlist are caught.
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Accept" to "*/*")))
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Referer" to "https://example.com")))
        assertTrue(FrameRateUtils.hasNextLibBlockingHeaders(mapOf("Origin" to "https://example.com")))
    }

    @Test
    fun `shouldUseNextLibProbe rejects blank and live streams`() {
        assertFalse(FrameRateUtils.shouldUseNextLibProbe("", emptyMap()))
        assertFalse(FrameRateUtils.shouldUseNextLibProbe("   ", emptyMap()))
        assertFalse(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://cdn.example.com/live/manifest.mpd",
                emptyMap()
            )
        )
        assertFalse(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://cdn.example.com/live/foo.ism/manifest?token=1",
                emptyMap()
            )
        )
    }

    @Test
    fun `shouldUseNextLibProbe allows public http https mkv and local schemes`() {
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://cdn.example.com/movie.mkv",
                emptyMap()
            )
        )
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "http://cdn.example.com/movie.mp4",
                emptyMap()
            )
        )
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "file:///storage/emulated/0/movie.mkv",
                emptyMap()
            )
        )
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "content://media/external/video/media/1",
                emptyMap()
            )
        )
        // MKV extension short-circuits even without scheme recognition
        assertTrue(
            FrameRateUtils.shouldUseNextLibProbe(
                "https://cdn.example.com/path/Movie.Name.1080p.mkv?token=abc",
                emptyMap()
            )
        )
    }

    @Test
    fun `shouldUseNextLibProbe allows streams with only synthetic headers — 0_7_10 parity`() {
        val publicUrl = "https://public.cdn.example.com/film.mkv"
        val synthetic = mapOf(
            "User-Agent" to "Mozilla/5.0 NuvioTV",
            "Connection" to "close"
        )
        assertTrue(
            "Public streams that only inject UA must still use NextLib (regression vs broken intermediate)",
            FrameRateUtils.shouldUseNextLibProbe(publicUrl, synthetic)
        )
    }

    @Test
    fun `shouldUseNextLibProbe bypasses NextLib for authenticated debrid streams`() {
        val debridUrl = "https://real-debrid.com/d/ABC123/movie.mkv"
        assertFalse(
            FrameRateUtils.shouldUseNextLibProbe(
                debridUrl,
                mapOf(
                    "User-Agent" to "NuvioTV",
                    "Authorization" to "Bearer secret",
                    "Cookie" to "auth=1"
                )
            )
        )
    }

    @Test
    fun `detectFrameRateFromNextLib returns null immediately when blocked by headers`() {
        // Gate returns null before MediaInfoBuilder runs — Context must not be required.
        val context = mockk<Context>(relaxed = true)
        val result = FrameRateUtils.detectFrameRateFromNextLib(
            context = context,
            sourceUrl = "https://cdn.example.com/a.mkv",
            headers = mapOf("Authorization" to "Bearer x")
        )
        assertNull(result)
    }

    @Test
    fun `detectFrameRateFromNextLib returns null for live dash without touching heavy probe`() {
        val context = mockk<Context>(relaxed = true)
        assertNull(
            FrameRateUtils.detectFrameRateFromNextLib(
                context = context,
                sourceUrl = "https://cdn.example.com/live.mpd",
                headers = emptyMap()
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header helpers used by preflight
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `streamHeadersForAfrProbe strips Range case-insensitively`() {
        val input = mapOf(
            "User-Agent" to "NuvioTV",
            "Range" to "bytes=0-1023",
            "range" to "bytes=0-",
            "Authorization" to "Bearer x"
        )
        val stream = FrameRateUtils.streamHeadersForAfrProbe(input)
        assertFalse(stream.keys.any { it.equals("Range", ignoreCase = true) })
        assertEquals("NuvioTV", stream["User-Agent"])
        assertEquals("Bearer x", stream["Authorization"])
    }

    @Test
    fun `extractorProbeHeaders adds Connection close and keeps auth`() {
        val probe = FrameRateUtils.extractorProbeHeaders(
            mapOf(
                "Authorization" to "Bearer x",
                "Range" to "bytes=0-1",
                "User-Agent" to "NuvioTV"
            )
        )
        assertEquals("close", probe["Connection"])
        assertEquals("Bearer x", probe["Authorization"])
        assertEquals("NuvioTV", probe["User-Agent"])
        assertFalse(probe.keys.any { it.equals("Range", ignoreCase = true) })
    }

    @Test
    fun `NextLib decision uses stream headers not extractor Connection synthetic`() {
        // Preflight passes streamHeaders (no Connection) to NextLib.
        // Connection alone must not flip the gate — and streamHeaders never inject it.
        val original = mapOf("User-Agent" to "NuvioTV")
        val stream = FrameRateUtils.streamHeadersForAfrProbe(original)
        val probe = FrameRateUtils.extractorProbeHeaders(original)

        assertTrue(FrameRateUtils.shouldUseNextLibProbe("https://cdn.example.com/a.mkv", stream))
        // Even if someone mistakenly passed probe headers, Connection is ignored
        assertTrue(FrameRateUtils.shouldUseNextLibProbe("https://cdn.example.com/a.mkv", probe))
        assertEquals("close", probe["Connection"])
        assertNull(stream["Connection"])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isMkvSource / isLiveStreamUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isMkvSource ignores query string and case`() {
        assertTrue(FrameRateUtils.isMkvSource("https://x/a.mkv"))
        assertTrue(FrameRateUtils.isMkvSource("https://x/A.MKV?token=1"))
        assertFalse(FrameRateUtils.isMkvSource("https://x/a.mp4"))
        assertFalse(FrameRateUtils.isMkvSource("https://x/a.mkv.mp4"))
    }

    @Test
    fun `isLiveStreamUrl detects mpd and ism manifest`() {
        assertTrue(FrameRateUtils.isLiveStreamUrl("https://x/live.mpd"))
        assertTrue(FrameRateUtils.isLiveStreamUrl("https://x/foo.ism/manifest?x=1"))
        assertFalse(FrameRateUtils.isLiveStreamUrl("https://x/vod.m3u8"))
        assertFalse(FrameRateUtils.isLiveStreamUrl("https://x/movie.mkv"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FPS cache
    // ─────────────────────────────────────────────────────────────────────────

    private fun detection(fps: Float = 23.976f, w: Int? = 1920, h: Int? = 1080) =
        FrameRateUtils.FrameRateDetection(
            raw = fps,
            snapped = FrameRateUtils.snapToStandardRate(fps),
            videoWidth = w,
            videoHeight = h
        )

    @Test
    fun `cache hit returns same detection for identical url and headers`() {
        val url = "https://cdn.example.com/movie.mkv"
        val headers = mapOf("User-Agent" to "NuvioTV")
        val det = detection(24f)
        FrameRateUtils.cacheFrameRate(url, headers, det)
        val cached = FrameRateUtils.getCachedFrameRate(url, headers)
        assertNotNull(cached)
        assertEquals(det.raw, cached!!.raw, 0.0001f)
        assertEquals(det.snapped, cached.snapped, 0.0001f)
        assertEquals(det.videoWidth, cached.videoWidth)
        assertEquals(det.videoHeight, cached.videoHeight)
    }

    @Test
    fun `cache key strips query string when filename is absent`() {
        val det = detection(25f)
        FrameRateUtils.cacheFrameRate(
            "https://cdn.example.com/movie.mkv?token=AAA&exp=1",
            emptyMap(),
            det
        )
        val hit = FrameRateUtils.getCachedFrameRate(
            "https://cdn.example.com/movie.mkv?token=BBB&exp=2",
            emptyMap()
        )
        assertNotNull("Signed URL query rotation must still hit cache", hit)
        assertEquals(25f, hit!!.raw, 0.0001f)
    }

    @Test
    fun `cache with filename keys by host and filename not full url`() {
        val det = detection(23.976f)
        val filename = "Movie.2024.1080p.mkv"
        FrameRateUtils.cacheFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN1/path",
            headers = emptyMap(),
            detection = det,
            filename = filename
        )
        val hit = FrameRateUtils.getCachedFrameRate(
            url = "https://download.real-debrid.com/d/TOKEN2/other-path",
            headers = emptyMap(),
            filename = filename
        )
        assertNotNull(hit)
        assertEquals(det.raw, hit!!.raw, 0.0001f)
    }

    @Test
    fun `cache with same filename on different host is a miss`() {
        val det = detection(24f)
        val filename = "SameName.mkv"
        FrameRateUtils.cacheFrameRate(
            "https://host-a.example.com/a",
            emptyMap(),
            det,
            filename
        )
        assertNull(
            FrameRateUtils.getCachedFrameRate(
                "https://host-b.example.com/a",
                emptyMap(),
                filename
            )
        )
    }

    @Test
    fun `cache distinguishes auth headers — different tokens do not collide`() {
        val url = "https://cdn.example.com/movie.mkv"
        val detA = detection(24f)
        val detB = detection(25f)
        FrameRateUtils.cacheFrameRate(url, mapOf("Authorization" to "tok-A"), detA)
        FrameRateUtils.cacheFrameRate(url, mapOf("Authorization" to "tok-B"), detB)

        assertEquals(
            24f,
            FrameRateUtils.getCachedFrameRate(url, mapOf("Authorization" to "tok-A"))!!.raw,
            0.0001f
        )
        assertEquals(
            25f,
            FrameRateUtils.getCachedFrameRate(url, mapOf("Authorization" to "tok-B"))!!.raw,
            0.0001f
        )
    }

    @Test
    fun `Range header is ignored in cache key`() {
        val url = "https://cdn.example.com/movie.mkv"
        val det = detection(30f)
        FrameRateUtils.cacheFrameRate(url, mapOf("Range" to "bytes=0-1"), det)
        val hit = FrameRateUtils.getCachedFrameRate(url, emptyMap())
        assertNotNull(hit)
        assertEquals(30f, hit!!.raw, 0.0001f)
    }

    @Test
    fun `empty and missing cache entries return null`() {
        assertNull(
            FrameRateUtils.getCachedFrameRate("https://never-cached.example.com/x.mkv", emptyMap())
        )
    }

    @Test
    fun `buildCacheKey is stable under header key casing and order`() {
        val url = "https://cdn.example.com/a.mkv"
        val key1 = FrameRateUtils.buildCacheKey(
            url,
            mapOf("Authorization" to "x", "User-Agent" to "ua"),
            null
        )
        val key2 = FrameRateUtils.buildCacheKey(
            url,
            mapOf("user-agent" to "ua", "authorization" to "x"),
            null
        )
        assertEquals(key1, key2)
    }

    @Test
    fun `buildCacheKey without filename uses path without query`() {
        val key = FrameRateUtils.buildCacheKey(
            "https://cdn.example.com/path/movie.mkv?a=1&b=2",
            emptyMap(),
            null
        )
        assertEquals("https://cdn.example.com/path/movie.mkv", key)
    }

    @Test
    fun `buildCacheKey with filename uses file host scheme`() {
        val key = FrameRateUtils.buildCacheKey(
            "https://download.real-debrid.com/d/TOKEN/file",
            emptyMap(),
            "Film.mkv"
        )
        assertEquals("file://download.real-debrid.com/Film.mkv", key)
    }

    @Test
    fun `filename present still includes non-Range headers in key — current policy`() {
        // Documents known limitation: rotating Authorization defeats filename cache.
        val keyA = FrameRateUtils.buildCacheKey(
            "https://host.example.com/x",
            mapOf("Authorization" to "A"),
            "Film.mkv"
        )
        val keyB = FrameRateUtils.buildCacheKey(
            "https://host.example.com/x",
            mapOf("Authorization" to "B"),
            "Film.mkv"
        )
        assertNotEquals(keyA, keyB)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // originalModeId lifecycle (process-level singleton)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clearOriginalDisplayMode is idempotent`() {
        FrameRateUtils.clearOriginalDisplayMode()
        FrameRateUtils.clearOriginalDisplayMode()
    }

    @Test
    fun `restoreOriginalDisplayMode returns false when no original mode was recorded`() {
        FrameRateUtils.clearOriginalDisplayMode()
        val activity = mockk<Activity>(relaxed = true)
        // Either SDK gate or missing originalModeId — must not throw and must not claim success.
        assertFalse(FrameRateUtils.restoreOriginalDisplayMode(activity))
    }

    @Test
    fun `cleanupDisplayListener is a safe no-op for API compatibility`() {
        FrameRateUtils.cleanupDisplayListener()
        FrameRateUtils.cleanupDisplayListener()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache capacity (LRU)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `frame rate cache evicts oldest entries beyond capacity`() {
        val det = detection(24f)
        // Capacity is 64; insert 65 unique URLs and ensure the first was evicted.
        repeat(65) { i ->
            FrameRateUtils.cacheFrameRate(
                url = "https://cdn.example.com/evict-$i.mkv",
                headers = emptyMap(),
                detection = det
            )
        }
        assertNull(
            "LRU cache must drop the eldest entry when size exceeds 64",
            FrameRateUtils.getCachedFrameRate("https://cdn.example.com/evict-0.mkv", emptyMap())
        )
        assertNotNull(
            FrameRateUtils.getCachedFrameRate("https://cdn.example.com/evict-64.mkv", emptyMap())
        )
    }

    @Test
    fun `cache overwrite updates detection for same key`() {
        val url = "https://cdn.example.com/overwrite.mkv"
        FrameRateUtils.cacheFrameRate(url, emptyMap(), detection(24f))
        FrameRateUtils.cacheFrameRate(url, emptyMap(), detection(25f))
        assertEquals(25f, FrameRateUtils.getCachedFrameRate(url, emptyMap())!!.raw, 0.0001f)
    }

    @Test
    fun `blank filename is treated as absent for cache key`() {
        val withBlank = FrameRateUtils.buildCacheKey(
            "https://cdn.example.com/a.mkv?t=1",
            emptyMap(),
            "   "
        )
        val without = FrameRateUtils.buildCacheKey(
            "https://cdn.example.com/a.mkv?t=1",
            emptyMap(),
            null
        )
        // isNullOrBlank → query-stripped URL path
        assertEquals(without, withBlank)
        assertEquals("https://cdn.example.com/a.mkv", withBlank)
    }

    @Test
    fun `header values are trimmed in cache key via sanitize`() {
        val keyLoose = FrameRateUtils.buildCacheKey(
            "https://cdn.example.com/a.mkv",
            mapOf(" Authorization " to " token "),
            null
        )
        val keyTight = FrameRateUtils.buildCacheKey(
            "https://cdn.example.com/a.mkv",
            mapOf("Authorization" to "token"),
            null
        )
        assertEquals(keyTight, keyLoose)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cross-scenario matrix: probe path selection summary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe path matrix matches 0_7_10 expectations for common stream types`() {
        data class Case(
            val name: String,
            val url: String,
            val headers: Map<String, String>,
            val expectNextLib: Boolean
        )

        val cases = listOf(
            Case("public mkv no headers", "https://cdn/x.mkv", emptyMap(), true),
            Case("public mp4 no headers", "https://cdn/x.mp4", emptyMap(), true),
            Case("public + UA only", "https://cdn/x.mkv", mapOf("User-Agent" to "Nuvio"), true),
            Case(
                "public + UA + Connection",
                "https://cdn/x.mkv",
                mapOf("User-Agent" to "Nuvio", "Connection" to "close"),
                true
            ),
            Case(
                "debrid auth",
                "https://cdn/x.mkv",
                mapOf("Authorization" to "Bearer t"),
                false
            ),
            Case(
                "debrid cookie",
                "https://cdn/x.mkv",
                mapOf("Cookie" to "a=b"),
                false
            ),
            Case(
                "debrid auth + UA (realistic preflight streamHeaders)",
                "https://cdn/x.mkv",
                FrameRateUtils.streamHeadersForAfrProbe(
                    mapOf(
                        "User-Agent" to "Nuvio",
                        "Authorization" to "Bearer t",
                        "Range" to "bytes=0-"
                    )
                ),
                false
            ),
            Case(
                "public after streamHeadersForAfrProbe strips Range",
                "https://cdn/x.mkv",
                FrameRateUtils.streamHeadersForAfrProbe(
                    mapOf("User-Agent" to "Nuvio", "Range" to "bytes=0-")
                ),
                true
            ),
            Case("dash live", "https://cdn/live.mpd", emptyMap(), false),
            Case("ism live", "https://cdn/live.ism/manifest", emptyMap(), false),
            Case("blank url", "", emptyMap(), false),
            Case("unsupported scheme rtmp", "rtmp://cdn/stream", emptyMap(), false),
        )

        cases.forEach { c ->
            assertEquals(
                "Failed case: ${c.name}",
                c.expectNextLib,
                FrameRateUtils.shouldUseNextLibProbe(c.url, c.headers)
            )
        }
    }

    @Test
    fun `snap boundary edges at band limits`() {
        // Lower edge of NTSC film band is inclusive 23.90
        assertNear(24000f / 1001f, FrameRateUtils.snapToStandardRate(23.90f))
        // Just below band stays unsnapped
        assertEquals(23.89f, FrameRateUtils.snapToStandardRate(23.89f), 0.0001f)
        // 24.1 is upper edge of cinema 24
        assertEquals(24f, FrameRateUtils.snapToStandardRate(24.1f), 0.0001f)
        assertEquals(24.11f, FrameRateUtils.snapToStandardRate(24.11f), 0.0001f)
    }

    private fun assertNear(expected: Float, actual: Float, epsilon: Float = 0.001f) {
        assertTrue(
            "Expected ~$expected but was $actual",
            abs(expected - actual) <= epsilon
        )
    }
}
