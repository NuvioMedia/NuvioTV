package com.nuvio.tv.ui.screens.player

/**
 * Recovery ladder for audio failures that persist on the same stream: safe audio, then forced PCM
 * on top of it, then audio disabled. Returns null once every step is in force, so the error moves
 * on to the rest of the recovery chain instead of rebuilding the player again.
 */
internal object PlayerAudioFallbackPolicy {

    enum class Step {
        SAFE_AUDIO,

        // The player rebuild clears forced PCM unless the rebuild is armed for it, so this step and
        // the next must arm it, or the next failure lands on this step again.
        FORCE_PCM,
        DISABLE_AUDIO
    }

    fun nextStep(
        safeAudioActive: Boolean,
        pcmForced: Boolean,
        audioDisabled: Boolean
    ): Step? = when {
        !safeAudioActive -> Step.SAFE_AUDIO
        !pcmForced -> Step.FORCE_PCM
        !audioDisabled -> Step.DISABLE_AUDIO
        else -> null
    }
}
