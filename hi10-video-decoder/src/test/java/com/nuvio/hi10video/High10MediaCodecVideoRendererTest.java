package com.nuvio.hi10video;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.MediaCodecInfo.CodecProfileLevel;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class High10MediaCodecVideoRendererTest {
  @Test
  public void incompatibleHigh10Fallback_cannotInitializeCodec() throws Exception {
    Format format = high10("avc1.6e0028");
    MediaCodecInfo codec = codec(false, 16);
    assertEquals(0, filter(format, Collections.singletonList(codec)).size());
  }

  @Test
  public void high10WithoutAdvertisedHigh10_cannotInitializeCodec() throws Exception {
    // Unknown profile metadata must not make Media3's permissive support test sufficient.
    Format format = high10(null).buildUpon().setInitializationData(
        Collections.singletonList(new byte[] {0, 0, 0, 1, 0x67, 110, 0, 40})).build();
    MediaCodecInfo codec = codec(true, 8);
    assertEquals(0, filter(format, Collections.singletonList(codec)).size());
  }

  @Test
  public void compatibleHigh10_remainsEligible() throws Exception {
    Format format = high10("avc1.6e0028");
    assertEquals(1, filter(format, Collections.singletonList(codec(true, 16))).size());
  }

  @Test
  public void spsLevel_isPassedToActualFormatSupportCheck() throws Exception {
    Format format = high10(null).buildUpon().setInitializationData(
        Collections.singletonList(new byte[] {1, 110, 0x10, 40})).build();
    Format expectedCapabilityFormat = format.buildUpon().setCodecs("avc1.6E1028").build();
    MediaCodecInfo codec = codec(false, 16);
    when(codec.isFormatSupported(expectedCapabilityFormat)).thenReturn(true);
    assertEquals(1, filter(format, Collections.singletonList(codec)).size());
  }

  @Test
  public void ordinaryAvcAndHevc_preserveExistingSelection() throws Exception {
    List<MediaCodecInfo> candidates = Collections.singletonList(codec(false, 8));
    for (Format format : new Format[] {
        high10("avc1.640028"),
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L120.B0").build()}) {
      assertEquals(candidates, filter(format, candidates));
    }
  }

  private static Format high10(String codecs) {
    return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs(codecs)
        .setWidth(1920).setHeight(1080).setFrameRate(23.976f).build();
  }

  static MediaCodecInfo codec(boolean supported, int profile) throws Exception {
    MediaCodecInfo codec = mock(MediaCodecInfo.class);
    when(codec.isFormatSupported(org.mockito.ArgumentMatchers.any(Format.class)))
        .thenReturn(supported);
    CodecProfileLevel advertised = new CodecProfileLevel();
    advertised.profile = profile;
    advertised.level = 2048; // AVCLevel4, sufficient for avc1.*0028.
    when(codec.getProfileLevels()).thenReturn(new CodecProfileLevel[] {advertised});
    return codec;
  }

  private static List<MediaCodecInfo> filter(Format format, List<MediaCodecInfo> candidates)
      throws Exception {
    return High10MediaCodecVideoRenderer.compatibleDecoders(format, candidates);
  }
}
