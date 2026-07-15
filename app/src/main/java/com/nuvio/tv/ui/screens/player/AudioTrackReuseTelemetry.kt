package com.nuvio.tv.ui.screens.player

/**
 * Session counters for AudioTrack flush-reuse (passthrough/tunnel).
 * Fed into playback issue reports via [PlayerPlaybackAnalyticsDiagnostics.recordRawEventLine]
 * — no backend schema change; lines land in rawEventLines / rawEvents.
 */
internal enum class AudioTrackReuseOutcome {
    REUSED_PASSTHROUGH,
    REUSED_TUNNEL,
    SKIPPED_NOT_ELIGIBLE,
    SKIPPED_NO_TRACK,
    SKIPPED_CONFIG_MISMATCH,
    FAILED_REFLECTION,
    DISABLED
}

internal data class AudioTrackReuseSnapshot(
    val okPassthrough: Int,
    val okTunnel: Int,
    val skipNotEligible: Int,
    val skipNoTrack: Int,
    val skipConfigMismatch: Int,
    val failReflection: Int,
    val disabled: Int
) {
    val totalAttempts: Int
        get() = okPassthrough + okTunnel + skipNotEligible + skipNoTrack +
            skipConfigMismatch + failReflection + disabled

    val reuseOk: Int
        get() = okPassthrough + okTunnel
}

internal class AudioTrackReuseTelemetry {
    private val lock = Any()
    private var okPassthrough: Int = 0
    private var okTunnel: Int = 0
    private var skipNotEligible: Int = 0
    private var skipNoTrack: Int = 0
    private var skipConfigMismatch: Int = 0
    private var failReflection: Int = 0
    private var disabled: Int = 0

    fun reset() {
        synchronized(lock) {
            okPassthrough = 0
            okTunnel = 0
            skipNotEligible = 0
            skipNoTrack = 0
            skipConfigMismatch = 0
            failReflection = 0
            disabled = 0
        }
    }

    fun snapshot(): AudioTrackReuseSnapshot = synchronized(lock) {
        AudioTrackReuseSnapshot(
            okPassthrough = okPassthrough,
            okTunnel = okTunnel,
            skipNotEligible = skipNotEligible,
            skipNoTrack = skipNoTrack,
            skipConfigMismatch = skipConfigMismatch,
            failReflection = failReflection,
            disabled = disabled
        )
    }

    /**
     * Bumps counters. Returns a raw-event line for interesting outcomes, or null for the
     * common non-tunnel PCM path ([AudioTrackReuseOutcome.SKIPPED_NOT_ELIGIBLE]) so we
     * don't flood the report buffer on every normal seek.
     */
    fun record(outcome: AudioTrackReuseOutcome, sampleMimeType: String? = null): String? {
        synchronized(lock) {
            when (outcome) {
                AudioTrackReuseOutcome.REUSED_PASSTHROUGH -> okPassthrough++
                AudioTrackReuseOutcome.REUSED_TUNNEL -> okTunnel++
                AudioTrackReuseOutcome.SKIPPED_NOT_ELIGIBLE -> skipNotEligible++
                AudioTrackReuseOutcome.SKIPPED_NO_TRACK -> skipNoTrack++
                AudioTrackReuseOutcome.SKIPPED_CONFIG_MISMATCH -> skipConfigMismatch++
                AudioTrackReuseOutcome.FAILED_REFLECTION -> failReflection++
                AudioTrackReuseOutcome.DISABLED -> disabled++
            }
        }
        if (outcome == AudioTrackReuseOutcome.SKIPPED_NOT_ELIGIBLE) {
            return null
        }
        val mime = sampleMimeType?.takeIf { it.isNotBlank() } ?: "n/a"
        return "AUDIO_TRACK_REUSE: result=${outcome.name} mime=$mime"
    }

    fun summaryLine(): String {
        val s = snapshot()
        return "AUDIO_TRACK_REUSE_SUMMARY: " +
            "okPt=${s.okPassthrough} okTunnel=${s.okTunnel} " +
            "skipEligible=${s.skipNotEligible} skipNoTrack=${s.skipNoTrack} " +
            "skipConfig=${s.skipConfigMismatch} fail=${s.failReflection} " +
            "disabled=${s.disabled} total=${s.totalAttempts} reuseOk=${s.reuseOk}"
    }
}
