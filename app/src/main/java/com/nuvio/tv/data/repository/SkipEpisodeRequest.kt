package com.nuvio.tv.data.repository

internal data class SkipEpisodeRequest(
    val source: String,
    val id: String,
    val season: Int?,
    val episode: Int,
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null
) {
    val cacheKey: String get() = "$source:$id:$season:$episode:$imdbId:$imdbSeason:$imdbEpisode"

    companion object {
        fun from(contentId: String?, videoId: String?, season: Int?, episode: Int?): SkipEpisodeRequest? {
            val parts = (videoId?.takeIf { it.isNotBlank() } ?: contentId ?: return null).split(':')
            val contentParts = contentId?.split(':').orEmpty()
            val contentImdb = contentParts.firstOrNull()?.takeIf { it.matches(Regex("tt[0-9]+")) }
            return when (parts.firstOrNull()) {
                "mal", "kitsu" -> {
                    val id = parts.getOrNull(1)?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return null
                    val number = if (parts.size == 3) parts[2].toIntOrNull() else if (parts.size == 2) episode else null
                    if (number == null || number <= 0) return null
                    // A MAL/Kitsu list's display season is not necessarily the IMDb season.
                    val imdbSeason = contentParts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }
                    val imdbEpisode = contentParts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
                    val hasCoordinates = contentImdb != null && contentParts.size == 3 && imdbSeason != null && imdbEpisode != null
                    SkipEpisodeRequest(parts[0], id, null, number, contentImdb,
                        imdbSeason.takeIf { hasCoordinates }, imdbEpisode.takeIf { hasCoordinates })
                }
                else -> {
                    val id = parts[0].takeIf { it.matches(Regex("tt[0-9]+")) } ?: return null
                    // Addons can display absolute numbering while the video ID contains seasonal coordinates.
                    val s = if (parts.size == 3) parts[1].toIntOrNull() else if (parts.size == 1) season else null
                    val e = if (parts.size == 3) parts[2].toIntOrNull() else if (parts.size == 1) episode else null
                    if (s == null || s < 0 || e == null || e <= 0) return null
                    SkipEpisodeRequest("imdb", id, s, e)
                }
            }
        }
    }
}
