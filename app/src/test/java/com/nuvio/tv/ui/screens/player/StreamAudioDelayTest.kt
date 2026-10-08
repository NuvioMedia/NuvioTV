package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAudioDelayTest {

    @Test
    fun `stream delay is added to the device delay`() {
        assertEquals(1_450, combinedAudioDelayMs(deviceDelayMs = 200, streamDelayMs = 1_250))
        assertEquals(-800, combinedAudioDelayMs(deviceDelayMs = 200, streamDelayMs = -1_000))
        assertEquals(200, combinedAudioDelayMs(deviceDelayMs = 200, streamDelayMs = 0))
    }

    @Test
    fun `combined delay is clamped to the player range`() {
        assertEquals(AUDIO_DELAY_MAX_MS, combinedAudioDelayMs(deviceDelayMs = 50_000, streamDelayMs = 20_000))
        assertEquals(AUDIO_DELAY_MIN_MS, combinedAudioDelayMs(deviceDelayMs = -50_000, streamDelayMs = -20_000))
    }

    @Test
    fun `addon behaviorHints audioDelayMs reaches the domain model`() {
        assertEquals(1_240, BehaviorHintsDto(audioDelayMs = 1_240).toDomain().audioDelayMs)
        assertNull(BehaviorHintsDto().toDomain().audioDelayMs)
    }
}
