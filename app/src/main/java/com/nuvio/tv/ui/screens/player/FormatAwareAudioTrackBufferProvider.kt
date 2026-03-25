package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Format-aware AudioTrack buffer size provider.
 *
 * Uses empirically-tested, per-codec buffer sizes instead of ExoPlayer's
 * generic defaults.  The standard Android AudioTrack minimum buffer is often
 * too small for high-bitrate lossless codecs (DTS-HD MA, TrueHD) which causes
 * micro-stuttering and frame drops on devices like NVIDIA Shield Pro.
 *
 * Buffer values are derived from real-world testing across multiple Android TV
 * devices and verified against open-source media-centre implementations:
 *
 *   DTS-HD MA / DTS-HD HR : 4 × 30 720  =  122 880 bytes
 *   Dolby TrueHD          : 2 × 61 440  =  122 880 bytes
 *   DTS Core              : 8 ×  5 462  =   43 696 bytes
 *   AC3  (Dolby Digital)  : max(platform_min × 3, 1 536 × 8)
 *   EAC3 (DD+)            : 2 × 10 752  =   21 504 bytes
 *   PCM                   : ~200 ms target (32-64 ms periods)
 */
@UnstableApi
internal class FormatAwareAudioTrackBufferProvider :
    DefaultAudioSink.AudioTrackBufferSizeProvider {

    companion object {
        private const val TAG = "AudioTrackBuffer"

        // ─── Per-codec buffer constants (empirically tested) ─────────────

        // DTS-HD Master Audio / DTS-HD High Resolution
        private const val DTSHD_FRAME_SIZE = 30_720
        private const val DTSHD_MULTIPLIER = 4          // 122 880 bytes

        // Dolby TrueHD (Atmos lossless carrier)
        private const val TRUEHD_SIZE = 61_440
        private const val TRUEHD_MULTIPLIER = 2          // 122 880 bytes

        // DTS Core (covers 512 / 1024 / 2048 sample variants)
        private const val DTS_CORE_FRAME_SIZE = 5_462
        private const val DTS_CORE_MULTIPLIER = 8        //  43 696 bytes

        // AC3 (Dolby Digital 5.1)
        private const val AC3_DEFAULT_FRAME_SIZE = 1_536
        private const val AC3_MIN_FRAMES = 8
        private const val AC3_MIN_BUFFER_SCALE = 3

        // EAC3 (Dolby Digital Plus / Atmos lossy)
        // 10 752 = LCM(1792, 1536) — ensures aligned writes for both frame sizes
        private const val EAC3_BUFFER_SIZE = 2 * 10_752  //  21 504 bytes

        // Fallback for unknown passthrough formats
        private const val RAW_FALLBACK_BUFFER = 64 * 1024

        // DefaultAudioSink output mode constants
        private const val OUTPUT_MODE_PCM = 0
        @Suppress("unused")
        private const val OUTPUT_MODE_OFFLOAD = 1
        private const val OUTPUT_MODE_PASSTHROUGH = 2
    }

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double
    ): Int {

        // ── Passthrough: per-codec sizing ────────────────────────────────
        if (outputMode == OUTPUT_MODE_PASSTHROUGH) {
            val codecSize = passthroughBufferSize(encoding, minBufferSizeInBytes)
            if (codecSize > 0) {
                val final_ = codecSize.coerceAtLeast(minBufferSizeInBytes)
                Log.d(TAG, "PT buffer [${encodingLabel(encoding)}]: " +
                    "codec=${codecSize}B  min=${minBufferSizeInBytes}B → ${final_}B")
                return final_
            }
        }

        // ── PCM: target ~200 ms with 32-64 ms periods ────────────────────
        if (outputMode == OUTPUT_MODE_PCM && pcmFrameSize > 0 && sampleRate > 0) {
            val targetMs = 200
            val targetBytes =
                (sampleRate.toLong() * pcmFrameSize * targetMs / 1000).toInt()
            return targetBytes.coerceAtLeast(minBufferSizeInBytes)
        }

        // ── Offload / unknown: ExoPlayer default ─────────────────────────
        return DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
            .getBufferSizeInBytes(
                minBufferSizeInBytes, encoding, outputMode,
                pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed
            )
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Per-codec passthrough buffer sizes
    // ─────────────────────────────────────────────────────────────────────

    private fun passthroughBufferSize(encoding: Int, platformMin: Int): Int =
        when (encoding) {

            // DTS-HD MA / DTS-HD HR
            //   7.1 channel layout,  48 kHz base (IEC61937 → 192 kHz)
            C.ENCODING_DTS_HD -> {
                val s = DTSHD_MULTIPLIER * DTSHD_FRAME_SIZE
                Log.i(TAG, "DTS-HD → ${DTSHD_MULTIPLIER}×${DTSHD_FRAME_SIZE} = ${s}B")
                s
            }

            // Dolby TrueHD (Atmos lossless)
            //   7.1 channel layout,  192 kHz for IEC61937
            C.ENCODING_DOLBY_TRUEHD -> {
                val s = TRUEHD_MULTIPLIER * TRUEHD_SIZE
                Log.i(TAG, "TrueHD → ${TRUEHD_MULTIPLIER}×${TRUEHD_SIZE} = ${s}B")
                s
            }

            // DTS Core (all variants)
            C.ENCODING_DTS -> {
                val s = DTS_CORE_MULTIPLIER * DTS_CORE_FRAME_SIZE
                Log.i(TAG, "DTS → ${DTS_CORE_MULTIPLIER}×${DTS_CORE_FRAME_SIZE} = ${s}B")
                s
            }

            // AC3 (Dolby Digital 5.1)
            //   max(platformMin × 3,  1536 × 8)
            C.ENCODING_AC3 -> {
                val s = maxOf(platformMin * AC3_MIN_BUFFER_SCALE,
                    AC3_DEFAULT_FRAME_SIZE * AC3_MIN_FRAMES)
                Log.i(TAG, "AC3 → max(${platformMin}×3, 1536×8) = ${s}B")
                s
            }

            // EAC3 / EAC3-JOC (DD+ / Atmos lossy)
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC -> {
                Log.i(TAG, "EAC3 → 2×10752 = ${EAC3_BUFFER_SIZE}B")
                EAC3_BUFFER_SIZE
            }

            else -> {
                Log.w(TAG, "Unknown PT encoding=$encoding → ${RAW_FALLBACK_BUFFER}B")
                RAW_FALLBACK_BUFFER
            }
        }

    private fun encodingLabel(enc: Int): String = when (enc) {
        C.ENCODING_AC3 -> "AC3"
        C.ENCODING_E_AC3 -> "EAC3"
        C.ENCODING_E_AC3_JOC -> "EAC3-JOC"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        C.ENCODING_PCM_16BIT -> "PCM16"
        C.ENCODING_PCM_FLOAT -> "PCM-F"
        else -> "?$enc"
    }
}
