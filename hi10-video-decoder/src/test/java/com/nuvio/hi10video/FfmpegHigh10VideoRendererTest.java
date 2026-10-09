package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.RendererCapabilities;
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
