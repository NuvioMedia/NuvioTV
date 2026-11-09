package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Media3's own capability check and renderer mapping, with the box's codec list faked. */
class SoftwareAv1LadderTest {

    private val context = mockk<Context>(relaxed = true)

    private fun decoder(name: String, mimeType: String, softwareOnly: Boolean): MediaCodecInfo =
        MediaCodecInfo.newInstance(name, mimeType, mimeType, null, !softwareOnly, softwareOnly, !softwareOnly, false, false)

    private fun box(vararg decoders: MediaCodecInfo) = MediaCodecSelector { mimeType, secure, tunneling ->
        if (secure || tunneling) emptyList() else decoders.filter { it.mimeType == mimeType }
    }

    private val noHardwareAv1 = box(
        decoder("c2.amlogic.avc.decoder", MimeTypes.VIDEO_H264, softwareOnly = false),
        decoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264, softwareOnly = true),
        decoder("c2.amlogic.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
        decoder("c2.android.av1.decoder", MimeTypes.VIDEO_AV1, softwareOnly = true),
    )

    private val hardwareAv1 = box(
        decoder("c2.amlogic.avc.decoder", MimeTypes.VIDEO_H264, softwareOnly = false),
        decoder("c2.amlogic.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
        decoder("c2.amlogic.av1.decoder", MimeTypes.VIDEO_AV1, softwareOnly = false),
        decoder("c2.android.av1.decoder", MimeTypes.VIDEO_AV1, softwareOnly = true),
    )

    private fun video(id: String, mimeType: String, colorInfo: ColorInfo? = null): Format =
        Format.Builder().setId(id).setSampleMimeType(mimeType).setColorInfo(colorInfo).build()

    private val av1 = video("av1", MimeTypes.VIDEO_AV1)
    private val av1TenBit = video(
        "av1-10",
        MimeTypes.VIDEO_AV1,
        ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build(),
    )
    private val avc = video("avc", MimeTypes.VIDEO_H264)
    private val hevc = video("hevc", MimeTypes.VIDEO_H265)

    private fun capabilities(name: String, supports: (Format) -> Int) = object : RendererCapabilities {
        override fun getName() = name
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format) = supports(format)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }

    /** What DecodeOrderPtsVideoRenderer.supportsFormat hands to media3. */
    private fun mediaCodec(box: MediaCodecSelector, dav1dLoaded: Boolean) = capabilities("mediacodec") { format ->
        MediaCodecVideoRenderer.supportsFormat(context, withoutAndroidSoftwareAv1(box, format) { dav1dLoaded }, format)
    }

    /** Libdav1dVideoRenderer's own answer, wrapped as the player wraps it. */
    private fun dav1d(box: MediaCodecSelector): RendererCapabilities = SoftwareAv1Capabilities(
        capabilities("dav1d") { format ->
            if (format.sampleMimeType == MimeTypes.VIDEO_AV1) {
                RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                )
            } else {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
        }
    ) { format -> hardwareAv1Support(box, format) }

    private class Mapped(val info: MappingTrackSelector.MappedTrackInfo) : RuntimeException()

    private class MappingProbe : MappingTrackSelector() {
        override fun selectTracks(
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<out Array<out IntArray>>,
            rendererMixedMimeTypeAdaptationSupport: IntArray,
            mediaPeriodId: MediaSource.MediaPeriodId,
            timeline: Timeline,
        ): Pair<Array<RendererConfiguration?>, Array<ExoTrackSelection?>> = throw Mapped(mappedTrackInfo)
    }

    /** The renderer media3 gives one track group (an HLS ladder) to. */
    private fun owner(renderers: List<RendererCapabilities>, vararg tracks: Format): String {
        val info = try {
            MappingProbe().selectTracks(
                renderers.toTypedArray(),
                TrackGroupArray(TrackGroup(*tracks)),
                MediaSource.MediaPeriodId(Any()),
                Timeline.EMPTY,
            )
            error("not mapped")
        } catch (mapped: Mapped) {
            mapped.info
        }
        val index = renderers.indices.firstOrNull { info.getTrackGroups(it).length > 0 } ?: return "none"
        return renderers[index].name
    }

    private fun player(box: MediaCodecSelector, dav1dLoaded: Boolean = true) =
        listOf(mediaCodec(box, dav1dLoaded), dav1d(box))

    @Test
    fun `no hardware AV1 - 8-bit AV1 plays on dav1d`() {
        assertEquals("dav1d", owner(player(noHardwareAv1), av1))
    }

    @Test
    fun `no hardware AV1 - a mixed ladder stays on the hardware AVC and HEVC decoder`() {
        assertEquals("mediacodec", owner(player(noHardwareAv1), av1, avc))
        assertEquals("mediacodec", owner(player(noHardwareAv1), avc, av1, hevc))
        val mediaCodec = mediaCodec(noHardwareAv1, dav1dLoaded = true)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(mediaCodec.supportsFormat(av1)))
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(mediaCodec.supportsFormat(avc)))
    }

    @Test
    fun `no hardware AV1 - 10-bit AV1 stays on Android's software decoder`() {
        assertEquals("mediacodec", owner(player(noHardwareAv1), av1TenBit))
    }

    @Test
    fun `no hardware AV1 - without the dav1d library Android's software decoder keeps AV1`() {
        assertEquals("mediacodec", owner(listOf(mediaCodec(noHardwareAv1, dav1dLoaded = false)), av1))
    }

    @Test
    fun `hardware AV1 - everything stays on MediaCodec`() {
        assertEquals("mediacodec", owner(player(hardwareAv1), av1))
        assertEquals("mediacodec", owner(player(hardwareAv1), av1, avc))
        assertEquals("mediacodec", owner(player(hardwareAv1), av1TenBit))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(dav1d(hardwareAv1).supportsFormat(av1)))
    }

    @Test
    fun `hardware AV1 - Android's software decoder drops out of the list only for streams dav1d takes`() {
        val names = { format: Format ->
            withoutAndroidSoftwareAv1(hardwareAv1, format) { true }
                .getDecoderInfos(MimeTypes.VIDEO_AV1, false, false).map { it.name }
        }
        assertEquals(listOf("c2.amlogic.av1.decoder"), names(av1))
        assertEquals(listOf("c2.amlogic.av1.decoder", "c2.android.av1.decoder"), names(av1TenBit))
    }
}
