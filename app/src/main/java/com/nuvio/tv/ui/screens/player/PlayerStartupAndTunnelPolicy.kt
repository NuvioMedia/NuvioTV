package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.InternalPlayerEngine

/**
 * Pure policy for "is tunneling actually on this session?"
 * Keep in sync with DefaultTrackSelector setup in initializePlayer.
 */
internal fun resolveEffectiveTunneling(
    tunnelingSettingEnabled: Boolean,
    safeAudioMode: Boolean,
    engine: InternalPlayerEngine
): Boolean {
    return tunnelingSettingEnabled &&
        !safeAudioMode &&
        engine != InternalPlayerEngine.MVP_PLAYER
}

/** How we leave the paused startup state. */
internal enum class StartupPlaybackPlan {
    /** Wait for onRenderedFirstFrame (surface path). */
    WAIT_FIRST_FRAME,

    /** Tunnel: prefer first-frame callback, short READY fallback if OEM is silent. */
    TUNNEL_WAIT_THEN_FALLBACK,

    /** No video track — start on STATE_READY. */
    READY_NO_VIDEO
}

/**
 * First-ready autoplay policy. Caller still owns playWhenReady=false until the plan fires.
 */
internal fun resolveStartupPlaybackPlan(
    hasVideoTrack: Boolean,
    effectiveTunneling: Boolean
): StartupPlaybackPlan {
    if (!hasVideoTrack) return StartupPlaybackPlan.READY_NO_VIDEO
    if (effectiveTunneling) return StartupPlaybackPlan.TUNNEL_WAIT_THEN_FALLBACK
    return StartupPlaybackPlan.WAIT_FIRST_FRAME
}

/** Bitstream mime/codecs we treat as passthrough-eligible (speed=1, not force-PCM). */
internal fun isBitstreamAudioMimeOrCodecs(sampleMimeType: String?, codecs: String?): Boolean {
    if (sampleMimeType != null) {
        when (sampleMimeType) {
            androidx.media3.common.MimeTypes.AUDIO_E_AC3,
            androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC,
            androidx.media3.common.MimeTypes.AUDIO_AC3,
            androidx.media3.common.MimeTypes.AUDIO_AC4,
            androidx.media3.common.MimeTypes.AUDIO_TRUEHD,
            androidx.media3.common.MimeTypes.AUDIO_DTS,
            androidx.media3.common.MimeTypes.AUDIO_DTS_HD,
            androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS -> return true
        }
        if (sampleMimeType.startsWith("audio/vnd.dts")) return true
    }
    if (codecs != null) {
        return codecs.contains("ac-3", ignoreCase = true) ||
            codecs.contains("ac-4", ignoreCase = true) ||
            codecs.contains("ec-3", ignoreCase = true) ||
            codecs.contains("dts", ignoreCase = true) ||
            codecs.contains("truehd", ignoreCase = true) ||
            codecs.contains("dtshd", ignoreCase = true)
    }
    return false
}
