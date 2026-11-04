/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Part of the NuvioTV FFmpeg extension.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/** Which video formats the FFmpeg extension should decode instead of the platform decoders. */
@UnstableApi
public final class FfmpegVideoFormats {

  private static final int NAL_UNIT_TYPE_SPS = 7;

  private FfmpegVideoFormats() {}

  /**
   * Returns whether the FFmpeg video renderer should take {@code format}.
   *
   * <p>VC-1 always qualifies. H.264 qualifies only for the profiles that allow more than 8 bits per
   * sample or chroma beyond 4:2:0 (Hi10P and friends), which Android TV hardware decoders almost
   * never support; ordinary H.264 stays on the hardware decoder.
   */
  public static boolean isSoftwareDecodeCandidate(Format format) {
    @Nullable String mimeType = format.sampleMimeType;
    if (MimeTypes.VIDEO_H264.equals(mimeType)) {
      return isHighBitDepthAvc(format);
    }
    return MimeTypes.VIDEO_VC1.equals(mimeType);
  }

  /**
   * Returns whether {@code format} is H.264 in a profile beyond 8-bit 4:2:0: High 10 (110), High
   * 4:2:2 (122), High 4:4:4 Predictive (244) or CAVLC 4:4:4 Intra (44).
   */
  public static boolean isHighBitDepthAvc(Format format) {
    if (!MimeTypes.VIDEO_H264.equals(format.sampleMimeType)) {
      return false;
    }
    int profile = getAvcProfileIdc(format);
    return profile == 110 || profile == 122 || profile == 244 || profile == 44;
  }

  /**
   * Returns the H.264 {@code profile_idc} of {@code format}, from its codecs string ({@code
   * avc1.PPCCLL}) or else from the SPS in its initialization data, or -1 if unknown.
   */
  public static int getAvcProfileIdc(Format format) {
    @Nullable String codecs = format.codecs;
    if (codecs != null) {
      for (String codec : codecs.split(",")) {
        String trimmed = codec.trim();
        if ((trimmed.startsWith("avc1.") || trimmed.startsWith("avc3."))
            && trimmed.length() >= 7) {
          try {
            return Integer.parseInt(trimmed.substring(5, 7), 16);
          } catch (NumberFormatException e) {
            // Fall back to the SPS.
          }
        }
      }
    }
    if (format.initializationData.isEmpty()) {
      return -1;
    }
    return getAvcProfileIdcFromSps(format.initializationData.get(0));
  }

  /**
   * Returns the {@code profile_idc} of an H.264 SPS NAL unit, with or without an Annex B start
   * code, or -1 if {@code sps} is not one.
   */
  /* package */ static int getAvcProfileIdcFromSps(byte[] sps) {
    int index = 0;
    while (index < sps.length && sps[index] == 0) {
      index++;
    }
    // A start code is zeros then 0x01; without one the NAL unit header is the first byte.
    int header = index > 0 && index < sps.length && sps[index] == 1 ? index + 1 : 0;
    if (header + 1 >= sps.length || (sps[header] & 0x1F) != NAL_UNIT_TYPE_SPS) {
      return -1;
    }
    return sps[header + 1] & 0xFF;
  }
}
