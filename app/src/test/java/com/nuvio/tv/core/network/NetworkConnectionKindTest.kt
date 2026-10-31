package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkConnectionKindTest {

    @Test
    fun `no active network is offline`() {
        assertEquals(NetworkConnectionKind.Offline, networkConnectionKind(active = null))
    }

    @Test
    fun `wifi is wifi`() {
        assertEquals(
            NetworkConnectionKind.WiFi,
            networkConnectionKind(active = setOf(NetworkTransport.Wifi)),
        )
    }

    @Test
    fun `ethernet beats wifi on the same network`() {
        assertEquals(
            NetworkConnectionKind.Ethernet,
            networkConnectionKind(active = setOf(NetworkTransport.Ethernet, NetworkTransport.Wifi)),
        )
    }

    @Test
    fun `cellular counts as connected`() {
        assertEquals(
            NetworkConnectionKind.WiFi,
            networkConnectionKind(active = setOf(NetworkTransport.Cellular)),
        )
    }

    @Test
    fun `vpn that already reports wifi stays wifi`() {
        assertEquals(
            NetworkConnectionKind.WiFi,
            networkConnectionKind(active = setOf(NetworkTransport.Vpn, NetworkTransport.Wifi)),
        )
    }

    @Test
    fun `vpn without an underlying transport uses another wifi network`() {
        assertEquals(
            NetworkConnectionKind.WiFi,
            networkConnectionKind(
                active = setOf(NetworkTransport.Vpn),
                others = listOf(setOf(NetworkTransport.Wifi)),
            ),
        )
    }

    @Test
    fun `vpn prefers ethernet over wifi on other networks`() {
        assertEquals(
            NetworkConnectionKind.Ethernet,
            networkConnectionKind(
                active = setOf(NetworkTransport.Vpn),
                others = listOf(
                    setOf(NetworkTransport.Wifi),
                    setOf(NetworkTransport.Ethernet),
                ),
            ),
        )
    }

    @Test
    fun `vpn with no other network is offline`() {
        assertEquals(
            NetworkConnectionKind.Offline,
            networkConnectionKind(active = setOf(NetworkTransport.Vpn)),
        )
    }

    @Test
    fun `network without a known transport is offline`() {
        assertEquals(NetworkConnectionKind.Offline, networkConnectionKind(active = emptySet()))
    }
}
