@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package com.nuvio.tv.core.image

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.os.Build
import coil3.network.ConnectivityChecker

internal fun legacyVpnConnectivityChecker(context: Context): ConnectivityChecker {
    val delegate = ConnectivityChecker(context)
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return delegate

    val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return delegate
    return LegacyVpnConnectivityChecker(connectivityManager, delegate)
}

/** Allows image downloads when an Android 7/8 VPN omits INTERNET despite a connected uplink. */
internal class LegacyVpnConnectivityChecker(
    private val connectivityManager: ConnectivityManager,
    private val delegate: ConnectivityChecker,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ConnectivityChecker {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun isOnline(): Boolean {
        if (delegate.isOnline()) return true
        if (sdkInt > Build.VERSION_CODES.O_MR1) return false

        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val activeCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!activeCapabilities.hasTransport(TRANSPORT_VPN)) return false

        // A split-tunnel VPN can be the active network without advertising INTERNET.
        // Only fall back when a non-VPN network actually has a connected internet link.
        return connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null &&
                !capabilities.hasTransport(TRANSPORT_VPN) &&
                capabilities.hasCapability(NET_CAPABILITY_INTERNET) &&
                connectivityManager.getNetworkInfo(network)?.isConnected == true
        }
    }
}
