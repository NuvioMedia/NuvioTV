package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.player.DeniedTranscodePlanner
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.SurroundFormatMode

internal data class SurroundResolveInputs(
    val routeKey: String?,
    val isBluetooth: Boolean,
    val softwareDecodersAvailable: Boolean,
    val forceOpticalActive: Boolean,
    val effectiveDownmixEnabled: Boolean,
    val effectiveAudioOutputChannels: AudioOutputChannels
)

internal data class SurroundResolveResult(
    val resolution: SurroundFormatResolver.Resolution,
    val targetChannels: Int?,
    val downmixEnabled: Boolean,
    val audioOutputChannels: AudioOutputChannels,
    val deniedTranscodeMimes: Set<String>
)

internal fun resolveSurroundForRoute(
    context: Context,
    playerSettings: PlayerSettings,
    inputs: SurroundResolveInputs
): SurroundResolveResult {
    val currentRouteKey = inputs.routeKey
    val surroundResolution = if (inputs.isBluetooth) {
        SurroundFormatResolver.Resolution.INERT
    } else {
        val chainSnapshot = AudioChainProbe.snapshot(context, currentRouteKey)
        val learnedDeniedGroups = AudioRejectionReverifier.ledger.learnedFor(
            currentRouteKey,
            playerSettings.audioRejectionsConfirmed
        )
        SurroundFormatResolver.resolve(
            manualMode = playerSettings.surroundFormatMode == SurroundFormatMode.MANUAL,
            allowAc3 = playerSettings.allowAc3Passthrough,
            allowEac3 = playerSettings.allowEac3Passthrough,
            allowTrueHd = playerSettings.allowTruehdPassthrough,
            allowDts = playerSettings.allowDtsPassthrough,
            allowDtsHd = playerSettings.allowDtshdPassthrough,
            manualTranscodePreferred =
                playerSettings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3,
            manualChannelTargetChannels = when (playerSettings.surroundChannelTarget) {
                SurroundChannelTarget.AUTO -> null
                SurroundChannelTarget.CH_2_0 -> 2
                SurroundChannelTarget.CH_5_1 -> 6
                SurroundChannelTarget.CH_7_1 -> 8
            },
            direct = chainSnapshot.direct,
            rawMaxPcmChannels = chainSnapshot.maxPcmChannels,
            routeIsBluetooth = false,
            routeIsHdmiArc = SurroundFormatResolver.routeKeyIsHdmiArc(currentRouteKey),
            softwareDecodersAvailable = inputs.softwareDecodersAvailable,
            forceOpticalActive = inputs.forceOpticalActive,
            learnedDeniedGroups = learnedDeniedGroups
        )
    }
    val surroundTargetChannels = surroundResolution.inferredChannelTarget
    val surroundDownmixEnabled = inputs.effectiveDownmixEnabled || surroundTargetChannels != null
    val surroundAudioOutputChannels = when {
        surroundTargetChannels == null -> inputs.effectiveAudioOutputChannels
        inputs.effectiveDownmixEnabled &&
            inputs.effectiveAudioOutputChannels.channelCount <= surroundTargetChannels ->
            inputs.effectiveAudioOutputChannels
        else -> surroundTargetToOutputChannels(surroundTargetChannels, inputs.effectiveAudioOutputChannels)
    }
    val deniedTranscodeMimes = DeniedTranscodePlanner.effectiveTranscodeMimes(
        policy = surroundResolution.policy,
        transcodeDeniedToAc3 = surroundResolution.transcodePreferred,
        forcePassthroughActive = inputs.forceOpticalActive
    )
    return SurroundResolveResult(
        resolution = surroundResolution,
        targetChannels = surroundTargetChannels,
        downmixEnabled = surroundDownmixEnabled,
        audioOutputChannels = surroundAudioOutputChannels,
        deniedTranscodeMimes = deniedTranscodeMimes
    )
}

internal fun PlayerRuntimeController.applySurroundResolutionInPlace(reason: String) {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    val inputs = surroundResolveInputs ?: return
    if (inputs.isBluetooth || currentAudioOutputRoute?.isBluetooth == true) return
    val settings = currentPlayerSettingsForReport
    val routeKey = currentAudioOutputRoute?.key ?: inputs.routeKey
    val surround = resolveSurroundForRoute(
        context,
        settings,
        inputs.copy(routeKey = routeKey)
    )
    val policy = surround.resolution.policy
    val policyChanged = playbackSpeedAwareAudioSink?.setPassthroughPolicy(policy) == true
    currentAudioPassthroughPolicy = policy
    ffmpegAudioRenderer?.applyDownmixSettings(
        downmixEnabled = surround.downmixEnabled,
        audioOutputChannels = surround.audioOutputChannels,
        downmixNormalizationEnabled = !settings.maintainOriginalAudioOnDownmix,
        forceOpticalPassthrough = inputs.forceOpticalActive,
        deniedTranscodeMimes = surround.deniedTranscodeMimes
    )
    val changed = surroundResolveNeedsReselect(lastAppliedSurroundResolve, surround) || policyChanged
    lastAppliedSurroundResolve = surround
    Log.i(
        PlayerRuntimeController.TAG,
        "SURROUND_RESOLVE_INPLACE: reason=$reason changed=$changed route=$routeKey " +
            "policy=[ac3=${policy.allowAc3} eac3=${policy.allowEac3} truehd=${policy.allowTrueHd} " +
            "dts=${policy.allowDts} dtshd=${policy.allowDtsHd} learned=${policy.learnedDeniedGroups}] " +
            "transcodePreferred=${surround.resolution.transcodePreferred} " +
            "channelTarget=${surround.targetChannels}"
    )
    queuePlaybackRawEventLine(
        "surround_resolve_inplace reason=$reason changed=$changed " +
            "ac3=${policy.allowAc3} eac3=${policy.allowEac3} truehd=${policy.allowTrueHd} " +
            "dts=${policy.allowDts} dtshd=${policy.allowDtsHd} " +
            "transcodePreferred=${surround.resolution.transcodePreferred} " +
            "channelTarget=${surround.targetChannels}"
    )
    if (!changed) return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

internal fun surroundResolveNeedsReselect(
    previous: SurroundResolveResult?,
    next: SurroundResolveResult
): Boolean = previous != next
