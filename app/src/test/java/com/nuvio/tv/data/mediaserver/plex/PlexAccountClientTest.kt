package com.nuvio.tv.data.mediaserver.plex

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexAccountClientTest {
    private var clock = 1_000_000L

    private fun account(route: (Request) -> Pair<Int, String>): Pair<PlexAccountClient, PlexTestHttp> {
        val http = PlexTestHttp(route)
        return PlexAccountClient(PlexClient(http.client, plexTestIdentity), now = { clock }) to http
    }

    private val owner = PlexAccount("account-token", "1", "uuid-owner", "Paul")

    @Test
    fun startsACodeAndWaitsUntilItIsLinked() = runTest {
        var linked = false
        val (plex, http) = account { request ->
            when (request.method) {
                "POST" -> 201 to """{"id":42,"code":"ABCD","expiresIn":900}"""
                else -> 200 to (if (linked) """{"id":42,"code":"ABCD","authToken":"account-token"}""" else """{"id":42,"code":"ABCD"}""")
            }
        }

        val pin = plex.startPin()
        assertEquals("ABCD", pin.code)
        assertEquals(clock + 900_000L, pin.expiresAtMs)
        assertEquals(PlexPinStatus.Waiting, plex.checkPin(pin))
        linked = true
        assertEquals("account-token", (plex.checkPin(pin) as PlexPinStatus.Linked).accountToken)
        assertEquals("Nuvio", http.requests.first().header("X-Plex-Product"))
        assertEquals("device-1", http.requests.first().header("X-Plex-Client-Identifier"))
    }

    @Test
    fun aMissingCodeCountsAsWaitingUntilItRunsOut() = runTest {
        val (plex, _) = account { 404 to "" }
        val pin = PlexPin(42, "ABCD", expiresAtMs = clock + 1_000L)

        assertEquals(PlexPinStatus.Waiting, plex.checkPin(pin))
        clock += 2_000L
        assertEquals(PlexPinStatus.Expired, plex.checkPin(pin))
    }

    @Test
    fun listsServersWithTheTokenEachOneTakes() = runTest {
        val (plex, _) = account {
            200 to """[
                {"name":"Home","provides":"server","clientIdentifier":"m1","owned":true,"connections":[
                  {"address":"192.168.1.20","port":32400,"uri":"https://192-168-1-20.abc.plex.direct:32400","local":true},
                  {"address":"203.0.113.5","port":32400,"uri":"https://203-0-113-5.abc.plex.direct:32400","local":false}]},
                {"name":"Friend","provides":"server","clientIdentifier":"m2","owned":false,"accessToken":"friend-token","connections":[]},
                {"name":"No token","provides":"server","clientIdentifier":"m3","owned":false,"connections":[]},
                {"name":"Phone","provides":"player","clientIdentifier":"p1","owned":true}]"""
        }

        val servers = plex.servers("account-token")

        assertEquals(listOf("m1", "m2"), servers.map { it.machineId })
        assertEquals(listOf("account-token", "friend-token"), servers.map { it.accessToken })
        assertEquals(
            listOf(
                "https://192-168-1-20.abc.plex.direct:32400",
                "http://192.168.1.20:32400",
                "https://203-0-113-5.abc.plex.direct:32400"
            ),
            servers.first().addresses.map { it.uri }
        )
    }

    @Test
    fun anAccountWithoutPlexHomeIsItsOnlyUser() = runTest {
        val (plex, _) = account { 404 to "" }

        val users = plex.homeUsers(owner)

        assertEquals(listOf("uuid-owner"), users.map { it.uuid })
        assertTrue(users.single().admin)
    }

    @Test
    fun aWrongPinGivesNoUser() = runTest {
        val (plex, http) = account { 403 to "" }
        val kid = PlexHomeUser("2", "uuid-kid", "Kid", hasPin = true, admin = false, restricted = true)

        assertNull(plex.switchUser(owner, kid, "1234"))
        assertEquals("1234", http.requests.single().url.queryParameter("pin"))
    }

    @Test
    fun theOwnerWithoutPinNeedsNoSwitch() = runTest {
        val (plex, http) = account { error("No request expected") }
        val self = PlexHomeUser("1", "uuid-owner", "Paul", hasPin = false, admin = true, restricted = false)

        assertEquals(owner, plex.switchUser(owner, self, null))
        assertTrue(http.requests.isEmpty())
    }

    private val server = PlexServer(
        machineId = "m1",
        name = "Home",
        owned = true,
        accessToken = "token",
        addresses = listOf(
            PlexAddress("https://203-0-113-5.abc.plex.direct:32400", local = false, relay = false),
            PlexAddress("http://192.168.1.20:32400", local = true, relay = false),
            PlexAddress("https://relay.plex.direct:8443", local = false, relay = true),
            PlexAddress("http://203.0.113.5:32400", local = false, relay = false)
        )
    )

    @Test
    fun prefersTheHomeNetworkThenTheInternetThenRelay() = runTest {
        val (plex, _) = account { error("No request expected") }

        val home = plex.reach(server) { "m1" }
        val away = plex.reach(server) { address -> "m1".takeUnless { "192.168" in address } }
        val relay = plex.reach(server) { address -> "m1".takeIf { "relay" in address } }
        val offline = plex.reach(server) { "another-server" }

        assertEquals(PlexReach.LOCAL to "http://192.168.1.20:32400", home.reach to home.address)
        assertEquals(PlexReach.REMOTE to "https://203-0-113-5.abc.plex.direct:32400", away.reach to away.address)
        assertEquals(PlexReach.RELAY, relay.reach)
        assertEquals(PlexReach.OFFLINE to null, offline.reach to offline.address)
    }

    @Test
    fun neverUsesAnUnencryptedAddressAwayFromHome() = runTest {
        val (plex, _) = account { error("No request expected") }
        val tried = mutableListOf<String>()

        plex.reach(server) { address -> tried += address; null }

        assertTrue("http://203.0.113.5:32400" !in tried)
    }

    @Test
    fun deadAddressesDoNotUseUpTheTimeOfTheOthers() = runTest {
        val (plex, _) = account { error("No request expected") }
        val crowded = PlexServer(
            machineId = "m1",
            name = "Home",
            owned = true,
            accessToken = "token",
            addresses = List(8) { PlexAddress("https://10-0-0-$it.abc.plex.direct:32400", local = true, relay = false) } +
                PlexAddress("https://203-0-113-5.abc.plex.direct:32400", local = false, relay = false)
        )

        val reached = plex.reach(crowded) { address -> if ("10-0-0" in address) awaitCancellation() else "m1" }

        assertEquals("https://203-0-113-5.abc.plex.direct:32400", reached.address)
    }
}
