package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedPresetTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val preset = DebridSettings(streamNameTemplate = DebridStreamFormatterDefaults.DETAILED_NAME_TEMPLATE,
        streamDescriptionTemplate = DebridStreamFormatterDefaults.DETAILED_DESCRIPTION_TEMPLATE)
    private val now = 1_800_000_000_000L

    private fun render(title: String, ageDays: Long, bitrate: String?, tier: String?): Pair<String, String> {
        val release = UsenetRelease(title, "nzb", size = 60L shl 30, publishedAt = now - ageDays * 86_400_000L,
            indexerName = "Indexer")
        return formatter.render(preset, UsenetResultValues.of(release, episodeSize = null, failed = false,
            languageEmoji = formatter::languageEmoji, now = now, bitrate = bitrate, tier = tier))
    }

    @Test fun `the detailed preset shows a film's fields line by line`() {
        val (name, description) = render("Movie.Title.2024.2160p.UHD.BluRay.REMUX.DV.HDR10Plus.HEVC.TrueHD.Atmos.7.1-FraMeSToR",
            ageDays = 3, bitrate = "72 Mbps", tier = "T1")
        assertTrue(name, name.startsWith("▶ 4K") && "DV" in name && name.endsWith("T1"))
        val lines = description.lines()
        assertTrue(description, lines.any { it.startsWith("🔊 TrueHD Atmos 7.1") })
        assertTrue(description, lines.any { it.startsWith("📦 ") && "72 Mbps" in it && "⏳ 3d" in it })
        assertTrue(description, lines.any { it.startsWith("⚡ Usenet") && "🔎 Indexer" in it && "🏷️ FraMeSToR" in it })
    }

    @Test fun `the detailed preset shows an episode and its network`() {
        val (_, description) = render("Show.Name.S01E01.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR10.H.265-NTb",
            ageDays = 12, bitrate = null, tier = null)
        assertTrue(description, description.lines().any { "S01E01" in it })
        assertTrue(description, description.lines().any { "📺 Amazon" in it })
    }
}
