package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class TraktCommentReview(
    val id: Long,
    val authorDisplayName: String,
    val authorUsername: String? = null,
    val comment: String,
    val spoiler: Boolean = false,
    val containsInlineSpoilers: Boolean = false,
    val review: Boolean = false,
    val likes: Int = 0,
    val rating: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    val hasSpoilerContent: Boolean
        get() = spoiler || containsInlineSpoilers
}
