package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.PlaybackIssueReportApi
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackAnalyticsDto
import com.squareup.moshi.Moshi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Exercise the actual DTO conversion used immediately before the Retrofit request. */
class PlaybackIssueReportPrivacyTest {
    private val repository = PlaybackIssueReportRepository(mockk<PlaybackIssueReportApi>())
    private val moshi = Moshi.Builder().build()
    private val unsafe = "https://alice:privateCredential@cdn.example/secretPath?token=privateCredential"

    @Test fun `error conversion preserves status without exposing exception secrets`() {
        val input = PlaybackIssueErrorInput(
            displayMessage = unsafe, errorCode = 2001, errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            exceptionClass = "IOException", causeClass = "SocketException",
            causeMessage = "password=privateCredential", httpStatus = 503, category = "STARTUP_TIMEOUT"
        )
        val dto = with(repository) { input.toDto() }
        val json = moshi.adapter(PlaybackIssueErrorDto::class.java).toJson(dto)
        assertFalse(json, json.contains("privateCredential"))
        assertFalse(json, json.contains("secretPath"))
        assertFalse(json, json.contains("\"category\""))
        assertEquals(503, dto.httpStatus)
        assertEquals(2001, dto.errorCode)
    }

    @Test fun `typed category uses existing diagnostic events without changing wire schema`() {
        val input = mockk<PlaybackIssueLoadingInput>(relaxed = true)
        every { input.phase } returns "buffering_before_first_frame"
        every { input.elapsedMs } returns 25000L
        every { input.events } returns emptyList()
        val dto = with(repository) { input.toDtoWithErrorCategory("STARTUP_TIMEOUT") }
        assertEquals("errorCategory=STARTUP_TIMEOUT", dto.events.single().detail)
        assertEquals(25000L, dto.events.single().elapsedMs)
        assertEquals("buffering_before_first_frame", dto.events.single().phase)
    }

    @Test fun `loading report serializes sanitized raw events and preserves startup timing`() {
        val input = mockk<PlaybackIssueLoadingInput>(relaxed = true)
        every { input.phase } returns "READY"
        every { input.elapsedMs } returns 1450L
        every { input.phaseElapsedMs } returns 250L
        every { input.rawEventLines } returns listOf("failed $unsafe", "Authorization: Bearer privateCredential")
        every { input.events } returns listOf(
            PlaybackIssueLoadingEventInput(100L, 1450L, "READY", null, null, unsafe)
        )
        val dto = with(repository) { input.toDto() }
        val json = moshi.adapter(PlaybackIssueLoadingDto::class.java).toJson(dto)
        assertFalse(json, json.contains("privateCredential"))
        assertFalse(json, json.contains("secretPath"))
        assertEquals("READY", dto.phase)
        assertEquals(1450L, dto.elapsedMs)
        assertEquals(250L, dto.phaseElapsedMs)
    }

    @Test fun `analytics sanitizes every event collection and structured secret values`() {
        val input = mockk<PlaybackIssuePlaybackAnalyticsInput>(relaxed = true)
        val event = PlaybackIssuePlaybackEventInput(
            timeMs = 100, elapsedMs = 200, name = "error", playbackState = "BUFFERING",
            positionMs = 0, bufferedPositionMs = 0,
            details = mapOf("access_token" to "privateCredential", "api%5Fkey" to "privateCredential", "message" to unsafe, "phase" to "BUFFERING")
        )
        every { input.clickToFirstFrameMs } returns 1800L
        every { input.videoFormat } returns null
        every { input.audioFormat } returns null
        every { input.lastLoad } returns null
        every { input.lastLoadError } returns null
        every { input.rawEventLines } returns listOf(unsafe)
        every { input.rawEvents } returns listOf("Bearer privateCredential")
        every { input.events } returns listOf(event)
        every { input.deepExoEvents } returns listOf(event)
        every { input.stutterSignals } returns listOf(event)
        every { input.healthSnapshots } returns emptyList()
        every { input.startupStages } returns emptyList()
        val dto = with(repository) { input.toDto() }
        val json = moshi.adapter(PlaybackIssuePlaybackAnalyticsDto::class.java).toJson(dto)
        assertFalse(json, json.contains("privateCredential"))
        assertFalse(json, json.contains("secretPath"))
        assertEquals(1800L, dto.clickToFirstFrameMs)
        assertEquals("BUFFERING", dto.events.single().details["phase"])
        assertEquals("[redacted]", dto.events.single().details["access_token"])
        assertEquals("[redacted]", dto.events.single().details["api_key"])
    }
}
