package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes

internal enum class AudioRoutingMode {
    PASSTHROUGH_IEC,
    PASSTHROUGH_DIRECT,
    TRANSCODE_AC3,
    PCM
}

internal data class AudioRoutingSnapshot(
    val mode: AudioRoutingMode,
    val outputFormat: String,
    val isFallback: Boolean
)

internal fun isSurroundOrHbrMime(mime: String?, channelCount: Int): Boolean {
    if (mime == null) return false
    return when (mime) {
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_X,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_AC4 -> true
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_EXPRESS -> channelCount > 2
        else -> channelCount > 2
    }
}

internal fun resolveAudioRoutingSnapshot(
    sourceMime: String?,
    sourceChannelCount: Int,
    isIecActive: Boolean,
    isTranscodingAc3: Boolean,
    isAudioPathActive: Boolean,
    sinkMime: String?,
    sinkChannelCount: Int
): AudioRoutingSnapshot? {
    if (sourceMime == null) return null
    val isSourceSurroundOrHbr = isSurroundOrHbrMime(sourceMime, sourceChannelCount)

    return when {
        isIecActive -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PASSTHROUGH_IEC,
                outputFormat = "passthrough (IEC)",
                isFallback = false
            )
        }
        isTranscodingAc3 -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.TRANSCODE_AC3,
                outputFormat = "transcode AC-3 5.1",
                isFallback = true
            )
        }
        sinkMime != null && sinkMime != MimeTypes.AUDIO_RAW && PassthroughWaterLevelPacer.isPassthroughMime(sinkMime) -> {
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PASSTHROUGH_DIRECT,
                outputFormat = "passthrough (direct)",
                isFallback = false
            )
        }
        sinkMime == MimeTypes.AUDIO_RAW || sinkMime != null || isAudioPathActive -> {
            val pcmChannels = sinkChannelCount
            val channelText = when (pcmChannels) {
                1 -> "mono"
                2 -> "2.0"
                6 -> "5.1"
                8 -> "7.1"
                in 3..Int.MAX_VALUE -> "$pcmChannels ch"
                else -> ""
            }
            val baseLabel = if (channelText.isNotEmpty()) "PCM $channelText" else "PCM"
            val isFallback = if (pcmChannels in 1 until sourceChannelCount) {
                true
            } else {
                isSourceSurroundOrHbr && pcmChannels <= 2
            }
            val displayLabel = if (isFallback) "$baseLabel (fallback)" else baseLabel
            AudioRoutingSnapshot(
                mode = AudioRoutingMode.PCM,
                outputFormat = displayLabel,
                isFallback = isFallback
            )
        }
        else -> null
    }
}

internal fun PlayerRuntimeController.getAudioRoutingSnapshot(): AudioRoutingSnapshot? {
    val player = _exoPlayer ?: return null
    val sourceFormat = player.audioFormat ?: return null
    val sourceMime = sourceFormat.sampleMimeType ?: return null

    val sink = playbackSpeedAwareAudioSink
    val sinkFormat = sink?.activeInputFormat
    val ffmpeg = ffmpegAudioRenderer

    val isTranscodingAc3 = (runCatching { ffmpeg?.isTranscodingToAc3() }.getOrNull() == true) ||
        (sourceMime != MimeTypes.AUDIO_AC3 && sinkFormat?.sampleMimeType == MimeTypes.AUDIO_AC3)

    return resolveAudioRoutingSnapshot(
        sourceMime = sourceMime,
        sourceChannelCount = sourceFormat.channelCount,
        isIecActive = sink?.isIecHbrActive() == true,
        isTranscodingAc3 = isTranscodingAc3,
        isAudioPathActive = ffmpeg?.isAudioPathActive() == true,
        sinkMime = sinkFormat?.sampleMimeType,
        sinkChannelCount = sinkFormat?.channelCount ?: -1
    )
}
