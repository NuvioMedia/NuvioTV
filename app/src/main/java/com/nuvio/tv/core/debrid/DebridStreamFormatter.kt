package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.CompiledStreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamDebridCacheState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridStreamFormatter @Inject constructor(
    private val engine: DebridStreamTemplateEngine
) {
    fun format(
        stream: Stream,
        settings: DebridSettings,
        compiledBadgeFilters: List<CompiledStreamBadgeFilter> = emptyList()
    ): Stream {
        if (!stream.isManagedDebridForFormatting()) return stream
        val matchedBadges = StreamBadgeMatcher.matchedBadges(stream, compiledBadgeFilters)
        val values = buildValues(stream, settings, matchedBadges)
        val formattedName = engine.render(settings.streamNameTemplate, values)
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val formattedDescription = engine.render(settings.streamDescriptionTemplate, values)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

        return stream.copy(
            name = formattedName.ifBlank { stream.name ?: DebridProviders.instantName(stream.clientResolve?.service) },
            description = formattedDescription.ifBlank { stream.description ?: stream.title },
            badges = matchedBadges
        )
    }

    private fun buildValues(
        stream: Stream,
        settings: DebridSettings,
        matchedBadges: List<StreamBadge>
    ): Map<String, Any?> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val facts = DirectDebridStreamFilter.facts(stream, settings)
        val season = resolve?.season
        val episode = resolve?.episode
        val seasons = parsed?.seasons.orEmpty()
        val episodes = parsed?.episodes.orEmpty()
        val visualTags = buildList {
            addAll(parsed?.hdr.orEmpty())
            parsed?.bitDepth?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.ifEmpty { facts.visualTags.labelsExcludingUnknown { it.label } }
        val audioTags = parsed?.audio.orEmpty().ifEmpty { facts.audioTags.labelsExcludingUnknown { it.label } }
        val audioChannels = parsed?.channels.orEmpty().ifEmpty { facts.audioChannels.labelsExcludingUnknown { it.label } }
        val languages = parsed?.languages.orEmpty().ifEmpty { facts.languages.map { it.code }.filterNot { it == "unknown" } }
        val edition = parsed?.edition ?: buildEdition(parsed)
        val matchedBadgeNames = matchedBadges.map { it.name }

        return linkedMapOf(
            "stream.title" to (parsed?.parsedTitle ?: resolve?.title ?: stream.title),
            "stream.year" to parsed?.year,
            "stream.season" to season,
            "stream.episode" to episode,
            "stream.seasons" to seasons,
            "stream.episodes" to episodes,
            "stream.seasonEpisode" to buildSeasonEpisodeList(season, episode, seasons, episodes),
            "stream.formattedEpisodes" to formatEpisodes(episodes),
            "stream.formattedSeasons" to formatSeasons(seasons),
            "stream.resolution" to (parsed?.resolution ?: facts.resolution.labelUnlessUnknown()),
            "stream.library" to false,
            "stream.quality" to (parsed?.quality ?: facts.quality.labelUnlessUnknown()),
            "stream.visualTags" to visualTags,
            "stream.audioTags" to audioTags,
            "stream.audioChannels" to audioChannels,
            "stream.languages" to languages,
            "stream.languageEmojis" to languages.map { languageEmoji(it) },
            "stream.size" to (raw?.size ?: stream.behaviorHints?.videoSize ?: stream.debridCacheStatus?.cachedSize),
            "stream.folderSize" to raw?.folderSize,
            "stream.encode" to (parsed?.codec?.uppercase() ?: facts.encode.labelUnlessUnknown()),
            "stream.indexer" to (raw?.indexer ?: raw?.tracker),
            "stream.network" to (parsed?.network ?: raw?.network),
            "stream.releaseGroup" to parsed?.group,
            "stream.duration" to parsed?.duration,
            "stream.edition" to edition,
            "stream.filename" to (raw?.filename ?: resolve?.filename ?: stream.behaviorHints?.filename ?: stream.debridCacheStatus?.cachedName),
            "stream.regexMatched" to matchedBadgeNames,
            "stream.rseMatched" to matchedBadgeNames,
            "stream.type" to streamType(stream, resolve),
            "service.cached" to serviceCached(stream, resolve),
            "service.shortName" to serviceShortName(stream, resolve),
            "service.name" to serviceName(stream, resolve),
            "addon.name" to stream.addonName
        )
    }

    private fun streamType(stream: Stream, resolve: StreamClientResolve?): String {
        return when {
            stream.debridCacheStatus != null -> "Debrid"
            resolve?.type.equals("debrid", ignoreCase = true) -> "Debrid"
            resolve?.type.equals("torrent", ignoreCase = true) -> "p2p"
            else -> resolve?.type.orEmpty()
        }
    }

    private fun serviceCached(stream: Stream, resolve: StreamClientResolve?): Boolean? {
        return when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> true
            StreamDebridCacheState.NOT_CACHED -> false
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.UNKNOWN,
            null -> resolve?.isCached
        }
    }

    private fun serviceShortName(stream: Stream, resolve: StreamClientResolve?): String {
        val extension = resolve?.serviceExtension?.takeIf { it.isNotBlank() }
        if (extension != null) return extension
        return DebridProviders.shortName(serviceId(stream, resolve))
    }

    private fun serviceName(stream: Stream, resolve: StreamClientResolve?): String {
        return DebridProviders.displayName(serviceId(stream, resolve))
    }

    private fun serviceId(stream: Stream, resolve: StreamClientResolve?): String? =
        stream.debridCacheStatus?.providerId ?: resolve?.service

    private fun Stream.isManagedDebridForFormatting(): Boolean =
        isDirectDebrid() || debridCacheStatus != null

    private fun buildEdition(parsed: StreamClientResolveParsed?): String? {
        if (parsed == null) return null
        return buildList {
            if (parsed.extended == true) add("extended")
            if (parsed.theatrical == true) add("theatrical")
            if (parsed.remastered == true) add("remastered")
            if (parsed.unrated == true) add("unrated")
        }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun buildSeasonEpisodeList(
        season: Int?,
        episode: Int?,
        seasons: List<Int>,
        episodes: List<Int>
    ): List<String> {
        if (season != null && episode != null) return listOf("S${season.twoDigits()}E${episode.twoDigits()}")
        if (seasons.isEmpty() || episodes.isEmpty()) return emptyList()
        return seasons.flatMap { s -> episodes.map { e -> "S${s.twoDigits()}E${e.twoDigits()}" } }
    }

    private fun formatEpisodes(episodes: List<Int>): String {
        return episodes.joinToString(" • ") { "E${it.twoDigits()}" }
    }

    private fun formatSeasons(seasons: List<Int>): String {
        return seasons.joinToString(" • ") { "S${it.twoDigits()}" }
    }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')

    private fun DebridStreamResolution.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamResolution.UNKNOWN }

    private fun DebridStreamQuality.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamQuality.UNKNOWN }

    private fun DebridStreamEncode.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamEncode.UNKNOWN }

    private fun <T> List<T>.labelsExcludingUnknown(label: (T) -> String): List<String> =
        map(label).filterNot { it.equals("Unknown", ignoreCase = true) }

    private fun languageEmoji(language: String): String {
        return when (language.lowercase()) {
            "en", "eng", "english" -> "🇬🇧"
            "hi", "hin", "hindi" -> "🇮🇳"
            "ml", "mal", "malayalam" -> "🇮🇳"
            "ta", "tam", "tamil" -> "🇮🇳"
            "te", "tel", "telugu" -> "🇮🇳"
            "ja", "jpn", "japanese" -> "🇯🇵"
            "ko", "kor", "korean" -> "🇰🇷"
            "fr", "fre", "fra", "french" -> "🇫🇷"
            "es", "spa", "spanish" -> "🇪🇸"
            "de", "ger", "deu", "german" -> "🇩🇪"
            "it", "ita", "italian" -> "🇮🇹"
            "multi" -> "Multi"
            else -> language
        }
    }
}
