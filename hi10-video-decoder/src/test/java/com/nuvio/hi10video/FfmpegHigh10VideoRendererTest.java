package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import java.util.Collections;
import java.lang.reflect.Field;
import org.junit.Test;

public final class FfmpegHigh10VideoRendererTest {

  @Test
  public void defaultOutputPool_hasSixteenSlots() throws Exception {
    Field field = FfmpegHigh10VideoRenderer.class.getDeclaredField("DEFAULT_OUTPUT_BUFFERS");
    field.setAccessible(true);
    assertEquals(16, field.getInt(null));
  }

  @Test
  public void avcHigh8Bit_isUnsupported() {
    assertSupport(C.FORMAT_UNSUPPORTED_SUBTYPE, MimeTypes.VIDEO_H264, "avc1.640028");
  }

  @Test
  public void avcHigh10_isHandled() {
    assertSupport(C.FORMAT_HANDLED, MimeTypes.VIDEO_H264, "avc1.6e0028");
  }

  @Test
  public void rotatedHigh10_isNotHandled() {
    Format format = high10().setRotationDegrees(90).build();
    assertEquals(
        C.FORMAT_UNSUPPORTED_SUBTYPE,
        RendererCapabilities.getFormatSupport(
            FfmpegHigh10VideoRenderer.supportsFormatForTest(format, true)));
  }

  @Test
  public void encryptedHigh10_isNotHandled() {
    Format format = high10().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build();
    assertEquals(
        C.FORMAT_UNSUPPORTED_DRM,
        RendererCapabilities.getFormatSupport(
            FfmpegHigh10VideoRenderer.supportsFormatForTest(format, true)));
  }

  @Test
  public void nativeUnavailable_isNotHandled() {
    assertEquals(
        C.FORMAT_UNSUPPORTED_SUBTYPE,
        RendererCapabilities.getFormatSupport(
            FfmpegHigh10VideoRenderer.supportsFormatForTest(high10().build(), false)));
  }

  @Test
  public void rotatedHigh10_rejectsBeforeNativeConstruction() throws Exception {
    FfmpegHigh10VideoRenderer renderer = new FfmpegHigh10VideoRenderer(0, null, null, 50);
    // Do not start real native/worker resources in this host routing test.
    try (org.mockito.MockedConstruction<FfmpegHigh10VideoDecoder> nativeDecoder =
            mockConstruction(FfmpegHigh10VideoDecoder.class);
        org.mockito.MockedStatic<android.os.Trace> trace = mockStatic(android.os.Trace.class)) {
      try {
        renderer.createDecoder(high10().setRotationDegrees(90).build(), null);
        fail("Rotated software decode must be rejected before constructing the decoder");
      } catch (FfmpegHigh10VideoDecoderException error) {
        assertTrue(error.getMessage().contains("Unsupported software AVC High10 format"));
      }
    }
  }

  private static Format.Builder high10() {
    return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.6e0028");
  }

  @Test
  public void high10HardwareWrongLevel_doesNotDisableSoftware() throws Exception {
    assertHardwareEligibility(high10().setCodecs("avc1.6e0033").build(), false);
  }

  @Test
  public void high10HardwareWrongSize_doesNotDisableSoftware() throws Exception {
    assertHardwareEligibility(high10().setWidth(3840).setHeight(2160).build(), false);
  }

  @Test
  public void high10HardwareCompatible_defersToHardware() throws Exception {
    assertHardwareEligibility(high10().setWidth(1920).setHeight(1080).setFrameRate(23.976f).build(), true);
  }

  @Test
  public void secureOnlyHardware_doesNotDisableClearSoftware() throws Exception {
    Format format = high10().build();
    MediaCodecInfo codec = High10MediaCodecVideoRendererTest.codec(true, 16);
    setFlag(codec, "hardwareAccelerated", true);
    setFlag(codec, "secure", true);
    assertEquals(false, FfmpegHigh10VideoRenderer.hasCompatibleHardwareDecoder(
        format, Collections.singletonList(codec)));
  }

  @Test
  public void softwarePlatformCodec_doesNotDisableIsolatedRenderer() throws Exception {
    Format format = high10().build();
    MediaCodecInfo codec = High10MediaCodecVideoRendererTest.codec(true, 16);
    setFlag(codec, "softwareOnly", true);
    assertEquals(false, FfmpegHigh10VideoRenderer.hasCompatibleHardwareDecoder(
        format, Collections.singletonList(codec)));
  }

  private static void setFlag(MediaCodecInfo codec, String name, boolean value) throws Exception {
    Field field = MediaCodecInfo.class.getField(name);
    field.setAccessible(true);
    field.set(codec, value);
  }

  private static void assertHardwareEligibility(Format format, boolean supportsActualFormat) throws Exception {
    MediaCodecInfo codec = High10MediaCodecVideoRendererTest.codec(supportsActualFormat, 16);
    setFlag(codec, "hardwareAccelerated", true);
    when(codec.isFormatSupported(format)).thenReturn(supportsActualFormat);
    boolean hardwareAvailable = FfmpegHigh10VideoRenderer.hasCompatibleHardwareDecoder(
        format, Collections.singletonList(codec));
    assertEquals(
        supportsActualFormat ? C.FORMAT_UNSUPPORTED_SUBTYPE : C.FORMAT_HANDLED,
        RendererCapabilities.getFormatSupport(
            FfmpegHigh10VideoRenderer.supportsFormatForTest(format, true, hardwareAvailable)));
  }

  @Test
  public void avcHigh10_whenHardwareAdvertisesHigh10_isLeftToHardware() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.6e0028")
            .build();
    int capabilities =
        FfmpegHigh10VideoRenderer.supportsFormatForTest(format, true, true);
    assertEquals(
        C.FORMAT_UNSUPPORTED_SUBTYPE,
        RendererCapabilities.getFormatSupport(capabilities));
  }

  @Test
  public void hevcMain10_isUnsupported() {
    assertSupport(C.FORMAT_UNSUPPORTED_TYPE, MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0");
  }

  @Test
  public void vp9Profile2_isUnsupported() {
    assertSupport(C.FORMAT_UNSUPPORTED_TYPE, MimeTypes.VIDEO_VP9, "vp09.02.10.10");
  }

  @Test
  public void av1Main10_isUnsupported() {
    assertSupport(C.FORMAT_UNSUPPORTED_TYPE, MimeTypes.VIDEO_AV1, "av01.0.08M.10");
  }

  @Test
  public void avcWithoutProfile_isUnsupported() {
    assertSupport(C.FORMAT_UNSUPPORTED_SUBTYPE, MimeTypes.VIDEO_H264, null);
  }

  private static void assertSupport(int expected, String mimeType, String codecs) {
    Format format = new Format.Builder().setSampleMimeType(mimeType).setCodecs(codecs).build();
    int capabilities = FfmpegHigh10VideoRenderer.supportsFormatForTest(format, true);
    assertEquals(expected, RendererCapabilities.getFormatSupport(capabilities));
  }
}
