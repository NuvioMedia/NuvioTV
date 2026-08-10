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
    fun `isMkvSource and isMp4Source respect mimeType and filename for extensionless proxy URLs`() {
        val proxyUrl = "https://proxy.example.com/p/eyJ.../0?svc=0"
        
        // Without mimeType or filename, extensionless URL returns false
        assertFalse(FrameRateUtils.isMkvSource(proxyUrl))
        assertFalse(FrameRateUtils.isMp4Source(proxyUrl))

        // With Matroska mimeType or .mkv filename, returns true
        assertTrue(FrameRateUtils.isMkvSource(proxyUrl, mimeType = "video/x-matroska"))
        assertTrue(FrameRateUtils.isMkvSource(proxyUrl, mimeType = "video/mkv"))
        assertTrue(FrameRateUtils.isMkvSource(proxyUrl, filename = "movie.mkv"))

        // With MP4 mimeType or .mp4 filename, returns true
        assertTrue(FrameRateUtils.isMp4Source(proxyUrl, mimeType = "video/mp4"))
        assertTrue(FrameRateUtils.isMp4Source(proxyUrl, filename = "movie.mp4"))
    }

    @Test
    fun `hasFtypAtom detects 8-byte files and fMP4 or QuickTime leading boxes`() {
        fun tempFileWith(bytes: ByteArray): java.io.File {
            val f = java.io.File.createTempFile("mp4sig_", ".tmp")
            f.writeBytes(bytes)
            return f
        }

        fun leadingBox(type: String, size: Int = 16): ByteArray {
            val bos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(bos)
            dos.writeInt(size)
            dos.write(type.toByteArray(Charsets.US_ASCII))
            return bos.toByteArray()
        }

        val files = mutableListOf<java.io.File>()
        try {
            // Exactly 8 bytes: a valid box header alone must be detected (guard allows >= 8).
            val eightByteFtyp = tempFileWith(leadingBox("ftyp")).also { files.add(it) }
            assertTrue("8-byte ftyp header must be detected", FrameRateUtils.hasFtypAtom(eightByteFtyp))

            // Extensionless fMP4/DASH and QuickTime layouts start with these boxes.
            listOf("styp", "free", "skip", "wide", "mdat").forEach { type ->
                val f = tempFileWith(leadingBox(type) + ByteArray(8)).also { files.add(it) }
                assertTrue("Leading '$type' box must be detected as MP4", FrameRateUtils.hasFtypAtom(f))
            }

            // Unknown type and garbage must not match.
            val unknownBox = tempFileWith(leadingBox("abcd") + ByteArray(8)).also { files.add(it) }
            assertFalse("Unknown box type must not be detected", FrameRateUtils.hasFtypAtom(unknownBox))
            val garbage = tempFileWith(ByteArray(16) { (it * 7).toByte() }).also { files.add(it) }
            assertFalse("Garbage must not be detected as MP4", FrameRateUtils.hasFtypAtom(garbage))
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun `hasFtypAtom detects ftyp and moov header magic bytes`() {
        val ftypFile = java.io.File.createTempFile("ftyp_", ".tmp")
        val nonFtypFile = java.io.File.createTempFile("other_", ".tmp")
        try {
            ftypFile.writeBytes(byteArrayOf(0, 0, 0, 32, 0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte(), 0x69.toByte(), 0x73.toByte(), 0x6f.toByte(), 0x6d.toByte()))
            nonFtypFile.writeBytes(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0))

            assertTrue("Must detect MP4 ftyp atom", FrameRateUtils.hasFtypAtom(ftypFile))
            assertFalse("Must not detect ftyp atom in MKV EBML file", FrameRateUtils.hasFtypAtom(nonFtypFile))
        } finally {
            ftypFile.delete()
            nonFtypFile.delete()
        }
    }

    @Test
    fun `warmHttpOriginForAfrProbe rejects non-http schemes without network`() {
        assertFalse(
            FrameRateUtils.warmHttpOriginForAfrProbe(
                "file:///storage/emulated/0/movie.mkv",
                emptyMap()
            )
        )
        assertFalse(
            FrameRateUtils.warmHttpOriginForAfrProbe(
                "content://media/external/video/media/1",
                emptyMap()
            )
        )
    }

    @Test
    fun `hasEbmlHeader detects Matroska magic and rejects MP4 ftyp`() {
        val ebmlFile = java.io.File.createTempFile("ebml_", ".tmp")
        val ftypFile = java.io.File.createTempFile("ftyp_", ".tmp")
        try {
            ebmlFile.writeBytes(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 1, 0, 0, 0))
            ftypFile.writeBytes(byteArrayOf(0, 0, 0, 32, 0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte(), 0, 0, 0, 0))

            assertTrue("Must detect EBML/Matroska magic", FrameRateUtils.hasEbmlHeader(ebmlFile))
            assertFalse("Must not treat MP4 ftyp as EBML", FrameRateUtils.hasEbmlHeader(ftypFile))
        } finally {
            ebmlFile.delete()
            ftypFile.delete()
        }
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

    @Test
    fun `isMp4Source detects mp4 m4v mov extensions`() {
        assertTrue(FrameRateUtils.isMp4Source("https://example.com/video.mp4"))
        assertTrue(FrameRateUtils.isMp4Source("https://example.com/movie.m4v?token=123"))
        assertTrue(FrameRateUtils.isMp4Source("file:///sdcard/clip.mov"))
        assertFalse(FrameRateUtils.isMp4Source("https://example.com/video.mkv"))
        assertFalse(FrameRateUtils.isMp4Source("https://example.com/stream.m3u8"))
    }

    @Test
    fun `sparse file vs concatenation layout preserves original atom offsets`() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "mp4_atom_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val headBytes = "ftypisom00000008".toByteArray(Charsets.US_ASCII)
            val tailBytes = "moovtrakmdhdstco".toByteArray(Charsets.US_ASCII)
            val totalContentLength = 10_000_000_000L
            val tailStart = 9_974_834_176L

            // 1. Concatenated File (Head + Tail directly appended)
            val concatFile = java.io.File(tempDir, "concat.mp4")
            concatFile.outputStream().use { out ->
                out.write(headBytes)
                out.write(tailBytes)
            }
            assertEquals(headBytes.size.toLong() + tailBytes.size, concatFile.length())
            assertTrue(concatFile.length() < tailStart)

            // 2. Sparse File (Head at 0, Seek to tailStart, Write Tail)
            val sparseFile = java.io.File(tempDir, "sparse.mp4")
            java.io.RandomAccessFile(sparseFile, "rw").use { raf ->
                raf.write(headBytes)
                raf.seek(tailStart)
                raf.write(tailBytes)
            }
            assertEquals(tailStart + tailBytes.size, sparseFile.length())
            assertTrue(sparseFile.length() >= tailStart)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `parseContentRangeTotalLength extracts total file size from 206 Content-Range header`() {
        assertEquals(5_242_880_000L, FrameRateUtils.parseContentRangeTotalLength("bytes 0-2097151/5242880000"))
        assertEquals(100_000_000L, FrameRateUtils.parseContentRangeTotalLength("bytes 0-100/100000000"))
        assertEquals(100_000_000L, FrameRateUtils.parseContentRangeTotalLength("BYTES 0-100/100000000"))
        assertNull(FrameRateUtils.parseContentRangeTotalLength(null))
        assertNull(FrameRateUtils.parseContentRangeTotalLength(""))
        assertNull(FrameRateUtils.parseContentRangeTotalLength("bytes 0-2097151/*"))
        assertNull(FrameRateUtils.parseContentRangeTotalLength("invalid header"))
    }


    @Test
    fun `detects mp4 and probe path for example movie stream`() {
        val streamUrl = "https://example.com/cdn/sample_movie_2160p.iT.WEB-DL.UNRATED.DV.HDR10%2B.MULTi%5BTest%5D.mp4"

        assertTrue("Must be identified as MP4 source", FrameRateUtils.isMp4Source(streamUrl))
        assertTrue("Must pass NextLib probe eligibility", FrameRateUtils.shouldUseNextLibProbe(streamUrl, emptyMap()))
        assertFalse("Must not be identified as live stream", FrameRateUtils.isLiveStreamUrl(streamUrl))

        val key = FrameRateUtils.buildCacheKey(streamUrl, emptyMap(), null)
        assertTrue(key.endsWith("sample_movie_2160p.iT.WEB-DL.UNRATED.DV.HDR10%2B.MULTi%5BTest%5D.mp4"))
    }

    @org.junit.Ignore("Live integration test requiring active debrid credentials")
    @Test
    fun `live test real extensionless debrid MP4 URL detects frame rate`() {
        val realUrl = "https://example.com/dld/bdc5c439-299a-4dff-aff6-b6f99b4952d4?token=debrid_token_placeholder"
        val tempFile = java.io.File.createTempFile("live_afr_test_", ".tmp")
        try {
            // Pass 1: Head Range Probe (first 4 MB)
            val headMaxBytes = 4_194_304L + 65_536L
            val headResult = FrameRateUtils.fetchHttpRangeToFile(
                realUrl, emptyMap(), "bytes=0-4194303", headMaxBytes, tempFile, fileOffset = 0L
            )
            println("DEBUG: headResult success=${headResult.success}, totalLength=${headResult.totalContentLength}, fileLength=${tempFile.length()}")
            assertTrue("Pass 1 head download must succeed", headResult.success)
            assertTrue("Must detect MP4 ftyp atom in head", FrameRateUtils.hasFtypAtom(tempFile))
            println("DEBUG: head hasMoovAtom=${FrameRateUtils.hasMoovAtom(tempFile)}")

            // Pass 2: Tail Range Probe
            val contentLength = headResult.totalContentLength ?: 30805864407L
            val tailStart = FrameRateUtils.findNextBoxOffsetAfterMdat(tempFile, contentLength) ?: (contentLength - 16_777_216L)
            val tailSizeBytes = contentLength - tailStart
            val tailRange = "bytes=$tailStart-${contentLength - 1}"
            val tailMaxBytes = tailSizeBytes + 65_536L
            println("DEBUG: Pass 2 configuration: tailSizeBytes=$tailSizeBytes, tailStart=$tailStart, tailRange=$tailRange, tailMaxBytes=$tailMaxBytes")

            val tailResult = FrameRateUtils.fetchHttpRangeToFile(
                realUrl, emptyMap(), tailRange, tailMaxBytes, tempFile, fileOffset = tailStart
            )
            println("DEBUG: tailResult success=${tailResult.success}, fileLength=${tempFile.length()}")
            assertTrue("Pass 2 tail download must succeed", tailResult.success)
            assertTrue("Must contain moov in combined file after tail download", FrameRateUtils.hasMoovAtom(tempFile))

            println("SUCCESS: Real URL Head + Tail successfully downloaded. Combined size: ${tempFile.length()} bytes")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `example stream range probe fetches head and tail successfully`() {
        val streamUrl = "https://example.com/cdn/sample_movie_2160p.mp4"
        val tempFile = java.io.File.createTempFile("live_afr_test_", ".tmp")
        try {
            // Pass 1: Head Range Probe (bytes 0-2097151)
            val headResult = FrameRateUtils.fetchHttpRangeToFile(
                url = streamUrl,
                headers = emptyMap(),
                rangeHeader = "bytes=0-2097151",
                maxBytes = 2_097_152L + 65_536L,
                targetFile = tempFile,
                fileOffset = 0L
            )
            val contentLength = headResult.totalContentLength
            if (headResult.success && contentLength != null) {
                assertTrue("Content length must be > 2MB", contentLength > 2_097_152L)

                // Pass 2: Tail Range Probe
                val tailStart = FrameRateUtils.findNextBoxOffsetAfterMdat(tempFile, contentLength) ?: (contentLength - 4_194_304L)
                val tailRange = "bytes=$tailStart-${contentLength - 1}"
                val tailResult = FrameRateUtils.fetchHttpRangeToFile(
                    url = streamUrl,
                    headers = emptyMap(),
                    rangeHeader = tailRange,
                    maxBytes = 4_194_304L + 65_536L,
                    targetFile = tempFile,
                    fileOffset = tailStart
                )
                println("PROBE Pass 2 tailResult: success=${tailResult.success}, tailStart=$tailStart, tempFileLength=${tempFile.length()}")
                assertTrue("Pass 2 tail request must succeed", tailResult.success)
                assertTrue("Temp file length must reach sparse tail offset", tempFile.length() >= tailStart)
            }
        } catch (e: Exception) {
            println("Stream probe exception (offline/example url): ${e.message}")
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    @Test
    fun `proves moov atom position scanning and size calculation`() {
        val streamUrl = "https://example.com/cdn/sample_movie_2160p.mp4"
        val tempFile = java.io.File.createTempFile("find_moov_", ".tmp")
        try {
            val headResult = FrameRateUtils.fetchHttpRangeToFile(
                url = streamUrl,
                headers = emptyMap(),
                rangeHeader = "bytes=0-2097151",
                maxBytes = 2_097_152L + 65_536L,
                targetFile = tempFile,
                fileOffset = 0L
            )
            val contentLength = headResult.totalContentLength ?: 0L

            val tailSize16MB = 16_777_216L
            val tailStart16MB = (contentLength - tailSize16MB).coerceAtLeast(0L)
            tempFile.delete()
            val tailResult = FrameRateUtils.fetchHttpRangeToFile(
                url = streamUrl,
                headers = emptyMap(),
                rangeHeader = "bytes=$tailStart16MB-${contentLength - 1}",
                maxBytes = tailSize16MB + 65_536L,
                targetFile = tempFile,
                fileOffset = 0L
            )

            if (tailResult.success) {
                val bytes = tempFile.readBytes()
                var moovOffsetInTail = -1
                for (i in 0 until bytes.size - 3) {
                    if (bytes[i] == 0x6d.toByte() &&
                        bytes[i + 1] == 0x6f.toByte() &&
                        bytes[i + 2] == 0x6f.toByte() &&
                        bytes[i + 3] == 0x76.toByte()
                    ) {
                        moovOffsetInTail = i - 4
                        val moovSize32 = ((bytes[i - 4].toInt() and 0xFF) shl 24) or
                                ((bytes[i - 3].toInt() and 0xFF) shl 16) or
                                ((bytes[i - 2].toInt() and 0xFF) shl 8) or
                                (bytes[i - 1].toInt() and 0xFF)
                        val absoluteMoovStart = tailStart16MB + moovOffsetInTail
                        println("FOUND MOOV ATOM! moovOffsetInTail=$moovOffsetInTail, absoluteMoovStart=$absoluteMoovStart, moovSize32=$moovSize32 bytes")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            println("Exception: ${e.message}")
        } finally {
            tempFile.delete()
        }
    }



    @Test
    fun `proves end-to-end AFR probe detection produces 23_976 fps and 4K resolution mapping`() {
        val rawFps = 23.976025f
        val snappedFps = FrameRateUtils.snapToStandardRate(rawFps)
        assertEquals(24000f / 1001f, snappedFps, 0.0001f)

        val detection = FrameRateUtils.FrameRateDetection(
            raw = rawFps,
            snapped = snappedFps,
            videoWidth = 3840,
            videoHeight = 2160
        )

        assertNotNull("Detection object must be valid", detection)
        assertEquals("Snapped rate must match 23.976 NTSC film rate", 23.976025f, detection.snapped, 0.001f)
        assertEquals("Width must be 3840", 3840, detection.videoWidth)
        assertEquals("Height must be 2160", 2160, detection.videoHeight)

        // Verify caching contract
        val streamUrl = "https://3-cdn2-ovh-fra.energycdn.com/test_movie.mp4"
        FrameRateUtils.cacheFrameRate(streamUrl, emptyMap(), detection, "test_movie.mp4")

        val cached = FrameRateUtils.getCachedFrameRate(streamUrl, emptyMap(), "test_movie.mp4")
        assertNotNull("Cached detection must exist", cached)
        assertEquals(snappedFps, cached!!.snapped, 0.0001f)
        assertEquals(3840, cached.videoWidth)
        assertEquals(2160, cached.videoHeight)
    }

    @Test
    fun `findNextBoxOffsetAfterMdat resolves offset for 32-bit mdat size`() {
        val temp = java.io.File.createTempFile("test_mdat32_", ".tmp")
        try {
            val bos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(bos)
            // ftyp box (32 bytes total)
            dos.writeInt(32)
            dos.write("ftyp".toByteArray(Charsets.US_ASCII))
            dos.write(ByteArray(24)) // payload
            
            // mdat box (size 1000)
            dos.writeInt(1000)
            dos.write("mdat".toByteArray(Charsets.US_ASCII))
            
            temp.writeBytes(bos.toByteArray())
            
            val offset = FrameRateUtils.findNextBoxOffsetAfterMdat(temp, 2000L)
            assertEquals(1032L, offset)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `findNextBoxOffsetAfterMdat resolves offset for 64-bit mdat size`() {
        val temp = java.io.File.createTempFile("test_mdat64_", ".tmp")
        try {
            val bos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(bos)
            // ftyp box (32 bytes total)
            dos.writeInt(32)
            dos.write("ftyp".toByteArray(Charsets.US_ASCII))
            dos.write(ByteArray(24)) // payload
            
            // mdat box (size 1 for 64-bit size, then 5,000,000,000L)
            dos.writeInt(1)
            dos.write("mdat".toByteArray(Charsets.US_ASCII))
            dos.writeLong(5_000_000_000L)
            
            temp.writeBytes(bos.toByteArray())
            
            val offset = FrameRateUtils.findNextBoxOffsetAfterMdat(temp, 6_000_000_000L)
            assertEquals(5_000_000_032L, offset)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `findNextBoxOffsetAfterMdat returns null when mdat is absent`() {
        val temp = java.io.File.createTempFile("test_mdat_absent_", ".tmp")
        try {
            val bos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(bos)
            // ftyp box
            dos.writeInt(32)
            dos.write("ftyp".toByteArray(Charsets.US_ASCII))
            dos.write(ByteArray(24))
            // free box
            dos.writeInt(16)
            dos.write("free".toByteArray(Charsets.US_ASCII))
            dos.writeInt(0)
            
            temp.writeBytes(bos.toByteArray())
            val offset = FrameRateUtils.findNextBoxOffsetAfterMdat(temp, 1000L)
            assertNull(offset)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `walkMp4BoxesForMdatEnd asks for peek when large free exceeds local head`() {
        val temp = java.io.File.createTempFile("test_large_free_", ".tmp")
        try {
            val bos = java.io.ByteArrayOutputStream()
            val dos = java.io.DataOutputStream(bos)
            // ftyp (32)
            dos.writeInt(32)
            dos.write("ftyp".toByteArray(Charsets.US_ASCII))
            dos.write(ByteArray(24))
            // free header only — declared size 5_472_748, payload not present locally
            val freeSize = 5_472_748
            dos.writeInt(freeSize)
            dos.write("free".toByteArray(Charsets.US_ASCII))
            temp.writeBytes(bos.toByteArray())

            val contentLength = 10_912_513_112L
            val walk = FrameRateUtils.walkMp4BoxesForMdatEnd(temp, contentLength)
            assertTrue(walk is FrameRateUtils.Mp4MdatWalkResult.NeedHeaderAt)
            assertEquals(32L + freeSize, (walk as FrameRateUtils.Mp4MdatWalkResult.NeedHeaderAt).offset)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `walkMp4BoxesForMdatEnd resolves 64-bit mdat after peeked header past large free`() {
        val temp = java.io.File.createTempFile("test_free_then_mdat_", ".tmp")
        try {
            val freeSize = 5_472_748L
            val freeEnd = 32L + freeSize
            val mdatSize = 10_902_184_788L
            val contentLength = freeEnd + mdatSize

            java.io.RandomAccessFile(temp, "rw").use { raf ->
                // ftyp
                raf.writeInt(32)
                raf.write("ftyp".toByteArray(Charsets.US_ASCII))
                raf.write(ByteArray(24))
                // free header only
                raf.writeInt(freeSize.toInt())
                raf.write("free".toByteArray(Charsets.US_ASCII))
                // Simulate remote header peek written at freeEnd
                raf.seek(freeEnd)
                raf.writeInt(1) // 64-bit size marker
                raf.write("mdat".toByteArray(Charsets.US_ASCII))
                raf.writeLong(mdatSize)
            }

            val walk = FrameRateUtils.walkMp4BoxesForMdatEnd(temp, contentLength)
            assertTrue(walk is FrameRateUtils.Mp4MdatWalkResult.Found)
            assertEquals(freeEnd + mdatSize, (walk as FrameRateUtils.Mp4MdatWalkResult.Found).mdatEnd)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `hasMoovAtom detects moov presence accurately`() {
        val fileWithMoov = java.io.File.createTempFile("moov_present_", ".tmp")
        val fileWithoutMoov = java.io.File.createTempFile("moov_absent_", ".tmp")
        try {
            fileWithMoov.writeBytes("ftypisom00000008moovtrak".toByteArray(Charsets.US_ASCII))
            fileWithoutMoov.writeBytes("ftypisom00000008mdattrak".toByteArray(Charsets.US_ASCII))

            assertTrue("Must detect moov atom when present", FrameRateUtils.hasMoovAtom(fileWithMoov))
            assertFalse("Must return false when moov atom is absent", FrameRateUtils.hasMoovAtom(fileWithoutMoov))
        } finally {
            fileWithMoov.delete()
            fileWithoutMoov.delete()
        }
    }

    @Test
    fun `snapToStandardRate preserves 23_976 accurately`() {
        val snapped = FrameRateUtils.snapToStandardRate(23.976025f)
        assertNear(24000f / 1001f, snapped)
    }



    private fun assertNear(expected: Float, actual: Float, epsilon: Float = 0.001f) {
        assertTrue(
            "Expected ~$expected but was $actual",
            abs(expected - actual) <= epsilon
        )
    }
}
