package com.nuvio.tv.domain.model

import kotlinx.serialization.Serializable

/** User-owned source configuration. URLs stay local to the active profile. */
@Serializable
data class LiveTvSource(
    val id: String,
    val name: String,
    val playlistUrl: String,
    val epgUrl: String? = null,
    val enabled: Boolean = true,
)
