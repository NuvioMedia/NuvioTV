package com.nuvio.tv.domain.model

/** A single scheduled programme entry parsed from an XMLTV EPG feed. */
data class EpgProgramme(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long
)
