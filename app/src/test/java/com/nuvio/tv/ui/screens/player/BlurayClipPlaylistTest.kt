package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlurayClipPlaylistTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    private fun mpls(vararg items: Pair<String, Long>): ByteArray {
        var bytes = byteArrayOf()
        var at = 0L
        for ((clip, ticks) in items) {
            val body = ByteArray(20)
            (clip + "M2TS").toByteArray().copyInto(body, 0)
            for (i in 0..3) {
                body[12 + i] = (at shr (24 - 8 * i)).toByte()
                body[16 + i] = ((at + ticks) shr (24 - 8 * i)).toByte()
            }
            at += ticks
            bytes += byteArrayOf(0, 20) + body
        }
        val playlistStart = 16
        val data = ByteArray(playlistStart + 10 + bytes.size)
        "MPLS0200".toByteArray().copyInto(data, 0)
        data[11] = playlistStart.toByte()
        data[playlistStart + 4 + 2] = (items.size shr 8).toByte()
        data[playlistStart + 4 + 3] = items.size.toByte()
        bytes.copyInto(data, playlistStart + 10)
        return data
    }

    private val hour = 45_000L * 3600

    private val playlists = mapOf(
        "00000.mpls" to mpls("00001" to hour, "00002" to hour / 2),
        "00001.mpls" to mpls(*Array(40) { "00009" to hour / 5 }),
        "00002.mpls" to mpls("00003" to hour / 40),
        "00003.mpls" to mpls("00001" to hour)
    )

    private val clipInfo: ByteArray = run {
        val streams = listOf(0x1011 to "", 0x1100 to "eng", 0x1101 to "por", 0x1200 to "por", 0x1800 to "eng", 0x1A00 to "deu")
            .map { (pid, language) ->
                val info = when {
                    language.isEmpty() -> byteArrayOf(0x24, 0x61, 0, 0, 0)
                    pid in 0x1100..0x111F || pid >= 0x1A00 -> byteArrayOf(0x81.toByte(), 0x61) + language.toByteArray() + 0
                    else -> byteArrayOf(0x90.toByte()) + language.toByteArray() + 0
                }
                byteArrayOf((pid shr 8).toByte(), pid.toByte(), info.size.toByte()) + info
            }
        "HDMV0300".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 40) + ByteArray(24) +
            byteArrayOf(0, 0, 0, 0, 0, 1) + byteArrayOf(0, 0, 0, 0, 0x01, 0x00, streams.size.toByte(), 0) +
            streams.reduce { a, b -> a + b }
    }

    private fun command(operation: Int, options: Int, destination: Int) = byteArrayOf(
        operation.toByte(), options.toByte(), 0, 0,
        (destination shr 24).toByte(), (destination shr 16).toByte(), (destination shr 8).toByte(), destination.toByte(),
        0, 0, 0, 0
    )

    private fun movieObject(vararg objects: List<ByteArray>): ByteArray {
        var body = byteArrayOf()
        for (commands in objects) {
            body += byteArrayOf(0, 0, 0, commands.size.toByte()) + commands.fold(byteArrayOf()) { acc, it -> acc + it }
        }
        return "MOBJ0200".toByteArray() + ByteArray(32) + ByteArray(8) + byteArrayOf(0, objects.size.toByte()) + body
    }

    private val movieObject = movieObject(
        listOf(command(0x22, 0x80, 3), command(0x50, 0x80, 7)),
        listOf(command(0x22, 0x00, 1), command(0x22, 0x81, 0), command(0x22, 0x80, 3))
    )

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/flat/")) {
                    if (!path.endsWith("?token=abc")) return MockResponse().setResponseCode(403)
                    val file = path.removePrefix("/flat/BDMV/").removeSuffix("?token=abc")
                    if (file == "MovieObject.bdmv") return MockResponse().setBody(Buffer().write(movieObject))
                    val playlist = playlists[file.removePrefix("PLAYLIST/")]
                    return playlist?.takeIf { file.startsWith("PLAYLIST/") }?.let { MockResponse().setBody(Buffer().write(it)) }
                        ?: MockResponse().setResponseCode(404)
                }
                if (path == "/disc/BDMV/CLIPINF/00001.clpi") return MockResponse().setBody(Buffer().write(clipInfo))
                if (path == "/disc/BDMV/PLAYLIST/") {
                    return MockResponse().setBody(playlists.keys.joinToString("") { "<a href=\"$it\">$it</a>\n" })
                }
                val name = path.removePrefix("/disc/BDMV/PLAYLIST/")
                return playlists[name]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun probe(clip: String) =
        BlurayPlaylistResolver.probeClipPlaylist(server.url("/disc/BDMV/STREAM/$clip").toString(), emptyMap(), client)

    @Test fun `a clip that is part of its feature plays the feature playlist`() {
        val playlist = probe("00001.m2ts")!!
        assertEquals("00000.mpls", playlist.name)
        assertEquals(
            listOf(server.url("/disc/BDMV/STREAM/00001.m2ts").toString(), server.url("/disc/BDMV/STREAM/00002.m2ts").toString()),
            playlist.segmentUrls
        )
        assertEquals(listOf(3_600_000L, 1_800_000L), playlist.durationsMs)
    }

    @Test fun `a clip that is a whole title or outside every playlist plays alone`() {
        assertNull(probe("00003.m2ts"))
        assertNull(probe("00004.m2ts"))
        assertNull(probe("trailer.m2ts"))
    }

    @Test fun `a folder without playlists plays the clip alone`() {
        assertNull(
            BlurayPlaylistResolver.probeClipPlaylist(server.url("/other/BDMV/STREAM/00001.m2ts").toString(), emptyMap(), client)
        )
    }

    @Test fun `a disc offers its titles, longest first, without loops`() {
        val disc = BlurayPlaylistResolver.probeDisc(server.url("/disc/BDMV/STREAM/00001.m2ts").toString(), emptyMap(), client)!!
        assertEquals("00000.mpls", disc.currentTitle)
        assertEquals(listOf("00000.mpls", "00003.mpls", "00002.mpls"), disc.titles.map { it.name })
        assertEquals(listOf(5_400_000L, 3_600_000L, 90_000L), disc.titles.map { it.durationsMs.sum() })
    }

    @Test fun `a clip that is a whole title plays as that title`() {
        val disc = BlurayPlaylistResolver.probeDisc(server.url("/disc/BDMV/STREAM/00003.m2ts").toString(), emptyMap(), client)!!
        assertNull(disc.feature)
        assertEquals("00002.mpls", disc.currentTitle)
        assertEquals(listOf(server.url("/disc/BDMV/STREAM/00003.m2ts").toString()), disc.titles.last().segmentUrls)
    }

    @Test fun `a title's languages come from its first clip`() {
        val disc = BlurayPlaylistResolver.probeDisc(server.url("/disc/BDMV/STREAM/00001.m2ts").toString(), emptyMap(), client)!!
        assertEquals(
            listOf("eng", "por") to listOf("por", "eng"),
            BlurayPlaylistResolver.titleLanguages(disc.titles.first(), emptyMap(), client)
        )
        assertEquals(emptyList<String>() to emptyList<String>(), BlurayPlaylistResolver.titleLanguages(disc.titles.last(), emptyMap(), client))
    }

    @Test fun `titles shorter than a minute or repeating another are left out`() {
        val titles = BlurayPlaylistResolver.selectTitles(
            listOf(
                BlurayPlaylistResolver.parseMpls("00010.mpls", mpls("00001" to hour))!!,
                BlurayPlaylistResolver.parseMpls("00011.mpls", mpls("00001" to hour))!!,
                BlurayPlaylistResolver.parseMpls("00012.mpls", mpls("00004" to 45_000L * 30))!!
            )
        )
        assertEquals(listOf("00010.mpls"), titles.map { it.name })
    }

    @Test fun `an index of a disc folder plays its feature and offers its titles`() {
        val disc = BlurayPlaylistResolver.probeDiscEntry(server.url("/disc/BDMV/index.bdmv").toString(), emptyMap(), client)!!
        assertEquals("00000.mpls", disc.feature!!.name)
        assertEquals("00000.mpls", disc.currentTitle)
        assertEquals(
            listOf(server.url("/disc/BDMV/STREAM/00001.m2ts").toString(), server.url("/disc/BDMV/STREAM/00002.m2ts").toString()),
            disc.feature!!.segmentUrls
        )
        assertEquals(listOf("00000.mpls", "00003.mpls", "00002.mpls"), disc.titles.map { it.name })
    }

    @Test fun `a playlist plays itself, with the disc's other titles beside it`() {
        val disc = BlurayPlaylistResolver.probeDiscEntry(server.url("/disc/BDMV/PLAYLIST/00003.mpls").toString(), emptyMap(), client)!!
        assertEquals("00003.mpls", disc.feature!!.name)
        assertEquals("00003.mpls", disc.currentTitle)
        assertEquals(listOf(server.url("/disc/BDMV/STREAM/00001.m2ts").toString()), disc.feature!!.segmentUrls)
        assertEquals(3, disc.titles.size)
    }

    @Test fun `a host without folder listings finds the playlists in the movie objects`() {
        val disc = BlurayPlaylistResolver.probeDiscEntry(server.url("/flat/BDMV/index.bdmv?token=abc").toString(), emptyMap(), client)!!
        assertEquals("00000.mpls", disc.feature!!.name)
        assertEquals(
            listOf(server.url("/flat/BDMV/STREAM/00001.m2ts?token=abc").toString(), server.url("/flat/BDMV/STREAM/00002.m2ts?token=abc").toString()),
            disc.feature!!.segmentUrls
        )
        assertEquals(listOf("00000.mpls", "00003.mpls"), disc.titles.map { it.name })
    }

    @Test fun `movie objects name the playlists they play by number`() {
        assertEquals(listOf(3, 0), BlurayPlaylistResolver.parseMovieObjectPlaylists(movieObject))
        assertEquals(emptyList<Int>(), BlurayPlaylistResolver.parseMovieObjectPlaylists(ByteArray(64)))
    }

    @Test fun `disc entry links are an index, movie objects or a playlist`() {
        assertTrue(BlurayPlaylistResolver.isLikelyDiscEntryUrl("https://host/Movie/BDMV/index.bdmv"))
        assertTrue(BlurayPlaylistResolver.isLikelyDiscEntryUrl("https://host/Movie/BDMV/MovieObject.bdmv?token=1"))
        assertTrue(BlurayPlaylistResolver.isLikelyDiscEntryUrl("https://host/Movie/bdmv/playlist/00800.mpls"))
        assertFalse(BlurayPlaylistResolver.isLikelyDiscEntryUrl("https://host/Movie/BDMV/STREAM/00800.m2ts"))
        assertFalse(BlurayPlaylistResolver.isLikelyDiscEntryUrl("https://host/Movie/index.bdmv"))
        assertFalse(BlurayPlaylistResolver.isLikelyDiscEntryUrl("/storage/Movie/BDMV/index.bdmv"))
    }

    @Test fun `the feature is the longest playlist that is not a loop`() {
        val parsed = playlists.map { (name, data) -> BlurayPlaylistResolver.parseMpls(name, data)!! }
        assertEquals("00000.mpls", BlurayPlaylistResolver.selectMainPlaylist(parsed)!!.name)
    }
}
