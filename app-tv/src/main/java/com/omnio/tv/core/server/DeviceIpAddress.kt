package com.omnio.tv.core.server

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIpAddress {

    fun get(context: Context): String? {
        // Try WifiManager first
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiIp = wifiManager.connectionInfo.ipAddress
        if (wifiIp != 0) {
            return formatIp(wifiIp)
        }

        // Fallback: iterate NetworkInterface for Ethernet / other connections
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun formatIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
