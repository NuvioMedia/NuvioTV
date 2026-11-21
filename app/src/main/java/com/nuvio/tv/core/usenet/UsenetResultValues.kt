package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.debrid.DebridTemplateBytes
import com.nuvio.tv.core.streams.StreamRankingFacts
import com.nuvio.tv.core.streams.StreamReleaseQuality
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import java.util.Locale

/**
 * The fields a built-in result gives the stream formatter, under the names and labels a debrid
 * stream uses, so the same templates show both. Besides its size, date and indexer a release has
 * only its name: the rest is read from the name, the tags from after the title, so that words of the
 * title are not taken for tags.
 */
internal object UsenetResultValues {
    /**
     * [episodeSize] is an episode's share of a season pack, null for any other release. [bitrate] and
     * [tier] are the result's, as its own text shows them ("72 Mbps", "T1"); null when unknown.
     */
    fun of(release: UsenetRelease, episodeSize: Long?, failed: Boolean, languageEmoji: (String) -> String,
        now: Long = System.currentTimeMillis(), bitrate: String? = null, tier: String? = null): Map<String, Any?> {
        val name = parse(release.title)
        val tags = name.rest
        val facts = release.rankingFacts
        val episodes = name.episodes
        val season = name.season ?: release.packSeasons.singleOrNull()
        val seasons = release.packSeasons.ifEmpty { listOfNotNull(season) }
        val languages = if (tags.isEmpty()) emptyList() else StreamRankingFacts.languagesOf(tags).toList()
        val size = episodeSize ?: release.size
        return linkedMapOf(
            "stream.title" to name.title,
            "stream.year" to name.year,
            "stream.season" to season,
            "stream.episode" to episodes.firstOrNull(),
            "stream.seasons" to seasons,
            "stream.episodes" to episodes,
            "stream.seasonEpisode" to (season?.let { s -> episodes.map { "S%02dE%02d".format(Locale.ROOT, s, it) } } ?: emptyList()),
            "stream.formattedEpisodes" to episodes.joinToString(" • ") { "E%02d".format(Locale.ROOT, it) },
            "stream.formattedSeasons" to seasons.joinToString(" • ") { "S%02d".format(Locale.ROOT, it) },
            "stream.resolution" to release.resolution.takeIf { it > 0 }?.let { "${it}p" },
            "stream.library" to false,
            "stream.quality" to quality(facts.quality),
            "stream.visualTags" to visualTags(tags),
            "stream.audioTags" to audioTags(tags),
            "stream.audioChannels" to audioChannels(tags),
            "stream.languages" to languages,
            "stream.languageEmojis" to languages.map(languageEmoji),
            "stream.size" to size.takeIf { it > 0 }?.let(::DebridTemplateBytes),
            "stream.folderSize" to release.size.takeIf { episodeSize != null && it > 0 }?.let(::DebridTemplateBytes),
            "stream.encode" to facts.codec.takeIf { it != "UNKNOWN" },
            "stream.indexer" to release.indexerName.takeIf { it.isNotBlank() },
            "stream.network" to NETWORKS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(tags) }?.first,
            // Without what reposts add after it: ROEN, not xpost.
            "stream.releaseGroup" to release.labels.group,
            "stream.duration" to null,
            "stream.edition" to EDITIONS.filter { (_, pattern) -> pattern.containsMatchIn(tags) }.map { it.first }
                .takeIf { it.isNotEmpty() }?.joinToString(" "),
            "stream.filename" to release.title,
            "stream.age" to release.publishedAt.takeIf { it > 0 }?.let { "${((now - it) / 86_400_000).coerceAtLeast(0)}d" },
            "stream.bitrate" to bitrate,
            "stream.tier" to tier,
            "stream.regexMatched" to emptyList<String>(),
            "stream.rseMatched" to emptyList<String>(),
            "stream.type" to "usenet",
            // Any release streams at once unless it already failed for good with these providers.
            "service.cached" to !failed,
            "service.shortName" to SERVICE_NAME,
            "service.name" to SERVICE_NAME,
            "addon.name" to BuiltInUsenetService.GROUP_NAME
        )
    }

    private class Name(val title: String?, val year: Int?, val season: Int?, val episodes: List<Int>, val rest: String)

    /**
     * The title ends where the year, the episode or the first quality tag starts. Of the years before
     * that tag the last is the release year, so a title that holds a year keeps it.
     */
    private fun parse(release: String): Name {
        val text = EXTENSION.replace(release.take(2048), "")
        val start = ANIME_GROUP.find(text)?.range?.last?.plus(1) ?: 0
        val tag = TAG.find(text, start)?.range?.first ?: text.length
        val year = YEAR.findAll(text, start).filter { it.range.first in (start + 1) until tag }.lastOrNull()
        val episode = EPISODE.find(text, start)?.takeIf { it.range.first > start }
        val season = SEASON.find(text, start)?.takeIf { it.range.first > start }
        val cut = listOfNotNull(tag, year?.range?.first, episode?.range?.first, season?.range?.first).min()
        // Nothing marks where the title ends: every tag is read from the whole name.
        if (cut >= text.length) return Name(null, null, null, emptyList(), text)
        val title = SEPARATORS.replace(text.substring(start, cut), " ").trim(' ', '-', '(', '[').takeIf { it.isNotEmpty() }
        val episodes = episode?.let { match ->
            listOf(match.groupValues[2].toInt()) + NUMBER.findAll(match.groupValues[3]).map { it.value.toInt() }
        }.orEmpty()
        return Name(title, year?.groupValues?.get(1)?.toInt(), episode?.groupValues?.get(1)?.toInt(), episodes, text.substring(cut))
    }

    private fun quality(quality: StreamReleaseQuality): String? = when (quality) {
        StreamReleaseQuality.REMUX -> DebridStreamQuality.BLURAY_REMUX.label
        StreamReleaseQuality.BLURAY -> DebridStreamQuality.BLURAY.label
        StreamReleaseQuality.WEB_DL -> DebridStreamQuality.WEB_DL.label
        StreamReleaseQuality.WEBRIP -> DebridStreamQuality.WEBRIP.label
        StreamReleaseQuality.HDTV -> DebridStreamQuality.HDTV.label
        StreamReleaseQuality.DVD -> DebridStreamQuality.DVDRIP.label
        StreamReleaseQuality.CAM -> DebridStreamQuality.CAM.label
        StreamReleaseQuality.UNKNOWN -> null
    }

    /** One label per format: HDR10+ implies HDR10, and either implies HDR. */
    private fun visualTags(tags: String): List<String> {
        val plus = HDR10_PLUS.containsMatchIn(tags)
        val hdr10 = !plus && HDR10.containsMatchIn(tags)
        return listOfNotNull(
            DebridStreamVisualTag.DV.takeIf { DV.containsMatchIn(tags) },
            DebridStreamVisualTag.HDR10_PLUS.takeIf { plus },
            DebridStreamVisualTag.HDR10.takeIf { hdr10 },
            DebridStreamVisualTag.HDR.takeIf { !plus && !hdr10 && HDR.containsMatchIn(tags) },
            DebridStreamVisualTag.HLG.takeIf { HLG.containsMatchIn(tags) },
            DebridStreamVisualTag.TEN_BIT.takeIf { TEN_BIT.containsMatchIn(tags) },
            DebridStreamVisualTag.IMAX.takeIf { IMAX.containsMatchIn(tags) },
            DebridStreamVisualTag.THREE_D.takeIf { THREE_D.containsMatchIn(tags) },
            DebridStreamVisualTag.SDR.takeIf { SDR.containsMatchIn(tags) }
        ).map { it.label }
    }

    /** One DTS and one Dolby Digital label each, the most specific: DTS-HD MA also reads as DTS. */
    private fun audioTags(tags: String): List<String> {
        val found = AUDIO.filter { (_, pattern) -> pattern.containsMatchIn(tags) }.map { it.first }
        val dts = found.firstOrNull { it in DTS_FAMILY }
        return found.filterNot { (it in DTS_FAMILY && it != dts) || (it == DebridStreamAudioTag.DD && DebridStreamAudioTag.DD_PLUS in found) }
            .map { it.label }
    }

    private fun audioChannels(tags: String): List<String> {
        val found = CHANNELS.findAll(tags).mapNotNull { CHANNEL_LABELS[it.groupValues[1] + it.groupValues[2]] }.toSet()
        return DebridStreamAudioChannel.defaultOrder.filter { it in found }.map { it.label }
    }

    private const val SERVICE_NAME = "Usenet"
    /** Channels written onto a codec, such as DDP5.1 or AAC2.0. */
    private const val CH = "(?:[1-9][ ._]?[0-2])?"

    private fun token(value: String) = Regex("(?:^|[^a-z0-9])(?:$value)(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)
    private fun bounded(value: String) = Regex("(?<![0-9a-z])(?:$value)(?![0-9a-z])", RegexOption.IGNORE_CASE)

    private val EXTENSION = Regex("\\.(?:mkv|mp4|m2ts|ts|nzb)$", RegexOption.IGNORE_CASE)
    private val ANIME_GROUP = Regex("^\\[[^\\]]{1,40}\\][ ._]*")
    private val TAG = bounded("[0-9]{3,4}p|4k|uhd|remux|blu[ ._-]?ray|bdrip|brrip|web[ ._-]?dl|web[ ._-]?rip|hdtv|dvdrip|hdrip")
    private val YEAR = Regex("(?<![0-9a-z])((?:19|20)[0-9]{2})(?![0-9a-z])", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("(?<![0-9a-z])s([0-9]{1,2})[ ._-]?e([0-9]{1,3})((?:[ ._-]?e[0-9]{1,3})*)(?![0-9])", RegexOption.IGNORE_CASE)
    private val SEASON = bounded("s[0-9]{1,2}|season[ ._-]?[0-9]{1,2}")
    private val SEPARATORS = Regex("[._]+")
    private val NUMBER = Regex("[0-9]+")

    private val DV = token("dv|dovi|dolby[ ._-]?vision")
    private val HDR10_PLUS = token("hdr10(?:\\+|plus|p)")
    private val HDR10 = token("hdr10")
    private val HDR = token("hdr")
    private val HLG = token("hlg")
    private val TEN_BIT = token("10[ ._-]?bit")
    private val IMAX = token("imax")
    private val THREE_D = token("3d")
    private val SDR = token("sdr")

    private val DTS_FAMILY = setOf(DebridStreamAudioTag.DTS_X, DebridStreamAudioTag.DTS_HD_MA, DebridStreamAudioTag.DTS_HD,
        DebridStreamAudioTag.DTS_ES, DebridStreamAudioTag.DTS)
    /** In the order they are shown: the codec, then Atmos. */
    private val AUDIO = listOf(
        DebridStreamAudioTag.DTS_X to token("dts[ ._:-]?x$CH"),
        DebridStreamAudioTag.DTS_HD_MA to token("dts[ ._-]?hd[ ._-]?ma$CH"),
        DebridStreamAudioTag.DTS_HD to token("dts[ ._-]?hd(?:[ ._-]?hra?)?$CH"),
        DebridStreamAudioTag.DTS_ES to token("dts[ ._-]?es$CH"),
        DebridStreamAudioTag.DTS to token("dts$CH"),
        DebridStreamAudioTag.TRUEHD to token("true[ ._-]?hd$CH"),
        DebridStreamAudioTag.DD_PLUS to token("ddp$CH|dd\\+$CH|e[ ._-]?ac[ ._-]?3$CH"),
        DebridStreamAudioTag.DD to token("dd$CH|ac[ ._-]?3$CH"),
        DebridStreamAudioTag.FLAC to token("flac$CH"),
        DebridStreamAudioTag.OPUS to token("opus$CH"),
        DebridStreamAudioTag.AAC to token("aac$CH"),
        DebridStreamAudioTag.ATMOS to token("atmos")
    )
    private val CHANNELS = Regex("(?<![0-9])([7652])[ ._]([01])(?![0-9])")
    private val CHANNEL_LABELS = mapOf("71" to DebridStreamAudioChannel.CH_7_1, "61" to DebridStreamAudioChannel.CH_6_1,
        "51" to DebridStreamAudioChannel.CH_5_1, "20" to DebridStreamAudioChannel.CH_2_0)

    private val EDITIONS = listOf(
        "Extended" to token("extended"),
        "Theatrical" to token("theatrical"),
        "Director's Cut" to token("director'?s?[ ._-]?cut"),
        "Remastered" to token("remastered"),
        "Unrated" to token("unrated"),
        "Uncut" to token("uncut"),
        "Criterion" to token("criterion")
    )
    private val NETWORKS = listOf(
        "Amazon" to bounded("amzn"), "Netflix" to bounded("nf"), "Disney+" to bounded("dsnp"),
        "Apple TV+" to bounded("atvp"), "HBO Max" to bounded("hmax"), "Max" to bounded("max"), "Hulu" to bounded("hulu"),
        "Peacock" to bounded("pcok"), "Paramount+" to bounded("pmtp"), "Stan" to bounded("stan"), "Crave" to bounded("crav")
    )
}
