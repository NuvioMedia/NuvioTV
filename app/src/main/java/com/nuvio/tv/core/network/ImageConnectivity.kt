package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Coil's default checker only looks at [NetworkCapabilities.NET_CAPABILITY_INTERNET] on the
 * active network. A DNS VPN is that network and omits the capability, so Coil sends
 * `only-if-cached` and OkHttp answers HTTP 504. Treat a VPN transport as online. No active
 * network stays offline so a miss still fails locally instead of occupying the image dispatcher.
 */
internal fun imageNetworkIsOnline(
    activeNetworkPresent: Boolean,
    capabilitiesPresent: Boolean,
    hasInternet: Boolean,
    isVpn: Boolean,
): Boolean {
    if (!activeNetworkPresent || !capabilitiesPresent) return false
    return hasInternet || isVpn
}

@OptIn(ExperimentalCoilApi::class)
internal fun imageConnectivityChecker(context: Context): ConnectivityChecker {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return ConnectivityChecker.ONLINE
    return ConnectivityChecker {
        runCatching { manager.imageNetworkIsOnline() }.getOrDefault(true)
    }
}

@OptIn(ExperimentalCoilApi::class)
internal fun imageNetworkFetcherFactory(
    callFactory: () -> Call.Factory = ::OkHttpClient,
    cacheStrategy: () -> CacheStrategy = { CacheStrategy.DEFAULT },
) = OkHttpNetworkFetcherFactory(
    callFactory = callFactory,
    cacheStrategy = cacheStrategy,
    connectivityChecker = ::imageConnectivityChecker,
)

private fun ConnectivityManager.imageNetworkIsOnline(): Boolean {
    val network = activeNetwork
    val capabilities = network?.let { getNetworkCapabilities(it) }
    return imageNetworkIsOnline(
        activeNetworkPresent = network != null,
        capabilitiesPresent = capabilities != null,
        hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
    )
}
