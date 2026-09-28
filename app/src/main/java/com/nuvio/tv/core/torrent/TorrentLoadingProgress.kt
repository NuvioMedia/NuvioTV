package com.nuvio.tv.core.torrent

internal const val TorrentInitialByteProgressMidpoint = 5_242_880L
private const val TorrentInitialBufferTargetMs = 10_000L
private const val TorrentInitialNetworkStageWeight = 0.45f
private const val TorrentInitialDeliveryStageWeight = 0.30f
private const val TorrentInitialPlayerStageStart = 0.75f
private const val TorrentInitialLoadingMaximum = 0.95f

internal fun torrentInitialLoadingProgress(
    bufferedAheadMs: Long,
    downloadedBytes: Long,
    deliveredBytes: Long
): Float {
    val networkProgress = saturatingProgress(downloadedBytes, TorrentInitialByteProgressMidpoint) *
        TorrentInitialNetworkStageWeight
    val deliveryProgress = saturatingProgress(deliveredBytes, TorrentInitialByteProgressMidpoint) *
        TorrentInitialDeliveryStageWeight
    val engineProgress = networkProgress + deliveryProgress
    val playerProgress = if (bufferedAheadMs > 0L) {
        TorrentInitialPlayerStageStart +
            (bufferedAheadMs.toFloat() / TorrentInitialBufferTargetMs.toFloat()).coerceIn(0f, 1f) *
            (TorrentInitialLoadingMaximum - TorrentInitialPlayerStageStart)
    } else {
        0f
    }
    return maxOf(engineProgress, playerProgress).coerceIn(0f, TorrentInitialLoadingMaximum)
}

private fun saturatingProgress(value: Long, midpoint: Long): Float {
    val safeValue = value.coerceAtLeast(0L).toDouble()
    return (safeValue / (safeValue + midpoint.toDouble())).toFloat()
}
