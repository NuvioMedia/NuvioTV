package com.nuvio.tv.core.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Runs against Android Keystore and Conscrypt, not the host JVM's TLS provider. */
@RunWith(AndroidJUnit4::class)
class RemoteTlsIdentityDeviceTest {
    private val oldAlias = "nuvio.remote.tls.test.v1"
    private val newAlias = "nuvio.remote.tls.test.v2"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before fun retainHttpServerTimeZoneSupportInInstrumentationRuntime() {
        // The test APK supplies its own shrunk desugar runtime. Keep the overload
        // used by NanoHTTPD in the target APK, which the test shrinker cannot see.
        assertEquals("GMT", java.util.TimeZone.getTimeZone("GMT").id)
    }

    @After fun cleanup() {
        store().apply { deleteEntry(oldAlias); deleteEntry(newAlias) }
    }

    @Test fun legacyKeyRejectsTlsSigningButUpgradedIdentitySignsAndSurvivesReload() {
        cleanup()
        val legacy = KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(oldAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build())
        }.generateKeyPair()
        val digest = MessageDigest.getInstance("SHA-256").digest("TLS transcript".toByteArray())
        val legacyFailure = runCatching {
            Signature.getInstance("NONEwithECDSA").apply { initSign(legacy.private); update(digest) }.sign()
        }.exceptionOrNull()
        assertNotNull("The old authorization must reproduce the TLS signing failure", legacyFailure)
        try {
            exchange(RemoteTlsIdentity(oldAlias), "TLSv1.2", correctPin = true)
            fail("The old key must also fail the real TLS handshake")
        } catch (_: SSLException) { }

        val identity = RemoteTlsIdentity(newAlias)
        val key = store().getKey(newAlias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("NONEwithECDSA").apply { initSign(key); update(digest) }.sign()
        assertTrue(Signature.getInstance("NONEwithECDSA").apply { initVerify(identity.certificate); update(digest) }.verify(signature))
        assertArrayEquals(identity.certificate.encoded, RemoteTlsIdentity(newAlias).certificate.encoded)
        assertTrue("Migration must not delete other Keystore entries", store().containsAlias(oldAlias))
    }

    @Test fun pinnedTls12And13HandshakesTransferData() {
        val identity = RemoteTlsIdentity(newAlias)
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) exchange(identity, protocol, correctPin = true)
    }

    @Test fun wrongCertificatePinPreventsConnection() {
        try {
            exchange(RemoteTlsIdentity(newAlias), "TLSv1.2", correctPin = false)
            fail("A different certificate must not be trusted")
        } catch (_: SSLException) { }
    }

    @Test fun realTvServerPairsAndPublishesPlaybackOverPinnedHttps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TvRemoteServer.start(instrumentation.targetContext)
        val deadline = System.currentTimeMillis() + 10_000
        while (TvRemoteServer.instance == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val server = requireNotNull(TvRemoteServer.instance)
        val code = Uri.parse(server.beginPairing())
        val pin = requireNotNull(code.getQueryParameter("pin"))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("No certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(pin.toByteArray(), actual.toByteArray())) throw CertificateException("Pin mismatch")
                certificate.checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val base = "https://127.0.0.1:${code.getQueryParameter("port")}/v1"
        fun request(path: String, token: String? = null, body: String? = null): JSONObject {
            val connection = URL("$base/$path").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = tls.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                runCatching { trust.checkServerTrusted(session.peerCertificates.map { it as X509Certificate }.toTypedArray(), "EC"); true }.getOrDefault(false)
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                if (body != null) {
                    val bytes = body.toByteArray()
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                assertEquals(200, connection.responseCode)
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        val source = Any()
        try {
            val paired = request("pair", body = JSONObject().put("deviceId", code.getQueryParameter("id"))
                .put("secret", code.getQueryParameter("secret")).toString())
            assertEquals(code.getQueryParameter("id"), paired.getString("deviceId"))
            val token = paired.getString("credential")
            assertEquals(43, token.length)
            assertEquals("idle", request("playback", token).getString("state"))
            instrumentation.runOnMainSync {
                server.publish(source, RemoteSnapshot(deviceId = "", deviceName = "", sessionId = "tls-test-session-1",
                    title = "TLS test movie", state = "playing", durationMs = 120_000, canSeek = true)) { true }
            }
            val playing = request("playback", token)
            assertEquals("playing", playing.getString("state"))
            assertEquals("TLS test movie", playing.getString("title"))
        } finally {
            instrumentation.runOnMainSync { server.clear(source) }
            server.revoke()
            server.closePairing()
        }
    }

    private fun exchange(identity: RemoteTlsIdentity, protocol: String, correctPin: Boolean) {
        val expected = if (correctPin) MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded) else ByteArray(32)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("No certificate")
                if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(certificate.encoded)))
                    throw CertificateException("Pin mismatch")
                certificate.checkValidity()
            }
        }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val worker = Executors.newSingleThreadExecutor()
        val server = identity.socketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.enabledProtocols = arrayOf(protocol)
        server.soTimeout = 5_000
        val response = worker.submit<Unit> {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.startHandshake()
                socket.outputStream.write(42)
                socket.outputStream.flush()
            }
        }
        try {
            (clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.enabledProtocols = arrayOf(protocol)
                socket.startHandshake()
                assertEquals(protocol, socket.session.protocol)
                assertEquals(42, socket.inputStream.read())
            }
            response.get(6, TimeUnit.SECONDS)
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }
}
