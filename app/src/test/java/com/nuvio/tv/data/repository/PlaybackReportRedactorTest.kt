package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportRedactorTest {
    @Test fun `removes full links including userinfo paths queries and fragments`() {
        listOf(
            "https://alice:secret@cdn.example/private/path?token=secret#secret",
            "rtsp://alice:secret@cdn.example/private/path",
            "//alice:secret@cdn.example/private/path?key=secret",
            "magnet:?xt=urn:btih:secret",
            "www.example.com/private/secret"
        ).forEach { link ->
            assertEquals("failed [redacted-url]", PlaybackReportRedactor.text("failed $link", 2000))
        }
    }

    @Test fun `decodes nested and escaped links before redaction`() {
        listOf(
            "https%3A%2F%2Fcdn.example%2Fsecret%3Ftoken%3Dsecret",
            "https%253A%252F%252Fcdn.example%252Fsecret",
            "https:\\/\\/cdn.example/secret",
            "https\\u003a\\u002f\\u002fcdn.example/secret",
            "redirect=https://outer.example/?next=https%3A%2F%2Finner.example%2Fsecret"
        ).forEach { text ->
            val safe = PlaybackReportRedactor.text(text, 2000)
            assertFalse(safe, safe.contains("secret"))
            assertFalse(safe, safe.contains("cdn.example"))
        }
    }

    @Test fun `removes credentials from header cookie bearer and password fragments`() {
        listOf(
            "Authorization: Bearer secret",
            "authorization=Basic secret",
            "Cookie: session=secret; other=secret",
            "Set-Cookie=session=secret; Path=/secret",
            "Bearer secret", "Basic secret",
            "token=secret", "access_token: secret", "refreshToken=secret",
            "api-key=secret", "password='secret with spaces'",
            "\"client_secret\": \"secret\"", "username=secret", "pwd=secret"
        ).forEach { text ->
            val safe = PlaybackReportRedactor.text(text, 2000)
            assertFalse("Input $text yielded $safe", safe.contains("secret"))
        }
    }

    @Test fun `redacts structured credential values even when value has no label`() {
        listOf("Authorization", "accessToken", "X-Api-Key", "Cookie", "password", "client_secret")
            .forEach { assertEquals("[redacted]", PlaybackReportRedactor.detailValue(it, "secret", 200)) }
        assertEquals("READY", PlaybackReportRedactor.detailValue("phase", "READY", 200))
    }

    @Test fun `preserves useful timing phase category and host`() {
        val value = "phase=READY elapsedMs=1800 category=STARTUP_TIMEOUT host=cdn.example status=503"
        assertEquals(value, PlaybackReportRedactor.text(value, 2000))
        assertEquals("café", PlaybackReportRedactor.text("caf%C3%A9", 2000))
    }

    @Test fun `encoded structured keys cannot hide credentials`() {
        listOf("access%5Ftoken", "api%255Fkey", "pass\\u0077ord", "access%252525255Ftoken")
            .forEach { key -> assertEquals(key, "[redacted]", PlaybackReportRedactor.detailValue(key, "privateCredential", 200)) }
    }

    @Test fun `sanitizes before truncation and handles excessive encoding conservatively`() {
        val raw = "failed https://cdn.example/" + "a".repeat(3000) + "?token=secret"
        assertEquals("failed [redacted-url]", PlaybackReportRedactor.text(raw, 80))
        assertEquals("[redacted]", PlaybackReportRedactor.text("https%252525253A%252525252F%252525252Fexample/secret", 80))
    }

    @Test fun `header names never contain values`() {
        val names = PlaybackReportRedactor.headerNames(listOf("Authorization", "Content-Type", "Cookie: secret", "X=secret", "content-type"))
        assertEquals(listOf("authorization", "content-type"), names)
        assertTrue(names.none { it.contains("secret") })
    }
}
