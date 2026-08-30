package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPassthroughPolicyTest {

    private val allBitstreamMimeTypes = listOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_DTS_X,
        MimeTypes.AUDIO_AC4
    )

    // ── The load-bearing guarantee: the default must change nothing ──

    @Test
    fun allowAll_deniesNothing_forEveryBitstreamFormat() {
        for (mime in allBitstreamMimeTypes) {
            assertFalse(
                "ALLOW_ALL must not deny $mime",
                AudioPassthroughPolicy.ALLOW_ALL.deniesPassthrough(mime)
            )
        }
    }

    @Test
    fun allowAll_deniesNothing_forNonBitstreamAndNullFormats() {
        assertFalse(AudioPassthroughPolicy.ALLOW_ALL.deniesPassthrough(MimeTypes.AUDIO_RAW))
        assertFalse(AudioPassthroughPolicy.ALLOW_ALL.deniesPassthrough(MimeTypes.AUDIO_AAC))
        assertFalse(AudioPassthroughPolicy.ALLOW_ALL.deniesPassthrough(null))
        assertFalse(AudioPassthroughPolicy.ALLOW_ALL.deniesPassthrough(""))
    }

    @Test
    fun defaultConstructor_isAllowAll_andIsInert() {
        assertEquals(AudioPassthroughPolicy.ALLOW_ALL, AudioPassthroughPolicy())
        assertTrue(AudioPassthroughPolicy().allowsEverything())
    }

    // ── Each switch denies its own group and nothing else ──

    @Test
    fun eachSwitchDeniesExactlyItsOwnGroup() {
        val cases = listOf(
            AudioPassthroughPolicy(allowAc3 = false) to Group.AC3,
            AudioPassthroughPolicy(allowEac3 = false) to Group.EAC3,
            AudioPassthroughPolicy(allowTrueHd = false) to Group.TRUEHD,
            AudioPassthroughPolicy(allowDts = false) to Group.DTS,
            AudioPassthroughPolicy(allowDtsHd = false) to Group.DTS_HD
        )
        for ((policy, deniedGroup) in cases) {
            for (mime in allBitstreamMimeTypes) {
                val expectDenied = AudioPassthroughPolicy.groupOf(mime) == deniedGroup
                assertEquals(
                    "policy denying $deniedGroup on $mime",
                    expectDenied,
                    policy.deniesPassthrough(mime)
                )
            }
        }
    }

    @Test
    fun eac3Switch_coversJoc_soAtmosFollowsDolbyDigitalPlus() {
        val policy = AudioPassthroughPolicy(allowEac3 = false)
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3_JOC))
    }

    @Test
    fun allSwitchesOff_deniesEveryDeniableFormat_andOnlyThose() {
        val policy = AudioPassthroughPolicy(
            allowAc3 = false,
            allowEac3 = false,
            allowTrueHd = false,
            allowDts = false,
            allowDtsHd = false
        )
        assertFalse(policy.allowsEverything())
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_AC3))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3_JOC))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_TRUEHD))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_DTS))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
        // No fallback decoder exists for these, so they stay on the platform's answer.
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_DTS_EXPRESS))
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_DTS_X))
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_AC4))
    }

    // ── The prefix trap ──

    @Test
    fun dtsExpress_isNotMatchedAsDtsHd_despiteSharingItsPrefix() {
        assertTrue(
            "test premise: DTS Express must share the DTS-HD prefix",
            MimeTypes.AUDIO_DTS_EXPRESS.startsWith(MimeTypes.AUDIO_DTS_HD)
        )
        assertNull(AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_DTS_EXPRESS))
        assertFalse(
            AudioPassthroughPolicy(allowDtsHd = false)
                .deniesPassthrough(MimeTypes.AUDIO_DTS_EXPRESS)
        )
    }

    // ── The Decoder Priority = "Device only" guard ──

    @Test
    fun noSoftwareDecoders_deniesNothing_evenWithEverySwitchOff() {
        val policy = AudioPassthroughPolicy(
            allowAc3 = false,
            allowEac3 = false,
            allowTrueHd = false,
            allowDts = false,
            allowDtsHd = false,
            softwareDecodersAvailable = false
        )
        for (mime in allBitstreamMimeTypes) {
            assertFalse(
                "with no fallback decoders, $mime must keep passthrough",
                policy.deniesPassthrough(mime)
            )
        }
    }

    // ── Group mapping ──

    @Test
    fun everyDeniableMimeHasABundledFfmpegDecoder() {
        val ffmpegCodecByMime = mapOf(
            MimeTypes.AUDIO_AC3 to "ac3",
            MimeTypes.AUDIO_E_AC3 to "eac3",
            MimeTypes.AUDIO_E_AC3_JOC to "eac3",
            MimeTypes.AUDIO_TRUEHD to "truehd",
            MimeTypes.AUDIO_DTS to "dca",
            MimeTypes.AUDIO_DTS_HD to "dca"
        )
        for (mime in allBitstreamMimeTypes) {
            if (AudioPassthroughPolicy.groupOf(mime) != null) {
                assertTrue(
                    "$mime is deniable but has no bundled FFmpeg decoder",
                    ffmpegCodecByMime.containsKey(mime)
                )
            }
        }
    }

    @Test
    fun groupOf_mapsOnlyTheFiveDeniableFamilies() {
        assertEquals(Group.AC3, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_AC3))
        assertEquals(Group.EAC3, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_E_AC3))
        assertEquals(Group.EAC3, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(Group.TRUEHD, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_TRUEHD))
        assertEquals(Group.DTS, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_DTS))
        assertEquals(Group.DTS_HD, AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_DTS_HD))
        assertNull(AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_AC4))
        assertNull(AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_DTS_X))
        assertNull(AudioPassthroughPolicy.groupOf(MimeTypes.AUDIO_RAW))
        assertNull(AudioPassthroughPolicy.groupOf(null))
    }

    // ── Learned rejections deny independently of the user switches ──

    @Test
    fun learnedRejection_deniesGroup_evenWhenUserSwitchIsOn() {
        val policy = AudioPassthroughPolicy(learnedDeniedGroups = setOf(Group.DTS_HD))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_AC3))
        assertFalse(policy.allowsEverything())
    }

    @Test
    fun learnedRejection_respectsNoSoftwareDecoders() {
        val policy = AudioPassthroughPolicy(
            learnedDeniedGroups = setOf(Group.DTS_HD),
            softwareDecodersAvailable = false
        )
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_DTS_HD))
    }

    @Test
    fun learnedRejection_andUserSwitch_denyIndependently() {
        val policy = AudioPassthroughPolicy(allowDts = false, learnedDeniedGroups = setOf(Group.EAC3))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_DTS))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3))
        assertTrue(policy.deniesPassthrough(MimeTypes.AUDIO_E_AC3_JOC))
        assertFalse(policy.deniesPassthrough(MimeTypes.AUDIO_AC3))
    }
}
