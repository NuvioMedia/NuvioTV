package com.nuvio.tv.core.torrent

import com.nuvio.engine.NuvioEngineStats
import com.nuvio.engine.NuvioStreamStats

internal const val TorrentDiagnosticTag = "NuvioP2PDiag"

internal fun diagnosticId(value: String?): String =
    value?.trim()?.take(12)?.ifBlank { "none" } ?: "none"

internal fun diagnosticMessage(value: String?): String =
    value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

internal fun NuvioEngineStats.diagnosticSummary(): String =
    "http=$activeHttpRequests pendingReads=$pendingPieceReads " +
        "peers=$connectedPeers seeds=$connectedSeeds known=$knownPeers " +
        "downloading=$downloadingPeers downBps=$downloadRateBytesPerSecond " +
        "upBps=$uploadRateBytesPerSecond payloadDown=$totalPayloadDownloadBytes " +
        "activeTorrents=$activeTorrents activeStreams=$activeStreams " +
        "diskUsed=$diskCacheUsedBytes diskProtected=$diskCacheProtectedBytes"

internal fun NuvioStreamStats?.diagnosticSummary(): String =
    if (this == null) {
        "route=none"
    } else {
        "contiguous=$contiguousReadyBytes verified=$verifiedFileBytes delivered=$deliveredBytes " +
            "demands=$activeDemands scheduledPieces=$scheduledPieces blockingPieces=$blockingPieces"
    }
