package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetResultValuesTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val now = 1_700_000_000_000L

    private fun values(title: String, size: Long = 0, publishedAt: Long = 0, episodeSize: Long? = null, failed: Boolean = false) =
        UsenetResultValues.of(UsenetRelease(title, "https://a.test/nzb/1", size = size, publishedAt = publishedAt, indexerName = "A"),
            episodeSize, failed, formatter::languageEmoji, now)

    @Test fun `a remux gives its title, year and every tag under the debrid labels`() {
        val v = values("Movie.Name.2021.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-FraMeSToR",
            size = 50L shl 30, publishedAt = now - 3 * 86_400_000L)
        assertEquals("Movie Name", v["stream.title"])
        assertEquals(2021, v["stream.year"])
        assertEquals("2160p", v["stream.resolution"])
        assertEquals("BluRay REMUX", v["stream.quality"])
        assertEquals(listOf("DV", "HDR10"), v["stream.visualTags"])
        assertEquals(listOf("TrueHD", "Atmos"), v["stream.audioTags"])
        assertEquals(listOf("7.1"), v["stream.audioChannels"])
        assertEquals("HEVC", v["stream.encode"])
        assertEquals("FraMeSToR", v["stream.releaseGroup"])
        assertEquals("A", v["stream.indexer"])
        assertEquals("3d", v["stream.age"])
        assertEquals(true, v["service.cached"])
    }

    @Test fun `codecs with their channels written on, as WEB releases spell them, are read`() {
        val v = values("Show.Name.S02E05E06.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb")
        assertEquals("Show Name", v["stream.title"])
        assertEquals(2, v["stream.season"])
        assertEquals(listOf(5, 6), v["stream.episodes"])
        assertEquals(listOf("S02E05", "S02E06"), v["stream.seasonEpisode"])
        assertEquals("Amazon", v["stream.network"])
        assertEquals("WEB-DL", v["stream.quality"])
        assertEquals(listOf("DD+"), v["stream.audioTags"])
        assertEquals(listOf("5.1"), v["stream.audioChannels"])
        assertEquals("AVC", v["stream.encode"])
        assertEquals(emptyList<String>(), v["stream.visualTags"])
        assertEquals(listOf("AAC"), values("Movie.2020.720p.WEB.AAC2.0.x264-GRP")["stream.audioTags"])
        assertEquals(listOf("2.0"), values("Movie.2020.720p.WEB.AAC2.0.x264-GRP")["stream.audioChannels"])
    }

    @Test fun `only the most specific DTS and Dolby Digital labels are given`() {
        assertEquals(listOf("DTS-HD MA"), values("Movie.2019.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP")["stream.audioTags"])
        assertEquals(listOf("DD+", "Atmos"), values("Movie.2019.2160p.WEB-DL.DD+5.1.Atmos.DV.HDR10Plus.H.265-GRP")["stream.audioTags"])
        assertEquals(listOf("DV", "HDR10+"), values("Movie.2019.2160p.WEB-DL.DD+5.1.Atmos.DV.HDR10Plus.H.265-GRP")["stream.visualTags"])
        assertEquals(listOf("HDR", "10bit"), values("Movie.2019.2160p.WEB-DL.HDR.10bit.x265-GRP")["stream.visualTags"])
    }

    @Test fun `a year in the title stays in the title, and title words are not read as tags`() {
        val runner = values("Blade.Runner.2049.2017.1080p.BluRay.x264-GRP")
        assertEquals("Blade Runner 2049", runner["stream.title"])
        assertEquals(2017, runner["stream.year"])
        val year = values("1917.2019.1080p.BluRay.x264-GRP")
        assertEquals("1917", year["stream.title"])
        assertEquals(2019, year["stream.year"])
        val french = values("The.French.Dispatch.2021.1080p.WEB-DL.DDP5.1.x264-GRP")
        assertEquals(emptyList<String>(), french["stream.languages"])
        assertEquals(listOf("fr"), values("Movie.2021.FRENCH.1080p.WEB-DL.x264-GRP")["stream.languages"])
        assertEquals(listOf("Extended", "Remastered"), values("Movie.2001.Extended.Remastered.1080p.BluRay.x264-GRP")["stream.edition"]
            .toString().split(" "))
    }

    @Test fun `a season pack gives its seasons and an episode's share as its size`() {
        val v = values("Show.S01.2160p.WEB-DL.DV.HDR.DDP5.1.H.265-GRP", size = 40L shl 30, episodeSize = 4L shl 30)
        assertEquals(1, v["stream.season"])
        assertEquals(listOf(1), v["stream.seasons"])
        assertEquals("S01", v["stream.formattedSeasons"])
        assertEquals(emptyList<Int>(), v["stream.episodes"])
        assertEquals("4 GB", formatter.render(DebridSettings(streamNameTemplate = "{stream.size::bytes}",
            streamDescriptionTemplate = "{stream.folderSize::bytes}"), v).first)
    }

    @Test fun `a name with nothing that ends the title keeps every tag and no title`() {
        val v = values("Movie.DV.HEVC-GRP")
        assertNull(v["stream.title"])
        assertEquals(listOf("DV"), v["stream.visualTags"])
    }

    @Test fun `the debrid default templates show a release`() {
        val title = "Movie.Name.2021.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-FraMeSToR"
        val (name, description) = formatter.render(DebridSettings(), values(title, size = 50L shl 30))
        assertEquals("4K Usenet Instant", name)
        assertEquals(listOf("Movie Name (2021)", "BluRay REMUX DV | HDR10 HEVC", "TrueHD | Atmos | 7.1", "50 GB FraMeSToR A",
            "Ready (Usenet)", title), description.lines())
        assertEquals("Not Ready (Usenet)", formatter.render(DebridSettings(), values(title, failed = true)).second.lines()[4])
    }
}
