package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import com.nuvio.tv.domain.model.Subtitle
import java.io.File

internal val LOCAL_SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

/** Language of picked files whose name carries no language token; the overlay lists them as "Local file". */
internal const val LOCAL_SUBTITLE_UNKNOWN_LANG = "local"

private val LOCAL_SUBTITLE_LANG_TOKEN = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?$")

/** Builds a [Subtitle] for a file picked from device storage; shown next to addon subtitles. */
internal fun PlayerRuntimeController.buildLocalSubtitle(path: String): Subtitle {
    val file = File(path)
    // "Movie.sr.srt" -> "sr"; anything else is listed as a plain local file.
    val langToken = file.nameWithoutExtension.substringAfterLast('.', "")
    val lang = langToken.takeIf { LOCAL_SUBTITLE_LANG_TOKEN.matches(it) } ?: LOCAL_SUBTITLE_UNKNOWN_LANG
    return Subtitle(
        id = file.name,
        url = android.net.Uri.fromFile(file).toString(),
        lang = lang,
        addonName = context.getString(R.string.subtitle_local_source),
        addonLogo = null
    )
}

internal fun PlayerRuntimeController.readLocalSubtitleBody(url: String, languageHint: String?): String {
    val path = android.net.Uri.parse(url).path ?: error("Invalid subtitle path")
    val bytes = File(path).readBytes()
    if (bytes.isEmpty()) {
        error(context.getString(R.string.subtitle_download_empty_content))
    }
    val body = SubtitleCharsetDetector.decode(bytes, languageHint = languageHint)
    if (body.isBlank()) {
        error(context.getString(R.string.subtitle_download_empty_content))
    }
    return body
}
