package com.nuvio.tv.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageConnectivityTest {

    @Test
    fun `no active network stays offline`() {
        assertFalse(
            imageNetworkIsOnline(
                activeNetworkPresent = false,
                capabilitiesPresent = false,
                hasInternet = false,
                isVpn = false,
            ),
        )
    }

    @Test
    fun `missing capabilities stay offline`() {
        assertFalse(
            imageNetworkIsOnline(
                activeNetworkPresent = true,
                capabilitiesPresent = false,
                hasInternet = false,
                isVpn = false,
            ),
        )
    }

    @Test
    fun `wifi internet is online`() {
        assertTrue(
            imageNetworkIsOnline(
                activeNetworkPresent = true,
                capabilitiesPresent = true,
                hasInternet = true,
                isVpn = false,
            ),
        )
    }

    @Test
    fun `dns vpn without internet capability is online`() {
        assertTrue(
            imageNetworkIsOnline(
                activeNetworkPresent = true,
                capabilitiesPresent = true,
                hasInternet = false,
                isVpn = true,
            ),
        )
    }

    @Test
    fun `connected network without internet or vpn is offline`() {
        assertFalse(
            imageNetworkIsOnline(
                activeNetworkPresent = true,
                capabilitiesPresent = true,
                hasInternet = false,
                isVpn = false,
            ),
        )
    }
}
