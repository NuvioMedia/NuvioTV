@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package com.nuvio.tv.core.image

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkInfo
import coil3.network.ConnectivityChecker
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVpnConnectivityCheckerTest {
    private val connectivityManager = mockk<ConnectivityManager>()
    private val delegate = mockk<ConnectivityChecker>()
    private val vpn = mockk<Network>()
    private val ethernet = mockk<Network>()

    private fun checker(sdkInt: Int = 26) =
        LegacyVpnConnectivityChecker(connectivityManager, delegate, sdkInt)

    private fun configureVpn(connected: Boolean = true, hasInternet: Boolean = true) {
        every { delegate.isOnline() } returns false
        every { connectivityManager.activeNetwork } returns vpn
        val vpnCapabilities = mockk<NetworkCapabilities>()
        every { vpnCapabilities.hasTransport(TRANSPORT_VPN) } returns true
        every { connectivityManager.getNetworkCapabilities(vpn) } returns vpnCapabilities
        every { connectivityManager.allNetworks } returns arrayOf(vpn, ethernet)
        val ethernetCapabilities = mockk<NetworkCapabilities>()
        every { ethernetCapabilities.hasTransport(TRANSPORT_VPN) } returns false
        every { ethernetCapabilities.hasCapability(NET_CAPABILITY_INTERNET) } returns hasInternet
        every { connectivityManager.getNetworkCapabilities(ethernet) } returns ethernetCapabilities
        val info = mockk<NetworkInfo>()
        every { info.isConnected } returns connected
        every { connectivityManager.getNetworkInfo(ethernet) } returns info
    }

    @Test
    fun preservesOnlineResultWithoutProbingOtherNetworks() {
        every { delegate.isOnline() } returns true

        assertTrue(checker().isOnline())
        verify { connectivityManager wasNot Called }
    }

    @Test
    fun android8VpnWithConnectedInternetUplinkIsOnline() {
        configureVpn()

        assertTrue(checker().isOnline())
    }

    @Test
    fun android7And81AlsoUseTheLegacyFallback() {
        configureVpn()

        assertTrue(checker(sdkInt = 24).isOnline())
        assertTrue(checker(sdkInt = 27).isOnline())
    }

    @Test
    fun modernAndroidPreservesTheDefaultOfflineVerdict() {
        every { delegate.isOnline() } returns false

        assertFalse(checker(sdkInt = 28).isOnline())
        verify { connectivityManager wasNot Called }
    }

    @Test
    fun disconnectedUnderlyingNetworkDoesNotCountAsOnline() {
        configureVpn(connected = false)

        assertFalse(checker().isOnline())
    }

    @Test
    fun localOnlyUnderlyingNetworkDoesNotCountAsOnline() {
        configureVpn(hasInternet = false)

        assertFalse(checker().isOnline())
    }

    @Test
    fun missingUnderlyingConnectionInfoDoesNotCountAsOnline() {
        configureVpn()
        every { connectivityManager.getNetworkInfo(ethernet) } returns null

        assertFalse(checker().isOnline())
    }

    @Test
    fun anotherVpnDoesNotCountAsAnUnderlyingNetwork() {
        configureVpn()
        every { connectivityManager.allNetworks } returns arrayOf(vpn)

        assertFalse(checker().isOnline())
    }

    @Test
    fun missingActiveNetworkStaysOffline() {
        every { delegate.isOnline() } returns false
        every { connectivityManager.activeNetwork } returns null

        assertFalse(checker().isOnline())
    }

    @Test
    fun missingActiveCapabilitiesStaysOffline() {
        configureVpn()
        every { connectivityManager.getNetworkCapabilities(vpn) } returns null

        assertFalse(checker().isOnline())
    }

    @Test
    fun nonVpnOfflineNetworkDoesNotTriggerTheWorkaround() {
        configureVpn()
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasTransport(TRANSPORT_VPN) } returns false
        every { connectivityManager.getNetworkCapabilities(vpn) } returns capabilities

        assertFalse(checker().isOnline())
        verify(exactly = 0) { connectivityManager.allNetworks }
    }
}
