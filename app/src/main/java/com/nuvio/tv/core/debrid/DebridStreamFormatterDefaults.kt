package com.nuvio.tv.core.debrid

object DebridStreamFormatterDefaults {
    const val NAME_TEMPLATE = "{stream.resolution::=2160p[\"4K \"||\"\"]}{stream.resolution::=1440p[\"QHD \"||\"\"]}{stream.resolution::=1080p[\"FHD \"||\"\"]}{stream.resolution::=720p[\"HD \"||\"\"]}{stream.resolution::exists[\"\"||\"Direct \"]}{service.shortName::exists[\"{service.shortName} \"||\"Debrid \"]}Instant"

    const val DESCRIPTION_TEMPLATE = "{stream.title::exists[\"{stream.title::title} \"||\"\"]}{stream.year::exists[\"({stream.year})\"||\"\"]}\n{stream.quality::exists[\"{stream.quality} \"||\"\"]}{stream.visualTags::exists[\"{stream.visualTags::join(' | ')} \"||\"\"]}{stream.encode::exists[\"{stream.encode} \"||\"\"]}\n{stream.audioTags::exists[\"{stream.audioTags::join(' | ')}\"||\"\"]}{stream.audioTags::exists::and::stream.audioChannels::exists[\" | \"||\"\"]}{stream.audioChannels::exists[\"{stream.audioChannels::join(' | ')}\"||\"\"]}\n{stream.size::>0[\"{stream.size::bytes} \"||\"\"]}{stream.releaseGroup::exists[\"{stream.releaseGroup} \"||\"\"]}{stream.indexer::exists[\"{stream.indexer}\"||\"\"]}\n{service.cached::istrue[\"Ready\"||\"Not Ready\"]}{service.shortName::exists[\" ({service.shortName})\"||\"\"]}{stream.filename::exists[\"\n{stream.filename}\"||\"\"]}"

    /** A preset of the web editor: the video on the name, then one line for each kind of detail. */
    val DETAILED_NAME_TEMPLATE = "▶ {stream.resolution::=2160p[\"4K\"||\"\"]}{stream.resolution::=1440p[\"QHD\"||\"\"]}" +
        "{stream.resolution::=1080p[\"FHD\"||\"\"]}{stream.resolution::=720p[\"HD\"||\"\"]}" +
        "{stream.quality::exists[\" {stream.quality}\"||\"\"]}{stream.visualTags::exists[\" {stream.visualTags::join(' ')}\"||\"\"]}" +
        "{stream.tier::exists[\" {stream.tier}\"||\"\"]}"

    val DETAILED_DESCRIPTION_TEMPLATE = listOf(
        "{stream.title::exists[\"🎬 {stream.title}\"||\"\"]}{stream.year::exists[\" ({stream.year})\"||\"\"]}" +
            "{stream.seasonEpisode::exists[\" {stream.seasonEpisode::join(' ')}\"||\"\"]}{stream.edition::exists[\" · {stream.edition}\"||\"\"]}",
        "{stream.quality::exists[\"📀 {stream.quality}\"||\"\"]}{stream.encode::exists[\" · {stream.encode}\"||\"\"]}" +
            "{stream.network::exists[\" · 📺 {stream.network}\"||\"\"]}",
        "{stream.audioTags::exists[\"🔊 {stream.audioTags::join(' ')}\"||\"\"]}" +
            "{stream.audioChannels::exists[\" {stream.audioChannels::join(' ')}\"||\"\"]}" +
            "{stream.languageEmojis::exists[\" 🌐 {stream.languageEmojis::join(' ')}\"||\"\"]}",
        "{stream.size::>0[\"📦 {stream.size::bytes}\"||\"\"]}{stream.bitrate::exists[\" · {stream.bitrate}\"||\"\"]}" +
            "{stream.age::exists[\" · ⏳ {stream.age}\"||\"\"]}",
        "{service.shortName::exists[\"⚡ {service.shortName}\"||\"\"]}{stream.indexer::exists[\" · 🔎 {stream.indexer}\"||\"\"]}" +
            "{stream.releaseGroup::exists[\" · 🏷️ {stream.releaseGroup}\"||\"\"]}"
    ).joinToString("\n")
}
