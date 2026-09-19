package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

object SurroundFormatResolver {

    data class DirectSupport(
        val ac3: Boolean,
        val eac3: Boolean,
        val trueHd: Boolean,
        val dts: Boolean,
        val dtsHd: Boolean
    )

    data class Resolution(
        val policy: AudioPassthroughPolicy,
        val transcodePreferred: Boolean,
        val inferredChannelTarget: Int?
    ) {
        companion object {
            val INERT = Resolution(AudioPassthroughPolicy.ALLOW_ALL, false, null)
        }
    }

    private val groupRepresentativeMimes = listOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD
    )

    fun resolve(
        manualMode: Boolean,
        allowAc3: Boolean,
        allowEac3: Boolean,
        allowTrueHd: Boolean,
        allowDts: Boolean,
        allowDtsHd: Boolean,
        manualTranscodePreferred: Boolean,
        manualChannelTargetChannels: Int?,
        direct: DirectSupport?,
        rawMaxPcmChannels: Int?,
        routeIsBluetooth: Boolean,
        routeIsHdmiArc: Boolean,
        softwareDecodersAvailable: Boolean,
        forceOpticalActive: Boolean,
        learnedDeniedGroups: Set<AudioPassthroughPolicy.Group>
    ): Resolution {
        // Bluetooth: fully inert - the Bluetooth PCM machinery sits ahead of this policy.
        if (routeIsBluetooth) return Resolution.INERT

        // Force AC-3 Transcoding overrides the chain's real capabilities; Auto yields.
        if (!manualMode && forceOpticalActive) return Resolution.INERT

        val policy = if (manualMode) {
            AudioPassthroughPolicy(
                allowAc3 = allowAc3,
                allowEac3 = allowEac3,
                allowTrueHd = allowTrueHd,
                allowDts = allowDts,
                allowDtsHd = allowDtsHd,
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        } else if (direct == null) {
            // No probe (API < 29 or probe failure): Auto denies nothing. Learned denials
            // and the decoder guard still apply - they do not depend on the probe.
            AudioPassthroughPolicy(
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        } else {
            // The DTS-core corner: DTS-HD unclaimed with DTS claimed stays allowed unless
            // the raw report proves a multichannel-PCM chain, where lossless decode wins.
            val dtsHdAllowed = when {
                direct.dtsHd -> true
                direct.dts -> !(rawMaxPcmChannels != null && rawMaxPcmChannels > 2)
                else -> false
            }
            AudioPassthroughPolicy(
                allowAc3 = direct.ac3,
                allowEac3 = direct.eac3,
                allowTrueHd = direct.trueHd,
                allowDts = direct.dts,
                allowDtsHd = dtsHdAllowed,
                softwareDecodersAvailable = softwareDecodersAvailable,
                learnedDeniedGroups = learnedDeniedGroups
            )
        }

        val anythingDenied = groupRepresentativeMimes.any { policy.deniesPassthrough(it) }

        val transcodePreferred = when {
            !anythingDenied -> false
            manualMode -> manualTranscodePreferred
            // Chain-shape rule: a proven multichannel-PCM chain decodes losslessly; a
            // 2-channel chain (measured, or inferred from an HDMI ARC route whose PCM
            // profile is unreadable) prefers compressed 5.1 AC-3 - but only where the
            // chain actually claims AC-3.
            rawMaxPcmChannels != null && rawMaxPcmChannels > 2 -> false
            rawMaxPcmChannels == 2 -> direct?.ac3 == true
            rawMaxPcmChannels == null && routeIsHdmiArc -> direct?.ac3 == true
            else -> false
        }

        val inferredChannelTarget = when {
            manualChannelTargetChannels != null -> manualChannelTargetChannels
            !anythingDenied -> null
            rawMaxPcmChannels != null -> when {
                rawMaxPcmChannels >= 8 -> 8
                rawMaxPcmChannels >= 6 -> 6
                else -> 2
            }
            routeIsHdmiArc -> 2
            else -> null
        }

        return Resolution(policy, transcodePreferred, inferredChannelTarget)
    }
}
