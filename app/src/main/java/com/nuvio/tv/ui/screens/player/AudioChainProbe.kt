package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport

object AudioChainProbe {

    data class ChainSnapshot(
        val direct: DirectSupport?,
        val maxPcmChannels: Int?
    )

    @Volatile
    private var cached: Pair<String, ChainSnapshot>? = null

    fun snapshot(context: Context, routeKey: String?): ChainSnapshot {
        if (routeKey != null) {
            cached?.let { (key, snap) -> if (key == routeKey) return snap }
        }
        val fresh = ChainSnapshot(
            direct = probeDirectSupport(),
            maxPcmChannels = readMaxPcmChannelCount(context)
        )
        if (routeKey != null) {
            cached = routeKey to fresh
        }
        return fresh
    }

    fun invalidate() {
        cached = null
    }

    fun probeDirectSupport(): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            DirectSupport(
                ac3 = probeDirect(AudioFormat.ENCODING_AC3, attributes),
                eac3 = probeDirect(AudioFormat.ENCODING_E_AC3, attributes),
                trueHd = probeDirect(AudioFormat.ENCODING_DOLBY_TRUEHD, attributes),
                dts = probeDirect(AudioFormat.ENCODING_DTS, attributes),
                dtsHd = probeDirect(AudioFormat.ENCODING_DTS_HD, attributes)
            )
        }.getOrNull()
    }

    private fun probeDirect(encoding: Int, attributes: AudioAttributes): Boolean {
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        return runCatching {
            AudioTrack.isDirectPlaybackSupported(format, attributes)
        }.getOrDefault(false)
    }

    private val HDMI_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    @SuppressLint("NewApi", "InlinedApi")
    fun readMaxPcmChannelCount(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val pcmEncodings = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.audioProfiles }
                .filter { it.format in pcmEncodings }
                // Both positional and index masks resolve to a channel count via
                // popcount; take the largest across every PCM profile's masks.
                .flatMap { profile ->
                    (profile.channelMasks.asList() + profile.channelIndexMasks.asList())
                        .map { mask -> Integer.bitCount(mask) }
                }
                .filter { it > 0 }
                .maxOrNull()
        }.getOrNull()
    }
}
