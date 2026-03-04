package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class HiddenItemEntry(
    val itemId: String,
    val itemType: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape = PosterShape.POSTER,
    val hiddenAt: Long = 0L
) {
    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = itemId,
            type = ContentType.fromString(itemType),
            rawType = itemType,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }
}
