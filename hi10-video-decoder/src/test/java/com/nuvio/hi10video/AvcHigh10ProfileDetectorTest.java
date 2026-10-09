package com.nuvio.hi10video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.util.Collections;
import org.junit.Test;

public final class AvcHigh10ProfileDetectorTest {

  @Test
  public void avcHigh8Bit_isNotClaimed() {
    assertFalse(isHigh10(MimeTypes.VIDEO_H264, "avc1.640028"));
  }

  @Test
  public void avc1High10_isClaimed() {
    assertTrue(isHigh10(MimeTypes.VIDEO_H264, "avc1.6e0028"));
  }

  @Test
  public void avc3High10_isClaimed() {
    assertTrue(isHigh10(MimeTypes.VIDEO_H264, "avc3.6E0028"));
  }

  @Test
  public void hevcMain10_isNotClaimed() {
    assertFalse(isHigh10(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0"));
  }

  @Test
  public void vp9Profile2_isNotClaimed() {
    assertFalse(isHigh10(MimeTypes.VIDEO_VP9, "vp09.02.10.10"));
  }

  @Test
  public void av1Main10_isNotClaimed() {
    assertFalse(isHigh10(MimeTypes.VIDEO_AV1, "av01.0.08M.10"));
  }

  @Test
  public void missingCodecMetadata_isNotClaimed() {
    assertFalse(isHigh10(MimeTypes.VIDEO_H264, null));
  }

  @Test
  public void annexBSpsHigh10_isClaimedWithoutCodecString() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setInitializationData(
                Collections.singletonList(new byte[] {0, 0, 0, 1, 0x67, 110, 0, 31}))
            .build();
    assertTrue(AvcHigh10ProfileDetector.isHigh10(format));
  }

  @Test
  public void avcConfigurationRecordHigh10_isClaimedWithoutCodecString() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setInitializationData(Collections.singletonList(new byte[] {1, 110, 0, 31}))
            .build();
    assertTrue(AvcHigh10ProfileDetector.isHigh10(format));
  }

  private static boolean isHigh10(String sampleMimeType, String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(sampleMimeType).setCodecs(codecs).build();
    return AvcHigh10ProfileDetector.isHigh10(format);
  }
}
