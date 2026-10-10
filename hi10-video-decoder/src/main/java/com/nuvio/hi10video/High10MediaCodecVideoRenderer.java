/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import static android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import java.util.ArrayList;
import java.util.List;

/** Keeps Media3's normal video path, but never initializes an incompatible High10 codec. */
@UnstableApi
public final class High10MediaCodecVideoRenderer extends MediaCodecVideoRenderer {
  public High10MediaCodecVideoRenderer(Builder builder) {
    super(builder);
  }

  @Override
  protected List<MediaCodecInfo> getDecoderInfos(
      MediaCodecSelector selector, Format format, boolean requiresSecureDecoder)
      throws DecoderQueryException {
    return compatibleDecoders(format, super.getDecoderInfos(selector, format, requiresSecureDecoder));
  }

  @Override
  protected DecoderReuseEvaluation canReuseCodec(MediaCodecInfo codec, Format oldFormat, Format newFormat) {
    if (AvcHigh10ProfileDetector.isHigh10(newFormat)) {
      try {
        if (!isCompatible(newFormat, codec)) {
          return new DecoderReuseEvaluation(codec.name, oldFormat, newFormat,
              DecoderReuseEvaluation.REUSE_RESULT_NO,
              DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
        }
      } catch (DecoderQueryException error) {
        return new DecoderReuseEvaluation(codec.name, oldFormat, newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
      }
    }
    return super.canReuseCodec(codec, oldFormat, newFormat);
  }

  static List<MediaCodecInfo> compatibleDecoders(Format format, List<MediaCodecInfo> candidates)
      throws DecoderQueryException {
    if (!AvcHigh10ProfileDetector.isHigh10(format)) return candidates;
    List<MediaCodecInfo> compatible = new ArrayList<>();
    for (MediaCodecInfo codec : candidates) {
      if (isCompatible(format, codec)) compatible.add(codec);
    }
    // Media3's existing no-suitable-decoder exception/error UI handles an empty list.
    return compatible;
  }

  static boolean isCompatible(Format format, MediaCodecInfo codec) throws DecoderQueryException {
    boolean high10Advertised = false;
    for (android.media.MediaCodecInfo.CodecProfileLevel profile : codec.getProfileLevels()) {
      if (profile.profile == AVCProfileHigh10) high10Advertised = true;
    }
    return high10Advertised && codec.isFormatSupported(AvcHigh10ProfileDetector.withAvcCodecString(format));
  }
}
