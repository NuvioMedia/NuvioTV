package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.core.player.SurroundFormatResolver.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundFormatResolverTest {

    private val allClaimed = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)
    private val nothingClaimed = DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)

    private fun resolve(
        manualMode: Boolean = false,
        allowAc3: Boolean = true,
        allowEac3: Boolean = true,
        allowTrueHd: Boolean = true,
        allowDts: Boolean = true,
        allowDtsHd: Boolean = true,
        manualTranscodePreferred: Boolean = false,
        manualChannelTargetChannels: Int? = null,
        direct: DirectSupport? = allClaimed,
        rawMaxPcmChannels: Int? = 8,
        routeIsBluetooth: Boolean = false,
        routeIsHdmiArc: Boolean = false,
        softwareDecodersAvailable: Boolean = true,
        forceOpticalActive: Boolean = false,
        learnedDeniedGroups: Set<Group> = emptySet()
    ): Resolution = SurroundFormatResolver.resolve(
        manualMode = manualMode,
        allowAc3 = allowAc3,
        allowEac3 = allowEac3,
        allowTrueHd = allowTrueHd,
        allowDts = allowDts,
        allowDtsHd = allowDtsHd,
        manualTranscodePreferred = manualTranscodePreferred,
        manualChannelTargetChannels = manualChannelTargetChannels,
        direct = direct,
        rawMaxPcmChannels = rawMaxPcmChannels,
        routeIsBluetooth = routeIsBluetooth,
        routeIsHdmiArc = routeIsHdmiArc,
        softwareDecodersAvailable = softwareDecodersAvailable,
        forceOpticalActive = forceOpticalActive,
        learnedDeniedGroups = learnedDeniedGroups
    )

    @Test
    fun bluetoothRoute_isFullyInert_inBothModes() {
        assertEquals(Resolution.INERT, resolve(routeIsBluetooth = true, direct = nothingClaimed))
        assertEquals(
            Resolution.INERT,
            resolve(manualMode = true, routeIsBluetooth = true, allowDts = false, manualTranscodePreferred = true)
        )
    }

    @Test
    fun forceOptical_makesAutoInert() {
        assertEquals(Resolution.INERT, resolve(forceOpticalActive = true, direct = nothingClaimed))
    }

    @Test
    fun forceOptical_doesNotSuppressManualSwitches() {
        val r = resolve(manualMode = true, forceOpticalActive = true, allowDts = false)
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS))
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
    }

    @Test
    fun auto_probeNull_deniesNothing() {
        val r = resolve(direct = null)
        assertTrue(r.policy.allowsEverything())
        assertFalse(r.transcodePreferred)
        assertNull(r.inferredChannelTarget)
    }

    @Test
    fun auto_probeNull_learnedDenialsStillApply() {
        val r = resolve(direct = null, learnedDeniedGroups = setOf(Group.DTS_HD))
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
    }

    @Test
    fun auto_claimedFormatsPassThrough_unclaimedAreDenied() {
        val direct = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = false, dtsHd = false)
        val r = resolve(direct = direct)
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_AC3))
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3))
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3_JOC))
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS))
    }

    @Test
    fun auto_learnedDenial_overridesAClaimedFormat() {
        val r = resolve(direct = allClaimed, learnedDeniedGroups = setOf(Group.TRUEHD))
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun auto_deviceOnlyDecoders_denyNothing() {
        val r = resolve(direct = nothingClaimed, softwareDecodersAvailable = false)
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
        assertFalse(r.transcodePreferred)
        assertNull(r.inferredChannelTarget)
    }

    @Test
    fun dtsCoreKeep_dtsHdUnclaimedWithDtsClaimed_staysAllowed_onTwoChannelChain() {
        val direct = allClaimed.copy(dtsHd = false)
        val r = resolve(direct = direct, rawMaxPcmChannels = 2)
        assertFalse("core-keep: DTS-HD must stay allowed", r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun dtsCoreKeep_staysAllowed_whenPcmCountUnreadable() {
        val direct = allClaimed.copy(dtsHd = false)
        val r = resolve(direct = direct, rawMaxPcmChannels = null)
        assertFalse(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun dtsCoreKeep_yieldsToLosslessDecode_onProvenMultichannelChain() {
        val direct = allClaimed.copy(dtsHd = false)
        val r = resolve(direct = direct, rawMaxPcmChannels = 6)
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun dtsHdUnclaimed_withDtsAlsoUnclaimed_isDeniedOutright() {
        val direct = allClaimed.copy(dts = false, dtsHd = false)
        val r = resolve(direct = direct, rawMaxPcmChannels = 2)
        assertTrue(r.policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun deniedHandling_multichannelPcmChain_prefersLosslessDecode() {
        val r = resolve(direct = allClaimed.copy(trueHd = false), rawMaxPcmChannels = 6)
        assertFalse(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_twoChannelChain_prefersAc3Transcode_whenAc3Claimed() {
        val r = resolve(direct = allClaimed.copy(trueHd = false, dtsHd = false, dts = false), rawMaxPcmChannels = 2)
        assertTrue(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_twoChannelChain_fallsBackToDecode_whenAc3Unclaimed() {
        val direct = DirectSupport(ac3 = false, eac3 = true, trueHd = false, dts = false, dtsHd = false)
        val r = resolve(direct = direct, rawMaxPcmChannels = 2)
        assertFalse(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_arcRouteWithUnreadablePcm_infersTwoChannel_andPrefersTranscode() {
        val r = resolve(
            direct = allClaimed.copy(trueHd = false),
            rawMaxPcmChannels = null,
            routeIsHdmiArc = true
        )
        assertTrue(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_unreadablePcmOffArc_staysConservativeDecode() {
        val r = resolve(direct = allClaimed.copy(trueHd = false), rawMaxPcmChannels = null)
        assertFalse(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_nothingDenied_neverPrefersTranscode() {
        val r = resolve(direct = allClaimed, rawMaxPcmChannels = 2)
        assertFalse(r.transcodePreferred)
    }

    @Test
    fun deniedHandling_manualMode_passesTheUserChoiceThrough() {
        val on = resolve(manualMode = true, allowDts = false, manualTranscodePreferred = true)
        val off = resolve(manualMode = true, allowDts = false, manualTranscodePreferred = false)
        assertTrue(on.transcodePreferred)
        assertFalse(off.transcodePreferred)
    }

    @Test
    fun deniedHandling_manualChoiceIsMoot_whenNothingIsDenied() {
        val r = resolve(manualMode = true, manualTranscodePreferred = true)
        assertFalse(r.transcodePreferred)
    }

    @Test
    fun channelTarget_snapsToProvenTiersOnly() {
        val denied = allClaimed.copy(trueHd = false)
        assertEquals(8, resolve(direct = denied, rawMaxPcmChannels = 8).inferredChannelTarget)
        assertEquals(6, resolve(direct = denied, rawMaxPcmChannels = 7).inferredChannelTarget)
        assertEquals(6, resolve(direct = denied, rawMaxPcmChannels = 6).inferredChannelTarget)
        assertEquals(2, resolve(direct = denied, rawMaxPcmChannels = 5).inferredChannelTarget)
        assertEquals(2, resolve(direct = denied, rawMaxPcmChannels = 4).inferredChannelTarget)
        assertEquals(2, resolve(direct = denied, rawMaxPcmChannels = 3).inferredChannelTarget)
        assertEquals(2, resolve(direct = denied, rawMaxPcmChannels = 2).inferredChannelTarget)
    }

    @Test
    fun channelTarget_nothingDenied_leavesSettingsUntouched() {
        assertNull(resolve(direct = allClaimed, rawMaxPcmChannels = 6).inferredChannelTarget)
    }

    @Test
    fun routeKey_isArcOnlyForTheArcType() {
        assertTrue(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_arc|name:soundbar"))
        assertTrue(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_arc"))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_earc|name:soundbar"))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_earc"))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi|name:tv"))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_arc_extra|name:x"))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc(null))
        assertFalse(SurroundFormatResolver.routeKeyIsHdmiArc("hdmi_arc"))
    }

    @Test
    fun earcRoute_withUnreadablePcm_doesNotInferStereoOrAc3() {
        val earc = SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_earc|name:living_room")
        val r = resolve(
            direct = allClaimed.copy(trueHd = false, dtsHd = false),
            rawMaxPcmChannels = null,
            routeIsHdmiArc = earc
        )
        assertFalse(r.transcodePreferred)
        assertNull(r.inferredChannelTarget)
    }

    @Test
    fun arcRouteKey_withUnreadablePcm_stillInfersStereoAndAc3() {
        val arc = SurroundFormatResolver.routeKeyIsHdmiArc("type:hdmi_arc|name:soundbar")
        val r = resolve(
            direct = allClaimed.copy(trueHd = false, dtsHd = false),
            rawMaxPcmChannels = null,
            routeIsHdmiArc = arc
        )
        assertTrue(r.transcodePreferred)
        assertEquals(2, r.inferredChannelTarget)
    }

    @Test
    fun measuredChannels_winOnEarcEvenWhenTheRouteIsNotArc() {
        val denied = allClaimed.copy(trueHd = false)
        val stereo = resolve(direct = denied, rawMaxPcmChannels = 2, routeIsHdmiArc = false)
        assertTrue(stereo.transcodePreferred)
        assertEquals(2, stereo.inferredChannelTarget)
        val sevenOne = resolve(direct = denied, rawMaxPcmChannels = 8, routeIsHdmiArc = false)
        assertFalse(sevenOne.transcodePreferred)
        assertEquals(8, sevenOne.inferredChannelTarget)
    }

    @Test
    fun measuredMultichannel_winsOverAnArcRoute() {
        val r = resolve(
            direct = allClaimed.copy(trueHd = false),
            rawMaxPcmChannels = 8,
            routeIsHdmiArc = true
        )
        assertFalse(r.transcodePreferred)
        assertEquals(8, r.inferredChannelTarget)
    }

    @Test
    fun channelTarget_arcRouteWithUnreadablePcm_infersStereo() {
        val r = resolve(direct = allClaimed.copy(trueHd = false), rawMaxPcmChannels = null, routeIsHdmiArc = true)
        assertEquals(2, r.inferredChannelTarget)
    }

    @Test
    fun channelTarget_unreadablePcmOffArc_leavesSettingsUntouched() {
        val r = resolve(direct = allClaimed.copy(trueHd = false), rawMaxPcmChannels = null)
        assertNull(r.inferredChannelTarget)
    }

    @Test
    fun channelTarget_explicitManualValue_winsInBothModes() {
        assertEquals(
            3,
            resolve(manualMode = true, allowDts = false, manualChannelTargetChannels = 3).inferredChannelTarget
        )
        assertEquals(
            6,
            resolve(direct = allClaimed.copy(trueHd = false), manualChannelTargetChannels = 6).inferredChannelTarget
        )
    }

    @Test
    fun channelTarget_manualModeWithAutoTarget_stillInfersFromTheSink() {
        val r = resolve(manualMode = true, allowTrueHd = false, rawMaxPcmChannels = 6)
        assertEquals(6, r.inferredChannelTarget)
    }
}
