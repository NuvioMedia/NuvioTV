package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
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
    ) {
        fun deniesEveryEncoding(): Boolean {
            val d = direct ?: return false
            return !d.ac3 && !d.eac3 && !d.trueHd && !d.dts && !d.dtsHd
        }
    }

    @Volatile
    private var cached: Pair<String, ChainSnapshot>? = null

    fun snapshot(context: Context, routeKey: String?): ChainSnapshot {
        if (routeKey != null) {
            cached?.let { (key, snap) -> if (key == routeKey) return snap }
        }
        val fresh = ChainSnapshot(
            direct = probeDirectSupport(context),
            maxPcmChannels = readMaxPcmChannelCount(context)
        )
        if (routeKey != null && !fresh.deniesEveryEncoding()) {
            cached = routeKey to fresh
        }
        return fresh
    }

    fun invalidate() {
        cached = null
    }

    fun probeDirectSupport(context: Context): DirectSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // The plug report only describes the HDMI sink. With a USB output connected, media
            // goes there instead, so the report says nothing about the active chain.
            if (connectedOutputTypes(context).any { it in USB_OUTPUT_TYPES }) return null
            return readHdmiPlugReport(context)
        }
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun readHdmiPlugReport(context: Context): DirectSupport? {
        val report = runCatching {
            context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
        }.getOrNull() ?: return null
        return directSupportFromHdmiPlugReport(
            plugState = report.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1),
            encodings = report.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)
        )
    }

    @SuppressLint("InlinedApi")
    internal fun directSupportFromHdmiPlugReport(plugState: Int, encodings: IntArray?): DirectSupport? {
        return when {
            plugState == 0 ->
                DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)
            plugState != 1 || encodings == null || encodings.isEmpty() -> null
            else -> DirectSupport(
                ac3 = AudioFormat.ENCODING_AC3 in encodings,
                eac3 = AudioFormat.ENCODING_E_AC3 in encodings ||
                    AudioFormat.ENCODING_E_AC3_JOC in encodings,
                trueHd = AudioFormat.ENCODING_DOLBY_TRUEHD in encodings,
                dts = AudioFormat.ENCODING_DTS in encodings,
                dtsHd = AudioFormat.ENCODING_DTS_HD in encodings
            )
        }
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

    private val USB_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    /**
     * Output types whose PCM profiles describe the active media sink. [routedType] is the
     * device media is routed to, when the platform reports it (API 33+). Without it, a
     * connected USB output wins over HDMI, as in Android's media routing. Any other routed
     * type has no profile worth reading.
     */
    internal fun pcmSinkTypes(routedType: Int?, connectedTypes: Collection<Int>): Set<Int>? = when {
        routedType != null && routedType in HDMI_OUTPUT_TYPES -> HDMI_OUTPUT_TYPES
        routedType != null && routedType in USB_OUTPUT_TYPES -> USB_OUTPUT_TYPES
        routedType != null -> null
        connectedTypes.any { it in USB_OUTPUT_TYPES } -> USB_OUTPUT_TYPES
        else -> HDMI_OUTPUT_TYPES
    }

    private fun connectedOutputTypes(context: Context): List<Int> {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.getOrDefault(emptyList())
    }

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
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val sinkTypes = pcmSinkTypes(
                routedType = AudioOutputRouteDetector.detectMediaRoutedDevice(audioManager)?.type,
                connectedTypes = outputs.map { it.type }
            ) ?: return null
            outputs
                .filter { it.type in sinkTypes }
                .flatMap { it.audioProfiles }
                .filter { it.format in pcmEncodings }
                .flatMap { profile ->
                    (profile.channelMasks.asList() + profile.channelIndexMasks.asList())
                        .map { mask -> Integer.bitCount(mask) }
                }
                .filter { it > 0 }
                .maxOrNull()
        }.getOrNull()
    }
}
