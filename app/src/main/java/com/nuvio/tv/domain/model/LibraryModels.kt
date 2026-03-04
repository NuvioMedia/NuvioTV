package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LibraryEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val addonBaseUrl: String?,
    val listKeys: Set<String> = emptySet(),
    val listedAt: Long = 0L,
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null
) {
    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(type),
            rawType = type,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres
        )
    }
}

enum class LibrarySourceMode {
    LOCAL,
    TRAKT
}

enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy {
            return entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
        }
    }
}

@Immutable
data class LibraryListTab(
    val key: String,
    val title: String,
    val type: Type,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null
) {
    enum class Type {
        WATCHLIST,
        PERSONAL
    }
}

@Immutable
data class ListMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap()
)

@Immutable
data class ListMembershipChanges(
    val desiredMembership: Map<String, Boolean>
)

@Immutable
data class LibraryEntryInput(
    val itemId: String,
    val itemType: String,
    val title: String,
    val year: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val poster: String? = null,
    val posterShape: PosterShape = PosterShape.POSTER,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null
)
