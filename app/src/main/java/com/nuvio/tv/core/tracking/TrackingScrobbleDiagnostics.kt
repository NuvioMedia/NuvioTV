package com.nuvio.tv.core.tracking

internal const val TRACKING_SCROBBLE_DIAGNOSTIC_TAG = "SimklScrobbleDiag"

internal fun TrackingMediaReference.scrobbleDiagnosticIdentity(): String {
    val episodeIdentity = episode?.let { value ->
        "season=${value.season ?: "unknown"} episode=${value.number}"
    } ?: "movie"
    return "media=$stableKey kind=$kind $episodeIdentity"
}

internal fun TrackingScrobbleEvent.scrobbleDiagnosticSummary(): String =
    "${media.scrobbleDiagnosticIdentity()} progress=$progressPercent"
