package com.nuvio.tv.ui.screens.player

import kotlin.math.ceil

internal data class SubtitleFastAudioProbePlan(
    val playbackSpeed: Float,
    val activeDecodeTimeoutMs: Long,
    val estimatedFileBitrateBps: Int?
)

/** Keeps fast probes bounded without asking a high-bitrate remux to stream at an unrealistic 8x. */
internal object SubtitleFastAudioProbePolicy {
    private const val TARGET_AUDIO_MS = 60_000L
    private const val MIN_ACTIVE_TIMEOUT_MS = 20_000L
    private const val ACTIVE_TIMEOUT_GRACE_MS = 10_000L

    private const val EIGHT_X_MAX_BITRATE_BPS = 15_000_000
    private const val FOUR_X_MAX_BITRATE_BPS = 35_000_000
    private const val TWO_X_MAX_BITRATE_BPS = 120_000_000

    fun plan(fileSizeBytes: Long?, durationMs: Long): SubtitleFastAudioProbePlan {
        val bitrateBps = PlayerBitrateEstimator.fileBitrateBps(fileSizeBytes, durationMs)
        val speed = when {
            bitrateBps == null -> 8f
            bitrateBps <= EIGHT_X_MAX_BITRATE_BPS -> 8f
            bitrateBps <= FOUR_X_MAX_BITRATE_BPS -> 4f
            bitrateBps <= TWO_X_MAX_BITRATE_BPS -> 2f
            else -> 1f
        }
        return planForSpeed(speed, bitrateBps)
    }

    fun planForSpeed(
        playbackSpeed: Float,
        estimatedFileBitrateBps: Int? = null
    ): SubtitleFastAudioProbePlan {
        val speed = playbackSpeed.takeIf { it == 1f || it == 2f || it == 4f || it == 8f } ?: 1f
        val targetWallMs = ceil(TARGET_AUDIO_MS / speed.toDouble()).toLong()
        return SubtitleFastAudioProbePlan(
            playbackSpeed = speed,
            activeDecodeTimeoutMs = (targetWallMs + ACTIVE_TIMEOUT_GRACE_MS)
                .coerceAtLeast(MIN_ACTIVE_TIMEOUT_MS),
            estimatedFileBitrateBps = estimatedFileBitrateBps
        )
    }

    /** Back off one tier when a probe timed out before decoding even half of its audio target. */
    fun afterProbe(
        current: SubtitleFastAudioProbePlan,
        result: SubtitleFastAudioProbeResult
    ): SubtitleFastAudioProbePlan {
        if (
            result.termination != SubtitleFastAudioProbeTermination.WALL_TIMEOUT ||
            result.decodedDurationMs >= TARGET_AUDIO_MS / 2L ||
            current.playbackSpeed <= 1f
        ) {
            return current
        }
        return planForSpeed(
            playbackSpeed = current.playbackSpeed / 2f,
            estimatedFileBitrateBps = current.estimatedFileBitrateBps
        )
    }
}
