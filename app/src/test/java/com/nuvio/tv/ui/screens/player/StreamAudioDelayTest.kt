package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.toStreamAudioDelayMs
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAudioDelayTest {

    private val streamResponseAdapter = Moshi.Builder().build().adapter(StreamResponseDto::class.java)

    // --- AudioDelayParts: total ---

    @Test
    fun `stream delay is added to the device delay`() {
        assertEquals(1_450, AudioDelayParts(deviceMs = 200).withStream(1_250).totalMs)
        assertEquals(-800, AudioDelayParts(deviceMs = 200).withStream(-1_000).totalMs)
        assertEquals(200, AudioDelayParts(deviceMs = 200).withStream(0).totalMs)
    }

    @Test
    fun `total delay is clamped to the player range`() {
        assertEquals(AUDIO_DELAY_MAX_MS, AudioDelayParts(deviceMs = 50_000).withStream(20_000).totalMs)
        assertEquals(AUDIO_DELAY_MIN_MS, AudioDelayParts(deviceMs = -50_000).withStream(-20_000).totalMs)
    }

    @Test
    fun `stream and device delays are clamped on input`() {
        assertEquals(AUDIO_DELAY_MAX_MS, AudioDelayParts().withStream(100_000).streamMs)
        assertEquals(AUDIO_DELAY_MIN_MS, AudioDelayParts().withStream(-100_000).streamMs)
        assertEquals(AUDIO_DELAY_MAX_MS, AudioDelayParts().withDevice(100_000).deviceMs)
    }

    // --- AudioDelayParts: what gets persisted ---

    @Test
    fun `manual change without stream delay updates and persists the device delay`() {
        val parts = AudioDelayParts(deviceMs = 100).withManualTotal(325)

        assertEquals(325, parts.deviceMs)
        assertEquals(0, parts.sessionAdjustMs)
        assertEquals(325, parts.totalMs)
        assertEquals(325, parts.persistableDeviceMs)
    }

    @Test
    fun `manual change on a stream with delay is a session tweak and is not persisted`() {
        val parts = AudioDelayParts(deviceMs = 100).withStream(5_000).withManualTotal(5_300)

        assertEquals(5_300, parts.totalMs)
        assertEquals(100, parts.deviceMs)
        assertEquals(200, parts.sessionAdjustMs)
        assertNull(parts.persistableDeviceMs)
    }

    @Test
    fun `huge stream delay cannot produce a wild persisted device delay`() {
        // Old bug: stream 100000 + one press saved -39975 as the device delay.
        val parts = AudioDelayParts().withStream(100_000).withManualTotal(AUDIO_DELAY_MAX_MS - 25)

        assertEquals(0, parts.deviceMs)
        assertNull(parts.persistableDeviceMs)
    }

    // --- AudioDelayParts: stream switch / route change / player re-init ---

    @Test
    fun `tweak on a stream with delay does not leak into the next stream`() {
        val onStreamA = AudioDelayParts(deviceMs = 0).withStream(5_000).withManualTotal(5_200)

        val onStreamB = onStreamA.withStream(0)

        assertEquals(0, onStreamB.totalMs)
        assertEquals(0, onStreamB.sessionAdjustMs)
        // A press on stream B must persist only the user's own value, not the old 200 tweak.
        assertEquals(25, onStreamB.withManualTotal(25).persistableDeviceMs)
    }

    @Test
    fun `clamped total does not corrupt the device delay on stream switch`() {
        val clamped = AudioDelayParts(deviceMs = 10_000).withStream(55_000)
        assertEquals(AUDIO_DELAY_MAX_MS, clamped.totalMs)

        val next = clamped.withStream(0)

        assertEquals(10_000, next.deviceMs)
        assertEquals(10_000, next.totalMs)
    }

    @Test
    fun `switching between streams with delay keeps the device delay and drops the tweak`() {
        val parts = AudioDelayParts(deviceMs = 150).withStream(5_000).withManualTotal(5_400).withStream(-2_000)

        assertEquals(150, parts.deviceMs)
        assertEquals(0, parts.sessionAdjustMs)
        assertEquals(-1_850, parts.totalMs)
    }

    @Test
    fun `reloading the stored device delay keeps the tweak on the current stream`() {
        // Player re-init (error recovery, engine failover, libass switch) or a route change.
        val parts = AudioDelayParts(deviceMs = 100).withStream(5_000).withManualTotal(5_300)

        val sameRoute = parts.withDevice(100)
        val bluetoothRoute = parts.withDevice(250)

        assertEquals(5_300, sameRoute.totalMs)
        assertEquals(5_450, bluetoothRoute.totalMs)
        assertEquals(200, bluetoothRoute.sessionAdjustMs)
    }

    // --- Hint normalization ---

    @Test
    fun `hint values are rounded, clamped and non-finite values dropped`() {
        assertEquals(1_250, 1_250.toStreamAudioDelayMs())
        assertEquals(1_250, 1_250.4.toStreamAudioDelayMs())
        assertEquals(1_251, 1_250.6.toStreamAudioDelayMs())
        assertEquals(-1_251, (-1_250.6).toStreamAudioDelayMs())
        assertEquals(1_250, 1_250L.toStreamAudioDelayMs())
        assertEquals(AUDIO_DELAY_MAX_MS, 100_000.0.toStreamAudioDelayMs())
        assertEquals(AUDIO_DELAY_MIN_MS, (-100_000.0).toStreamAudioDelayMs())
        assertEquals(AUDIO_DELAY_MAX_MS, 1e12.toStreamAudioDelayMs())
        assertNull(Double.NaN.toStreamAudioDelayMs())
        assertNull(Double.POSITIVE_INFINITY.toStreamAudioDelayMs())
        assertNull(Double.NEGATIVE_INFINITY.toStreamAudioDelayMs())
        assertNull((null as Number?).toStreamAudioDelayMs())
    }

    @Test
    fun `addon behaviorHints audioDelayMs reaches the domain model`() {
        assertEquals(1_240, BehaviorHintsDto(audioDelayMs = 1_240.0).toDomain().audioDelayMs)
        assertEquals(AUDIO_DELAY_MAX_MS, BehaviorHintsDto(audioDelayMs = 100_000.0).toDomain().audioDelayMs)
        assertNull(BehaviorHintsDto(audioDelayMs = Double.NaN).toDomain().audioDelayMs)
        assertNull(BehaviorHintsDto().toDomain().audioDelayMs)
    }

    // --- Real addon JSON ---

    @Test
    fun `addon response with integer, decimal or missing audioDelayMs parses`() {
        val json = """
            {"streams":[
              {"url":"https://a","behaviorHints":{"audioDelayMs":1250}},
              {"url":"https://b","behaviorHints":{"audioDelayMs":1250.6}},
              {"url":"https://c","behaviorHints":{"audioDelayMs":-400.0}},
              {"url":"https://d","behaviorHints":{"audioDelayMs":250000}},
              {"url":"https://e","behaviorHints":{"bingeGroup":"x"}},
              {"url":"https://f"}
            ]}
        """.trimIndent()

        val delays = requireNotNull(streamResponseAdapter.fromJson(json)?.streams)
            .map { it.behaviorHints?.toDomain()?.audioDelayMs }

        assertEquals(listOf(1_250, 1_251, -400, AUDIO_DELAY_MAX_MS, null, null), delays)
    }
}
