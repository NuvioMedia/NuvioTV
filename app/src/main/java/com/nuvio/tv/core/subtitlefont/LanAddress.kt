package com.nuvio.tv.core.subtitlefont

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nuvio.tv.core.server.DeviceIpAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The TV's address on the home network, for the QR codes of the local upload page.
 *
 * Checks Android for the Wi-Fi or Ethernet network (excluding VPN tunnels) and falls back
 * to DeviceIpAddress lookup.
 */
internal object LanAddress {

    fun get(context: Context): String? =
        runCatching { fromConnectivity(context) }.getOrNull()
            ?: runCatching { fromInterfaces() }.getOrNull()
            ?: DeviceIpAddress.get(context)

    private fun fromConnectivity(context: Context): String? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        val candidates = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return candidates.asSequence()
            .distinct()
            .filter { isLocalNetwork(cm, it) }
            .mapNotNull { network -> cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.let(::pick) }
            .firstOrNull()
    }

    private fun isLocalNetwork(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun fromInterfaces(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && LAN_NAMES.any { name -> it.name.startsWith(name) } }
        .sortedBy { iface -> LAN_NAMES.indexOfFirst { iface.name.startsWith(it) } }
        .firstNotNullOfOrNull { pick(it.inetAddresses.toList()) }

    private fun pick(addresses: List<InetAddress>): String? {
        val v4 = addresses.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        return (v4.firstOrNull { it.isSiteLocalAddress } ?: v4.firstOrNull())?.hostAddress
    }

    private val LAN_NAMES = listOf("eth", "wlan")
}
