package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-device policy checks for tunneling + cold-start autoplay routing.
 * These are pure (no ExoPlayer) so they run on every CI unit test pass.
 */
class PlayerStartupAndTunnelPolicyTest {

    // ── effective tunneling ──────────────────────────────────────────

    @Test
    fun `tunneling on when setting on, not safe audio, not mpv`() {
        assertTrue(
            resolveEffectiveTunneling(
                tunnelingSettingEnabled = true,
                safeAudioMode = false,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }

    @Test
    fun `tunneling off when setting disabled`() {
        assertFalse(
            resolveEffectiveTunneling(
                tunnelingSettingEnabled = false,
                safeAudioMode = false,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }

    @Test
    fun `tunneling off under safe audio even if setting on`() {
        assertFalse(
            resolveEffectiveTunneling(
                tunnelingSettingEnabled = true,
                safeAudioMode = true,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }

    @Test
    fun `tunneling off for mpv engine`() {
        assertFalse(
            resolveEffectiveTunneling(
                tunnelingSettingEnabled = true,
                safeAudioMode = false,
                engine = InternalPlayerEngine.MVP_PLAYER
            )
        )
    }

    @Test
    fun `tunneling on for auto resolved to exo`() {
        // AUTO is resolved before this helper is called; still document EXO path.
        assertTrue(
            resolveEffectiveTunneling(
                tunnelingSettingEnabled = true,
                safeAudioMode = false,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }

    // ── startup plan ─────────────────────────────────────────────────

    @Test
    fun `no video starts on ready`() {
        assertEquals(
            StartupPlaybackPlan.READY_NO_VIDEO,
            resolveStartupPlaybackPlan(hasVideoTrack = false, effectiveTunneling = false)
        )
        assertEquals(
            StartupPlaybackPlan.READY_NO_VIDEO,
            resolveStartupPlaybackPlan(hasVideoTrack = false, effectiveTunneling = true)
        )
    }

    @Test
    fun `surface path waits for first frame`() {
        assertEquals(
            StartupPlaybackPlan.WAIT_FIRST_FRAME,
            resolveStartupPlaybackPlan(hasVideoTrack = true, effectiveTunneling = false)
        )
    }

    @Test
    fun `tunnel path uses short ready fallback`() {
        assertEquals(
            StartupPlaybackPlan.TUNNEL_WAIT_THEN_FALLBACK,
            resolveStartupPlaybackPlan(hasVideoTrack = true, effectiveTunneling = true)
        )
    }

    // ── bitstream detection (seek must stay direct, not PCM) ─────────

    @Test
    fun `bitstream mime types are recognized`() {
        val mimes = listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS,
            "audio/vnd.dts.hd;profile=lbr"
        )
        mimes.forEach { mime ->
            assertTrue("expected bitstream: $mime", isBitstreamAudioMimeOrCodecs(mime, null))
        }
    }

    @Test
    fun `pcm and aac are not bitstream for passthrough gate`() {
        assertFalse(isBitstreamAudioMimeOrCodecs(MimeTypes.AUDIO_RAW, null))
        assertFalse(isBitstreamAudioMimeOrCodecs(MimeTypes.AUDIO_AAC, null))
        assertFalse(isBitstreamAudioMimeOrCodecs(null, null))
    }

    @Test
    fun `codecs string detects bitstream when mime missing`() {
        assertTrue(isBitstreamAudioMimeOrCodecs(null, "ec-3"))
        assertTrue(isBitstreamAudioMimeOrCodecs(null, "ac-3"))
        assertTrue(isBitstreamAudioMimeOrCodecs(null, "truehd"))
        assertTrue(isBitstreamAudioMimeOrCodecs(null, "dts"))
        assertFalse(isBitstreamAudioMimeOrCodecs(null, "mp4a.40.2"))
    }

    // ── matrix: setting x safe x engine ───────────────────────────────

    @Test
    fun `effective tunneling truth table`() {
        data class Row(
            val setting: Boolean,
            val safe: Boolean,
            val engine: InternalPlayerEngine,
            val expected: Boolean
        )
        val rows = listOf(
            Row(true, false, InternalPlayerEngine.EXOPLAYER, true),
            Row(true, true, InternalPlayerEngine.EXOPLAYER, false),
            Row(true, false, InternalPlayerEngine.MVP_PLAYER, false),
            Row(true, true, InternalPlayerEngine.MVP_PLAYER, false),
            Row(false, false, InternalPlayerEngine.EXOPLAYER, false),
            Row(false, true, InternalPlayerEngine.EXOPLAYER, false)
        )
        rows.forEach { row ->
            assertEquals(
                "setting=${row.setting} safe=${row.safe} engine=${row.engine}",
                row.expected,
                resolveEffectiveTunneling(row.setting, row.safe, row.engine)
            )
        }
    }
}
