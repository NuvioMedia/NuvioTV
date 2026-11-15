package com.nuvio.tv.core.network

internal fun realtimeUrl(backendUrl: String): String {
    val base = backendUrl.trim().trimEnd('/')
    val socketBase = when {
        base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
        base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
        else -> base
    }
    return "$socketBase/realtime/v1?nuvio_realtime=1"
}
