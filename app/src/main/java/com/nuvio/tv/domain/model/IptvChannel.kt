package com.nuvio.tv.domain.model

/** A single channel entry parsed from a user-supplied M3U/M3U8 playlist. */
data class IptvChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val streamUrl: String,
    val epgId: String?,
    val headers: Map<String, String>? = null
)
