/*
 * Copyright (C) 2026 Nuvio contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nuvio.hi10video;

import static android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import java.util.List;
import java.util.Locale;

/** Identifies AVC High 10 without consulting filenames, color metadata, or display capability. */
public final class AvcHigh10ProfileDetector {

  private AvcHigh10ProfileDetector() {}

  /** Retains SPS profile/constraints/level when extractor metadata omitted codecs. */
  static Format withAvcCodecString(Format format) {
    if (CodecSpecificDataUtil.getCodecProfileAndLevel(format) != null) return format;
    for (byte[] data : format.initializationData) {
      int offset;
      if (data.length >= 4 && data[0] == 1) {
        offset = 1;
      } else {
        offset = startCodeLength(data);
        if (data.length < offset + 4 || (data[offset] & 0x1F) != 7) continue;
        offset++;
      }
      return format.buildUpon().setCodecs(String.format(Locale.US, "avc1.%02X%02X%02X",
          data[offset] & 0xFF, data[offset + 1] & 0xFF, data[offset + 2] & 0xFF)).build();
    }
    return format;
  }

  /** Returns whether {@code format} is conclusively AVC High 10 Profile. */
  public static boolean isHigh10(Format format) {
    if (!MimeTypes.VIDEO_H264.equals(format.sampleMimeType)) {
      return false;
    }

    @Nullable Pair<Integer, Integer> profileAndLevel =
        CodecSpecificDataUtil.getCodecProfileAndLevel(format);
    if (profileAndLevel != null && profileAndLevel.first != null) {
      return profileAndLevel.first == AVCProfileHigh10;
    }

    @Nullable Integer codecStringProfile = parseCodecStringProfileIdc(format.codecs);
    if (codecStringProfile != null) {
      return codecStringProfile == 110;
    }

    @Nullable Integer spsProfile = findSpsProfileIdc(format.initializationData);
    return spsProfile != null && spsProfile == 110;
  }

  @Nullable
  private static Integer parseCodecStringProfileIdc(@Nullable String codecs) {
    if (codecs == null) {
      return null;
    }
    for (String codec : codecs.split(",")) {
      String normalized = codec.trim().toLowerCase(Locale.US);
      if ((!normalized.startsWith("avc1.") && !normalized.startsWith("avc3."))
          || normalized.length() < 8) {
        continue;
      }
      try {
        return Integer.parseInt(normalized.substring(5, 7), 16);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  @Nullable
  private static Integer findSpsProfileIdc(List<byte[]> initializationData) {
    for (byte[] data : initializationData) {
      if (data.length >= 4 && data[0] == 1) {
        // AVCDecoderConfigurationRecord: AVCProfileIndication follows configurationVersion.
        return data[1] & 0xFF;
      }
      int offset = startCodeLength(data);
      if (data.length >= offset + 2 && (data[offset] & 0x1F) == 7) {
        return data[offset + 1] & 0xFF;
      }
    }
    return null;
  }

  private static int startCodeLength(byte[] data) {
    if (data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
      return 4;
    }
    if (data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
      return 3;
    }
    return 0;
  }
}
