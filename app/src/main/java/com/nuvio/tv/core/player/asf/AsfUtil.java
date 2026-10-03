/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nuvio.tv.core.player.asf;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared constants and helpers for the ASF extractor package. */
final class AsfUtil {

  private static final String TAG = AsfUtil.class.getSimpleName();

  static final byte[] VC1_START_CODE = {0x00, 0x00, 0x01, 0x0F};

  static final int FOURCC_WMV1 = makeFourcc('W', 'M', 'V', '1');
  static final int FOURCC_WMV2 = makeFourcc('W', 'M', 'V', '2');
  static final int FOURCC_WMV3 = makeFourcc('W', 'M', 'V', '3');
  static final int FOURCC_WMVA = makeFourcc('W', 'M', 'V', 'A');
  static final int FOURCC_WVC1 = makeFourcc('W', 'V', 'C', '1');
  static final int FOURCC_MP43 = makeFourcc('M', 'P', '4', '3');
  static final int FOURCC_MP4S = makeFourcc('M', 'P', '4', 'S');

  static final int WAVE_FORMAT_WMA1 = 0x0160;
  static final int WAVE_FORMAT_WMA2 = 0x0161;
  static final int WAVE_FORMAT_WMA_PRO = 0x0162;
  static final int WAVE_FORMAT_WMA_LOSS = 0x0163;
  static final int WAVE_FORMAT_WMA_VOICE = 0x000A;

  static int makeFourcc(char charA, char charB, char charC, char charD) {
    return charA | (charB << 8) | (charC << 16) | (charD << 24);
  }

  static String fourccToString(int fourcc) {
    return new String(
        new byte[] {
          (byte) fourcc, (byte) (fourcc >> 8), (byte) (fourcc >> 16), (byte) (fourcc >> 24)
        });
  }

  private static boolean isVc1Fourcc(int fourcc) {
    return fourcc == FOURCC_WVC1 || fourcc == FOURCC_WMVA;
  }

  static boolean isSupportedVideoFourcc(int fourcc) {
    return fourcc == FOURCC_WMV1
        || fourcc == FOURCC_WMV2
        || fourcc == FOURCC_WMV3
        || fourcc == FOURCC_WMVA
        || fourcc == FOURCC_WVC1
        || fourcc == FOURCC_MP43
        || fourcc == FOURCC_MP4S;
  }

  static boolean isSupportedWmaTag(int tag) {
    return tag == WAVE_FORMAT_WMA1
        || tag == WAVE_FORMAT_WMA2
        || tag == WAVE_FORMAT_WMA_PRO
        || tag == WAVE_FORMAT_WMA_LOSS
        || tag == WAVE_FORMAT_WMA_VOICE;
  }

  private static String fourccToMimeType(int fourcc) {
    if (fourcc == FOURCC_WMV1) {
      return "video/x-ms-wmv1";
    }
    if (fourcc == FOURCC_WMV2) {
      return "video/x-ms-wmv2";
    }
    if (fourcc == FOURCC_WVC1 || fourcc == FOURCC_WMVA) {
      return MimeTypes.VIDEO_VC1;
    }
    if (fourcc == FOURCC_MP43) {
      return "video/mp43";
    }
    if (fourcc == FOURCC_MP4S) {
      return MimeTypes.VIDEO_MP4V;
    }
    return "video/x-ms-wmv";
  }

  private static String wmaTagToMimeType(int tag) {
    switch (tag) {
      case WAVE_FORMAT_WMA1:
        return "audio/x-ms-wma";
      case WAVE_FORMAT_WMA_PRO:
        return "audio/x-ms-wma";
      case WAVE_FORMAT_WMA_LOSS:
        return "audio/x-ms-wma";
      case WAVE_FORMAT_WMA_VOICE:
        return "audio/x-ms-wma";
      default:
        return "audio/x-ms-wma";
    }
  }

  private static String wmaTagToCodecString(int tag) {
    switch (tag) {
      case WAVE_FORMAT_WMA1:
        return "wmav1";
      case WAVE_FORMAT_WMA_PRO:
        return "wmapro";
      case WAVE_FORMAT_WMA_LOSS:
        return "wmalossless";
      case WAVE_FORMAT_WMA_VOICE:
        return "wmavoice";
      default:
        return "wmav2";
    }
  }

  private static String fourccToCodecString(int fourcc) {
    if (fourcc == FOURCC_WMV1) {
      return "wmv1";
    }
    if (fourcc == FOURCC_WMV2) {
      return "wmv2";
    }
    if (fourcc == FOURCC_WMV3) {
      return "wmv3";
    }
    if (fourcc == FOURCC_WVC1 || fourcc == FOURCC_WMVA) {
      return "vc1";
    }
    if (fourcc == FOURCC_MP43) {
      return "msmpeg4v3";
    }
    if (fourcc == FOURCC_MP4S) {
      return "mp4v.20";
    }
    return "wmv3";
  }

  /**
   * Prepends the VC-1 sequence-header start code ({@code 00 00 01 0F}) if not already present.
   * Returns {@link #VC1_START_CODE} alone when {@code data} is null or empty.
   */
  static byte[] prependVc1StartCode(@Nullable byte[] data) {
    if (data == null || data.length == 0) {
      return VC1_START_CODE;
    }
    if (data.length >= 4
        && data[0] == 0x00
        && data[1] == 0x00
        && data[2] == 0x01
        && data[3] == 0x0F) {
      return data;
    }
    byte[] out = new byte[VC1_START_CODE.length + data.length];
    System.arraycopy(VC1_START_CODE, 0, out, 0, VC1_START_CODE.length);
    System.arraycopy(data, 0, out, VC1_START_CODE.length, data.length);
    return out;
  }

