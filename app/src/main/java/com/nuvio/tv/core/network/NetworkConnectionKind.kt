package com.nuvio.tv.core.network

internal enum class NetworkConnectionKind { Wifi, Ethernet, Offline }

internal enum class NetworkTransport { Wifi, Ethernet, Cellular, Vpn }

/**
 * Before Android 9 a VPN's capabilities do not include the underlying Wi-Fi or
 * Ethernet transport. The active network is then only [NetworkTransport.Vpn],
 * which would otherwise look offline. Use another reported network for the label.
 * Android 9 and later can put the underlying transport on the VPN itself.
 *
 * [others] is read only when the active network is a VPN with no physical transport.
 */
internal fun networkConnectionKind(
    active: Set<NetworkTransport>?,
    others: () -> List<Set<NetworkTransport>> = { emptyList() },
): NetworkConnectionKind {
    if (active == null) return NetworkConnectionKind.Offline
    physicalConnection(active)?.let { return it }
    if (NetworkTransport.Vpn !in active) return NetworkConnectionKind.Offline
    val underlying = others().mapNotNull { physicalConnection(it) }
    return underlying.firstOrNull { it == NetworkConnectionKind.Ethernet }
        ?: underlying.firstOrNull()
        ?: NetworkConnectionKind.Offline
}

private fun physicalConnection(transports: Set<NetworkTransport>): NetworkConnectionKind? {
    return when {
        NetworkTransport.Ethernet in transports -> NetworkConnectionKind.Ethernet
        NetworkTransport.Wifi in transports -> NetworkConnectionKind.Wifi
        NetworkTransport.Cellular in transports -> NetworkConnectionKind.Wifi
        else -> null
    }
}
