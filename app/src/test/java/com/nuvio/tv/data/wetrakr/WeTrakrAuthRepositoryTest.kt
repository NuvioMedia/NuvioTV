package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrAuthRepositoryTest {
    @Test
    fun `device flow sends the client id as json and builds the qr link`() = runTest {
        val harness = WeTrakrTestHarness()
        val session = harness.pending()
        val request = harness.engine.requests.single()

        assertEquals("/oauth/device/code", request.path)
        assertEquals("public-client", Json.parseToJsonElement(request.body!!).jsonObject["client_id"]!!.jsonPrimitive.content)
        assertNull(request.accessToken)
        assertEquals("K7P2QX", session.userCode)
        assertEquals("https://wetrakr.com/activate", session.verificationUrl)
        assertEquals("https://wetrakr.com/activate?code=K7P2QX", session.verificationUrlComplete)
        assertEquals(harness.now + 600_000L, session.expiresAtEpochMs)
    }

    @Test
    fun `untrusted verification url is rejected`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.reply(body = WeTrakrTestHarness.DEVICE_RESPONSE.replace("https://wetrakr.com", "https://example.com"))

        expectWeTrakrFailure<WeTrakrAuthException> { harness.auth.startDeviceAuthorization() }

        assertNull(harness.store.state.value.session)
    }

    @Test
    fun `code limit and unknown client map to their own errors`() = runTest {
        for ((status, error) in listOf(429 to WeTrakrAuthError.TOO_MANY_CODES, 401 to WeTrakrAuthError.INVALID_CLIENT)) {
            val harness = WeTrakrTestHarness()
            harness.reply(status = status)
            val failure = expectWeTrakrFailure<WeTrakrAuthException> { harness.auth.startDeviceAuthorization() }
            assertEquals(error, failure.error)
        }
    }

    @Test
    fun `poll statuses follow the device flow table`() = runTest {
        val cases = listOf(
            400 to WeTrakrDevicePollResult.Pending,
            404 to WeTrakrDevicePollResult.Expired,
            409 to WeTrakrDevicePollResult.Expired,
            410 to WeTrakrDevicePollResult.Expired,
            418 to WeTrakrDevicePollResult.Denied
        )
        for ((status, expected) in cases) {
            val harness = WeTrakrTestHarness()
            harness.pending()
            harness.now += 5_000L
            harness.reply(status = status)
            assertEquals(expected, harness.auth.pollDeviceAuthorization())
            assertFalse(harness.store.state.value.isAuthenticated)
        }
    }

    @Test
    fun `slow down lengthens the poll interval`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(status = 429)

        assertEquals(WeTrakrDevicePollResult.Pending, harness.auth.pollDeviceAuthorization())

        assertEquals(10, harness.store.state.value.session!!.intervalSeconds)
    }

    @Test
    fun `approval stores the tokens and sends the device code`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(body = WeTrakrTestHarness.TOKEN_RESPONSE)

        assertEquals(WeTrakrDevicePollResult.Authorized, harness.auth.pollDeviceAuthorization())

        val body = Json.parseToJsonElement(harness.engine.requests.last().body!!).jsonObject
        assertEquals("device-secret", body["code"]!!.jsonPrimitive.content)
        assertEquals("public-client", body["client_id"]!!.jsonPrimitive.content)
        assertTrue(harness.store.state.value.isAuthenticated)
        assertEquals("access-two", harness.store.authorization()!!.tokens.accessToken)
    }

    @Test
    fun `refresh keeps the rotated refresh token`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.connected()
        harness.reply(body = """{"access_token":"access-two","refresh_token":"refresh-two","new_refresh_token":"refresh-two","token_type":"Bearer","expires_in":604800}""")

        val authorization = harness.auth.authorization(harness.store.scope(), "access-one")

        assertEquals("/oauth/token/refresh", harness.engine.requests.single().path)
        assertEquals("access-two", authorization.tokens.accessToken)
        assertEquals("refresh-two", authorization.tokens.refreshToken)
    }

    @Test
    fun `rejected refresh token signs the user out`() = runTest {
        val harness = WeTrakrTestHarness()
        harness.connected()
        harness.reply(status = 401, body = """{"error":"TOKEN_REVOKED"}""")

        val failure = expectWeTrakrFailure<WeTrakrAuthException> {
            harness.auth.authorization(harness.store.scope(), "access-one")
        }

        assertEquals(WeTrakrAuthError.AUTHORIZATION_REVOKED, failure.error)
        assertFalse(harness.store.state.value.isAuthenticated)
    }
}
