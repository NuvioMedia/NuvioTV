package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PlayerMediaSourceAuthTest {

    private fun getAuthenticator(): okhttp3.Authenticator {
        val factory = PlayerMediaSourceFactory()
        val method = PlayerMediaSourceFactory::class.java.getDeclaredMethod("getOrCreateOkHttpClient")
        method.isAccessible = true
        val client = method.invoke(factory) as OkHttpClient
        return client.authenticator
    }

    @Test
    fun testKnownProviderUrlFormats() {
        val authenticator = getAuthenticator()

        val providerUrls = listOf(
            // 1. Generic NzbDav/WebDAV format
            "https://testuser:testpass@nzbdav.com/stream/file.mkv",
            
            // 2. WebDAV with port
            "http://user:pass123@192.168.1.100:8080/dav/movie.mp4",
            
            // 3. Premiumize WebDAV format (Customer ID + PIN)
            "https://123456789:abcde12345@webdav.premiumize.me/",
            
            // 4. EasyNews format (%40 for '@' in email username)
            "https://my.email%40gmail.com:myP%40ssw0rd@members.easynews.com/dl/movie.mkv",
            
            // 5. URL with complex query parameters
            "https://complexUser:complexPass@host.com/path?quality=1080p&source=RD",
            
            // 6. Put.io format
            "https://putioUser:putioPass@webdav.put.io/"
        )

        for (urlString in providerUrls) {
            println("--- TESTING PROVIDER URL: $urlString ---")
            val originalUrl = urlString.toHttpUrlOrNull()
            assertNotNull("URL string should be parsed correctly: $urlString", originalUrl)

            val expectedAuthHeader = okhttp3.Credentials.basic(originalUrl!!.username, originalUrl.password)
            println("Expected Auth Header to recover: $expectedAuthHeader")

            val originalRequest = Request.Builder().url(originalUrl).build()

            // Mock 302 Redirect response where it drops auth
            val priorResponse = Response.Builder()
                .request(originalRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .build()

            // The redirected URL (auth stripped natively by OkHttp on host change)
            val redirectedUrl = okhttp3.HttpUrl.Builder()
                .scheme(originalUrl.scheme)
                .host("redirect.cdn-server.com")
                .encodedPath(originalUrl.encodedPath)
                .encodedQuery(originalUrl.encodedQuery)
                .build()
                
            println("Redirected target URL (Auth stripped): $redirectedUrl")

            val redirectedRequest = Request.Builder().url(redirectedUrl).build()

            // Current 401 Unauthorized response from CDN
            val currentResponse = Response.Builder()
                .request(redirectedRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .priorResponse(priorResponse)
                .build()

            println("Simulating 401 Exception. Passing to Authenticator...")
            // Run Custom Authenticator
            val retriedRequest = authenticator.authenticate(null, currentResponse)

            assertNotNull("Authenticator should return a new Request for $urlString", retriedRequest)
            println("Authenticator recovered auth! Returned Header: ${retriedRequest?.header("Authorization")}")
            
            assertEquals("Auth header mismatch for $urlString", expectedAuthHeader, retriedRequest?.header("Authorization"))
            assertEquals("The retried URL should be the redirected URL", redirectedUrl, retriedRequest?.url)
            println("SUCCESS for $urlString\n")
        }
    }

    @Test
    fun testInfiniteLoopPrevention() {
        println("--- TESTING INFINITE LOOP PREVENTION ---")
        val authenticator = getAuthenticator()

        val originalUrl = "https://user:pass@host.com/file".toHttpUrlOrNull()!!
        val originalRequest = Request.Builder().url(originalUrl).build()

        val expectedAuthHeader = okhttp3.Credentials.basic("user", "pass")

        val priorResponse = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .build()
            
        // Simulate a scenario where the redirected server rejected our VALID credentials we just injected
        val redirectedUrl = "https://redirect.com/file".toHttpUrlOrNull()!!
        val redirectedRequest = Request.Builder()
            .url(redirectedUrl)
            .header("Authorization", expectedAuthHeader) // Auth header is ALREADY present
            .build()

        val currentResponse = Response.Builder()
            .request(redirectedRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(priorResponse)
            .build()
            
        println("Simulating 401 response where our Auth header is ALREADY attached. Asking Authenticator...")
        // Authenticator should return null to prevent endless 401 retries
        val retriedRequest = authenticator.authenticate(null, currentResponse)

        if (retriedRequest == null) {
            println("Authenticator properly returned NULL to prevent infinite loop. SUCCESS.\n")
        }
        assertNull("Authenticator should return null to prevent infinite loop", retriedRequest)
    }
    
    @Test
    fun testNoAuthUrlDoesNotCrash() {
        println("--- TESTING NO-AUTH URL 401 RECOVERY (No Crash Check) ---")
        val authenticator = getAuthenticator()

        val originalUrl = "https://host.com/file".toHttpUrlOrNull()!!
        val originalRequest = Request.Builder().url(originalUrl).build()

        val priorResponse = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .build()
            
        val redirectedUrl = "https://redirect.com/file".toHttpUrlOrNull()!!
        val redirectedRequest = Request.Builder()
            .url(redirectedUrl)
            .build()

        val currentResponse = Response.Builder()
            .request(redirectedRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(priorResponse)
            .build()
            
        println("Simulating 401 for a normal URL without user:pass info. passing to Authenticator...")

        // Authenticator should return null as there are no credentials to inject
        val retriedRequest = authenticator.authenticate(null, currentResponse)

        if (retriedRequest == null) {
            println("Authenticator realized no auth is present and cleanly returned NULL avoiding crashes. SUCCESS.\n")
        }
        assertNull("Authenticator should return null when no credentials exist", retriedRequest)
    }
}
