package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAudioFallbackPolicyTest {

    @Test
    fun `steps run safe audio, then forced PCM, then audio disabled`() {
        assertEquals(
            PlayerAudioFallbackPolicy.Step.SAFE_AUDIO,
            PlayerAudioFallbackPolicy.nextStep(safeAudioActive = false, pcmForced = false, audioDisabled = false)
        )
        assertEquals(
            PlayerAudioFallbackPolicy.Step.FORCE_PCM,
            PlayerAudioFallbackPolicy.nextStep(safeAudioActive = true, pcmForced = false, audioDisabled = false)
        )
        assertEquals(
            PlayerAudioFallbackPolicy.Step.DISABLE_AUDIO,
            PlayerAudioFallbackPolicy.nextStep(safeAudioActive = true, pcmForced = true, audioDisabled = false)
        )
    }

    @Test
    fun `no step is left once every fallback is in force`() {
        assertNull(PlayerAudioFallbackPolicy.nextStep(safeAudioActive = true, pcmForced = true, audioDisabled = true))
    }

    // Mirrors the controller: safe audio and audio disabled are kept per stream url, while forced
    // PCM survives the player rebuild only when the step armed the rebuild for it.
    @Test
    fun `a failure that persists on the same stream ends the ladder after three rebuilds`() {
        var safeAudio = false
        var pcmForced = false
        var pcmRebuildArmed = false
        var audioDisabled = false
        val rebuilds = mutableListOf<PlayerAudioFallbackPolicy.Step>()

        while (rebuilds.size < 10) {
            val step = PlayerAudioFallbackPolicy.nextStep(safeAudio, pcmForced, audioDisabled) ?: break
            when (step) {
                PlayerAudioFallbackPolicy.Step.SAFE_AUDIO -> safeAudio = true
                PlayerAudioFallbackPolicy.Step.FORCE_PCM -> {
                    pcmForced = true
                    pcmRebuildArmed = true
                }
                PlayerAudioFallbackPolicy.Step.DISABLE_AUDIO -> {
                    audioDisabled = true
                    pcmRebuildArmed = true
                }
            }
            rebuilds += step
            // initializePlayer: forced PCM is cleared unless the rebuild was armed for it.
            pcmForced = pcmRebuildArmed
            pcmRebuildArmed = false
            if (step == PlayerAudioFallbackPolicy.Step.FORCE_PCM) {
                assertTrue("the forced PCM rebuild must actually force PCM", pcmForced)
            }
        }

        assertEquals(
            listOf(
                PlayerAudioFallbackPolicy.Step.SAFE_AUDIO,
                PlayerAudioFallbackPolicy.Step.FORCE_PCM,
                PlayerAudioFallbackPolicy.Step.DISABLE_AUDIO
            ),
            rebuilds
        )
    }
}
