package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.usenet.UsenetSidecar

internal enum class PlayerStreamType(val label: String) {
    P2P("P2P"),
    USENET("Usenet"),
    SERVER("Server"),
    CLOUD("Cloud"),
    LOCAL("Local"),
    DIRECT("Direct")
}

internal fun streamTypeOf(isTorrent: Boolean, isServer: Boolean, contentType: String?, url: String): PlayerStreamType = when {
    isTorrent -> PlayerStreamType.P2P
    isServer -> PlayerStreamType.SERVER
    contentType.equals("cloud", ignoreCase = true) -> PlayerStreamType.CLOUD
    url.startsWith("file:") || url.startsWith("content:") || url.startsWith("/") -> PlayerStreamType.LOCAL
    else -> PlayerStreamType.DIRECT
}

internal fun streamTypeLabel(type: PlayerStreamType, addonName: String?): String =
    if (type == PlayerStreamType.DIRECT && !addonName.isNullOrBlank()) "${type.label} · $addonName" else type.label

internal fun PlayerRuntimeController.currentStreamType(): PlayerStreamType =
    if (UsenetSidecar.isSessionUrl(currentStreamUrl)) PlayerStreamType.USENET
    else streamTypeOf(isTorrentStream, _uiState.value.isServerStream, contentType, currentStreamUrl)
