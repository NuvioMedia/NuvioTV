package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

object DeniedTranscodePlanner {

    private val candidateMimeTypes = listOf(
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD
    )

    fun effectiveTranscodeMimes(
        policy: AudioPassthroughPolicy,
        transcodeDeniedToAc3: Boolean,
        forcePassthroughActive: Boolean
    ): Set<String> {
        if (!transcodeDeniedToAc3) return emptySet()
        if (forcePassthroughActive) return emptySet()
        if (!policy.softwareDecodersAvailable) return emptySet()
        if (policy.deniesPassthrough(MimeTypes.AUDIO_AC3)) return emptySet()
        return candidateMimeTypes.filter { policy.deniesPassthrough(it) }.toSet()
    }
}
