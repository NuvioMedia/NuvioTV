package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

enum class MetaReviewSource {
    TMDB,
    TRAKT
}

enum class MetaReviewType {
    REVIEW,
    SHOUT
}

@Immutable
data class MetaReview(
    val id: String,
    val author: String,
    val content: String,
    val rating: Double? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val url: String? = null,
    val source: MetaReviewSource = MetaReviewSource.TMDB,
    val type: MetaReviewType = MetaReviewType.REVIEW,
    val hasSpoiler: Boolean = false
)
