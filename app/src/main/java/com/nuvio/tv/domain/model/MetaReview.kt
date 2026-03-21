package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class MetaReview(
    val id: String,
    val author: String,
    val content: String,
    val rating: Double? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val url: String? = null
)
