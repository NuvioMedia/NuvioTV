package com.nuvio.tv.ui.screens.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlin.math.max
import kotlin.math.min

private const val SOFTWARE_AV1_MAX_LONG_SIDE = 1920
private const val SOFTWARE_AV1_MAX_SHORT_SIDE = 1088

internal enum class HardwareAv1Support { NONE, BEYOND_LIMITS, HANDLES }

/** dav1d only takes 8-bit AV1 that no hardware decoder takes, and above 1080p only when the box has none. */
internal fun softwareAv1FormatSupport(
    format: Format,
    decoderSupport: Int,
    hardware: () -> HardwareAv1Support,
): Int {
    if (decoderSupport != C.FORMAT_HANDLED) return decoderSupport
    if (isHighBitDepthAv1(format)) return C.FORMAT_UNSUPPORTED_SUBTYPE
    val hardwareSupport = hardware()
    return when {
        hardwareSupport == HardwareAv1Support.HANDLES -> C.FORMAT_UNSUPPORTED_SUBTYPE
        !exceedsSoftwareAv1Size(format) -> C.FORMAT_HANDLED
        hardwareSupport == HardwareAv1Support.NONE -> C.FORMAT_EXCEEDS_CAPABILITIES
        else -> C.FORMAT_UNSUPPORTED_SUBTYPE
    }
}

internal fun exceedsSoftwareAv1Size(format: Format): Boolean {
    if (format.width == Format.NO_VALUE || format.height == Format.NO_VALUE) return false
    return max(format.width, format.height) > SOFTWARE_AV1_MAX_LONG_SIDE ||
        min(format.width, format.height) > SOFTWARE_AV1_MAX_SHORT_SIDE
}

internal fun isHighBitDepthAv1(format: Format): Boolean {
    val bitDepth = av1BitDepth(format) ?: return when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> true
        else -> false
    }
    return bitDepth > 8
}

internal fun av1BitDepth(format: Format): Int? {
    format.colorInfo?.lumaBitdepth?.takeIf { it != Format.NO_VALUE && it > 0 }?.let { return it }
    format.codecs?.let { codecs ->
        val parts = codecs.split(',').firstOrNull { it.trim().startsWith("av01.") }?.trim()?.split('.')
        parts?.getOrNull(3)?.toIntOrNull()?.let { return it }
    }
    val av1c = format.initializationData.firstOrNull() ?: return null
    if (av1c.size < 3 || (av1c[0].toInt() and 0x80) == 0) return null
    val flags = av1c[2].toInt()
    return when {
        (flags and 0x40) == 0 -> 8
        (flags and 0x20) != 0 -> 12
        else -> 10
    }
}

/**
 * dav1d goes right after MediaCodec, so MediaCodec wins a tie and a mixed AV1/AVC/HEVC ladder stays on the hardware
 * AVC/HEVC decoder. dav1d still gets 8-bit AV1 because MediaCodec leaves Android's software AV1 decoders out for
 * it, see [withoutAndroidSoftwareAv1]. The test switch puts dav1d first.
 */
internal fun <T> softwareAv1Slot(
    videoRenderers: List<T>,
    extensionRendererMode: Int,
    first: Boolean,
    isMediaCodecRenderer: (T) -> Boolean,
): Int? {
    if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) return null
    if (first) return 0
    val mediaCodecIndex = videoRenderers.indexOfFirst(isMediaCodecRenderer)
    return if (mediaCodecIndex < 0) videoRenderers.size else mediaCodecIndex + 1
}

/** 8-bit AV1 up to 1080p without DRM: what dav1d plays when no hardware decoder takes it. */
internal fun dav1dTakesOverFromAndroid(format: Format): Boolean =
    format.sampleMimeType == MimeTypes.VIDEO_AV1 &&
        format.cryptoType == C.CRYPTO_TYPE_NONE &&
        !isHighBitDepthAv1(format) &&
        !exceedsSoftwareAv1Size(format)

internal fun withoutAndroidSoftwareAv1(
    selector: MediaCodecSelector,
    format: Format,
    dav1dLoaded: () -> Boolean,
): MediaCodecSelector {
    if (!dav1dTakesOverFromAndroid(format) || !dav1dLoaded()) return selector
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val decoders = selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (mimeType == MimeTypes.VIDEO_AV1) decoders.filterNot(::isSoftwareDecoder) else decoders
    }
}

internal fun dav1dLoaded(): Boolean = Dav1dLibrary.isAvailable()

internal fun hardwareAv1Support(selector: MediaCodecSelector, format: Format): HardwareAv1Support {
    val mimeType = format.sampleMimeType ?: return HardwareAv1Support.NONE
    val hardware = runCatching { selector.getDecoderInfos(mimeType, false, false) }
        .getOrDefault(emptyList())
        .filter { !it.softwareOnly }
    if (hardware.isEmpty()) return HardwareAv1Support.NONE
    val handles = hardware.any { runCatching { it.isFormatSupported(format) }.getOrDefault(false) }
    return if (handles) HardwareAv1Support.HANDLES else HardwareAv1Support.BEYOND_LIMITS
}

internal fun createSoftwareAv1Renderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    hardware: (Format) -> HardwareAv1Support,
): Renderer = SoftwareAv1Renderer(
    Libdav1dVideoRenderer(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
    ),
    hardware,
)

internal class SoftwareAv1Renderer(
    renderer: Renderer,
    hardware: (Format) -> HardwareAv1Support,
) : ForwardingRenderer(renderer) {
    private val capabilities = SoftwareAv1Capabilities(renderer.capabilities, hardware)

    override fun getCapabilities(): RendererCapabilities = capabilities
}

internal class SoftwareAv1Capabilities(
    private val delegate: RendererCapabilities,
    private val hardware: (Format) -> HardwareAv1Support,
) : RendererCapabilities {
    override fun getName(): String = delegate.name

    override fun getTrackType(): Int = delegate.trackType

    override fun supportsMixedMimeTypeAdaptation(): Int = delegate.supportsMixedMimeTypeAdaptation()

    override fun setListener(listener: RendererCapabilities.Listener) = delegate.setListener(listener)

    override fun clearListener() = delegate.clearListener()

    override fun supportsFormat(format: Format): Int {
        val capabilities = delegate.supportsFormat(format)
        val decoderSupport = RendererCapabilities.getFormatSupport(capabilities)
        val support = softwareAv1FormatSupport(format, decoderSupport) { hardware(format) }
        if (support == decoderSupport) return capabilities
        if (support != C.FORMAT_EXCEEDS_CAPABILITIES) return RendererCapabilities.create(support)
        return RendererCapabilities.create(
            support,
            RendererCapabilities.getAdaptiveSupport(capabilities),
            RendererCapabilities.getTunnelingSupport(capabilities),
        )
    }
}
