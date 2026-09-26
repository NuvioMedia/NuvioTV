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

/** The build-time facts a surround resolution depends on besides the settings and the chain. */
internal data class SurroundResolveInputs(
    val routeKey: String?,
    val isBluetooth: Boolean,
    val softwareDecodersAvailable: Boolean,
    val forceOpticalActive: Boolean,
    val effectiveDownmixEnabled: Boolean,
    val effectiveAudioOutputChannels: AudioOutputChannels
)

/** One surround resolution: what may bitstream, the app-side channel target, and what is transcoded. */
internal data class SurroundResolveResult(
    val resolution: SurroundFormatResolver.Resolution,
    val targetChannels: Int?,
    val downmixEnabled: Boolean,
    val audioOutputChannels: AudioOutputChannels,
    val deniedTranscodeMimes: Set<String>
)

// One resolution per player build: which formats may bitstream, how denied formats are
// handled, and the app-side decode channel target. Bluetooth skips the probe and resolver
// entirely - the PCM machinery already owns that route, and the policy stays ALLOW_ALL there.
// Also called again from the audio route callback: a snapshot taken while the HDMI link was
// down (a display mode change at title start hotplugs it) denies everything, and the title
// that is playing must not be left with that answer.
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
            routeIsHdmiArc = currentRouteKey != null &&
                (currentRouteKey.startsWith("type:hdmi_arc") ||
                    currentRouteKey.startsWith("type:hdmi_earc")),
            softwareDecodersAvailable = inputs.softwareDecodersAvailable,
            forceOpticalActive = inputs.forceOpticalActive,
            learnedDeniedGroups = learnedDeniedGroups
        )
    }
    // Denied formats decode on the app path at the resolved channel target. The
    // user's own downmix target still wins downward: an equal-or-lower layout the
    // user chose is kept; only a higher layout is capped to the resolved target.
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

// Resolve again for the title that is playing, after the audio route callback dropped the
// chain snapshot. Same shape as applyBluetoothAudioRouteInPlace: update the sink and the FFmpeg
// renderer in place, then nudge Media3 to reselect the audio track. Never rebuilds the player.
internal fun PlayerRuntimeController.applySurroundResolutionInPlace(reason: String) {
    if (_exoPlayer == null || isUsingMpvEngine()) return
    val inputs = surroundResolveInputs ?: return
    if (inputs.isBluetooth || currentAudioOutputRoute?.isBluetooth == true) return
    val settings = currentPlayerSettingsForReport
    val surround = resolveSurroundForRoute(
        context,
        settings,
        inputs.copy(routeKey = currentAudioOutputRoute?.key ?: inputs.routeKey)
    )
    val policy = surround.resolution.policy
    val changed = playbackSpeedAwareAudioSink?.setPassthroughPolicy(policy) == true
    currentAudioPassthroughPolicy = policy
    ffmpegAudioRenderer?.applyDownmixSettings(
        downmixEnabled = surround.downmixEnabled,
        audioOutputChannels = surround.audioOutputChannels,
        downmixNormalizationEnabled = !settings.maintainOriginalAudioOnDownmix,
        forceOpticalPassthrough = inputs.forceOpticalActive,
        deniedTranscodeMimes = surround.deniedTranscodeMimes
    )
    Log.i(
        PlayerRuntimeController.TAG,
        "SURROUND_RESOLVE_INPLACE: reason=$reason changed=$changed route=${inputs.routeKey} " +
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