  /** See ff_wma_get_frame_len_bits() in libavcodec/wma_common.c. */
  private static int wmaFrameLen(int sampleRate, int version, int decodeFlags) {
    int frameLenBits;
    if (sampleRate <= 16000) {
      frameLenBits = 9;
    } else if (sampleRate <= 22050 || (sampleRate <= 32000 && version == 1)) {
      frameLenBits = 10;
    } else if (sampleRate <= 48000 || version < 3) {
      frameLenBits = 11;
    } else if (sampleRate <= 96000) {
      frameLenBits = 12;
    } else {
      frameLenBits = 13;
    }
    if (version == 3) {
      int tmp = decodeFlags & 0x6;
      if (tmp == 0x2) {
        frameLenBits++;
      } else if (tmp == 0x4) {
        frameLenBits--;
      } else if (tmp == 0x6) {
        frameLenBits -= 2;
      }
    }
    return 1 << frameLenBits;
  }

  /** Resolves nBlockAlign, deriving it from avgBytesPerSec when nBlockAlign == 0. */
  private static int resolveBlockAlign(AudioStreamInfo audioInfo) {
    if (audioInfo.blockAlign != 0) {
      return audioInfo.blockAlign;
    }
    int version;
    int decodeFlags = 0;
    if (audioInfo.waveFormatTag == WAVE_FORMAT_WMA1) {
      version = 1;
    } else if (audioInfo.waveFormatTag == WAVE_FORMAT_WMA_PRO) {
      version = 3;
      if (audioInfo.codecExtra != null && audioInfo.codecExtra.length >= 16) {
        decodeFlags = (audioInfo.codecExtra[14] & 0xFF) | ((audioInfo.codecExtra[15] & 0xFF) << 8);
      }
    } else {
      version = 2;
    }
    int avgBytesPerSec = audioInfo.avgBitrateBps / 8;
    int frameLen = wmaFrameLen(audioInfo.sampleRate, version, decodeFlags);
    return (avgBytesPerSec * frameLen + audioInfo.sampleRate - 1) / audioInfo.sampleRate;
  }

  /**
   * Builds the WMA decoder initialization data list: index 0 = codec-private bytes (may be empty),
   * index 1 = nBlockAlign as 4-byte BE.
   */
  private static List<byte[]> buildWmaInitData(@Nullable byte[] codecExtra, int blockAlign) {
    List<byte[]> list = new ArrayList<>();
    list.add(codecExtra != null && codecExtra.length > 0 ? codecExtra : new byte[0]);
    list.add(
        new byte[] {
          (byte) (blockAlign >> 24),
          (byte) (blockAlign >> 16),
          (byte) (blockAlign >> 8),
          (byte) blockAlign
        });
    return list;
  }

  static Format buildAudioFormat(AudioStreamInfo audioInfo) {
    Format.Builder builder =
        new Format.Builder()
            .setId(String.valueOf(audioInfo.streamNumber))
            .setContainerMimeType("video/x-ms-wmv")
            .setSampleMimeType(wmaTagToMimeType(audioInfo.waveFormatTag))
            .setCodecs(wmaTagToCodecString(audioInfo.waveFormatTag))
            .setChannelCount(audioInfo.channelCount)
            .setSampleRate(audioInfo.sampleRate)
            .setAverageBitrate(audioInfo.avgBitrateBps);
    int blockAlign = resolveBlockAlign(audioInfo);
    if (blockAlign > 0) {
      builder.setMaxInputSize(blockAlign);
    } else {
      Log.w(
          TAG,
          "Stream #"
              + audioInfo.streamNumber
              + ": blockAlign is 0 (VBR or missing nBlockAlign "
              + "and nAvgBytesPerSec); WMA decoder will fail without block_align");
    }
    builder.setInitializationData(buildWmaInitData(audioInfo.codecExtra, blockAlign));
    return builder.build();
  }

  /**
   * @param pixelWidthHeightRatio SAR; pass {@code 1f} when no SAR extension is present.
   */
  static Format buildVideoFormat(VideoStreamInfo videoInfo, float pixelWidthHeightRatio) {
    Format.Builder builder =
        new Format.Builder()
            .setId(String.valueOf(videoInfo.streamNumber))
            .setContainerMimeType("video/x-ms-wmv")
            .setSampleMimeType(fourccToMimeType(videoInfo.fourcc))
            .setCodecs(fourccToCodecString(videoInfo.fourcc))
            .setWidth(videoInfo.width)
            .setHeight(videoInfo.height)
            .setPixelWidthHeightRatio(pixelWidthHeightRatio);
    if (videoInfo.frameRate != Format.NO_VALUE) {
      builder.setFrameRate(videoInfo.frameRate);
    }
    if (videoInfo.averageBitrate != Format.NO_VALUE) {
      builder.setAverageBitrate(videoInfo.averageBitrate);
    }
    if (videoInfo.codecPrivate != null && videoInfo.codecPrivate.length > 0) {
      byte[] csd =
          isVc1Fourcc(videoInfo.fourcc)
              ? prependVc1StartCode(videoInfo.codecPrivate)
              : videoInfo.codecPrivate;
      builder.setInitializationData(Collections.singletonList(csd));
    }
    return builder.build();
  }
}
