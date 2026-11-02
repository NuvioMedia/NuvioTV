package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.util.List;

/**
 * Reads the HEVC alternative transfer characteristics SEI (payload type 147). HLG streams made to
 * stay compatible with SDR decoders signal BT.2020 in the SPS VUI and carry the HLG transfer only
 * in this SEI, which sits in the access units rather than in the codec private data.
 */
@UnstableApi
/* package */ final class HevcAlternativeTransferSei {

  private static final int NAL_UNIT_TYPE_SPS = 33;
  private static final int NAL_UNIT_TYPE_PREFIX_SEI = 39;
  // NAL unit types below this carry slice data; the prefix SEI comes before them.
  private static final int FIRST_NON_VCL_NAL_UNIT_TYPE = 32;
  private static final int SEI_PAYLOAD_TYPE_ALTERNATIVE_TRANSFER = 147;
  private static final int MAX_SEI_NAL_UNIT_SIZE = 64 * 1024;

  private HevcAlternativeTransferSei() {}

  /**
   * Peeks the leading NAL units of a length-prefixed access unit and returns the preferred ISO
   * transfer characteristics from its alternative transfer SEI, or {@link Format#NO_VALUE} if it
   * has none. Leaves the peek position where it was.
   */
  public static int peekPreferredTransfer(
      ExtractorInput input, int sampleSize, int nalUnitLengthFieldLength) throws IOException {
    if (nalUnitLengthFieldLength < 1 || nalUnitLengthFieldLength > 4) {
      return Format.NO_VALUE;
    }
    byte[] lengthField = new byte[4];
    byte[] nalHeader = new byte[1];
    long offset = 0;
    try {
      while (offset + nalUnitLengthFieldLength + 2 <= sampleSize) {
        if (!input.peekFully(lengthField, 0, nalUnitLengthFieldLength, true)) {
          return Format.NO_VALUE;
        }
        long nalLength = 0;
        for (int i = 0; i < nalUnitLengthFieldLength; i++) {
          nalLength = (nalLength << 8) | (lengthField[i] & 0xFF);
        }
        offset += nalUnitLengthFieldLength;
        if (nalLength < 2 || offset + nalLength > sampleSize) {
          return Format.NO_VALUE;
        }
        if (!input.peekFully(nalHeader, 0, 1, true)) {
          return Format.NO_VALUE;
        }
        int nalUnitType = (nalHeader[0] >> 1) & 0x3F;
        if (nalUnitType < FIRST_NON_VCL_NAL_UNIT_TYPE) {
          return Format.NO_VALUE;
        }
        if (nalUnitType == NAL_UNIT_TYPE_PREFIX_SEI && nalLength <= MAX_SEI_NAL_UNIT_SIZE) {
          byte[] nalUnit = new byte[(int) nalLength];
          nalUnit[0] = nalHeader[0];
          if (!input.peekFully(nalUnit, 1, nalUnit.length - 1, true)) {
            return Format.NO_VALUE;
          }
          int preferredTransfer = parsePreferredTransfer(nalUnit, nalUnit.length);
          if (preferredTransfer != Format.NO_VALUE) {
            return preferredTransfer;
          }
        } else if (!input.advancePeekPosition((int) nalLength - 1, true)) {
          return Format.NO_VALUE;
        }
        offset += nalLength;
      }
      return Format.NO_VALUE;
    } finally {
      input.resetPeekPosition();
    }
  }

  /**
   * Returns the preferred ISO transfer characteristics from a prefix SEI NAL unit (with its 2-byte
   * header and emulation prevention bytes), or {@link Format#NO_VALUE} if it has no alternative
   * transfer message.
   */
  /* package */ static int parsePreferredTransfer(byte[] nalUnit, int length) {
    if (length < 3) {
      return Format.NO_VALUE;
    }
    byte[] rbsp = new byte[length - 2];
    System.arraycopy(nalUnit, 2, rbsp, 0, rbsp.length);
    int rbspLength = NalUnitUtil.unescapeStream(rbsp, rbsp.length);
    int position = 0;
    // Stop at the rbsp trailing bits (a lone 0x80 byte).
    while (position < rbspLength && !(position == rbspLength - 1 && rbsp[position] == (byte) 0x80)) {
      int payloadType = 0;
      while (position < rbspLength && rbsp[position] == (byte) 0xFF) {
        payloadType += 255;
        position++;
      }
      if (position >= rbspLength) {
        return Format.NO_VALUE;
      }
      payloadType += rbsp[position++] & 0xFF;
      int payloadSize = 0;
      while (position < rbspLength && rbsp[position] == (byte) 0xFF) {
        payloadSize += 255;
        position++;
      }
      if (position >= rbspLength) {
        return Format.NO_VALUE;
      }
      payloadSize += rbsp[position++] & 0xFF;
      if (position + payloadSize > rbspLength) {
        return Format.NO_VALUE;
      }
      if (payloadType == SEI_PAYLOAD_TYPE_ALTERNATIVE_TRANSFER && payloadSize >= 1) {
        return rbsp[position] & 0xFF;
      }
      position += payloadSize;
    }
    return Format.NO_VALUE;
  }

  /** Whether the container colour info or the SPS VUI already declares a PQ or HLG transfer. */
  public static boolean declaresHdrTransfer(Format format) {
    if (format.colorInfo != null && isHdrTransfer(format.colorInfo.colorTransfer)) {
      return true;
    }
    @Nullable NalUnitUtil.H265SpsData sps = findSps(format.initializationData);
    return sps != null && isHdrTransfer(sps.colorTransfer);
  }

  public static boolean isHdrTransfer(@C.ColorTransfer int colorTransfer) {
    return colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG;
  }

  /**
   * Returns {@code format} with {@code colorTransfer} applied. Colour space, range and bit depth
   * come from the existing colour info, else from the SPS in the initialization data.
   */
  public static Format withColorTransfer(Format format, @C.ColorTransfer int colorTransfer) {
    ColorInfo.Builder builder;
    if (format.colorInfo != null) {
      builder = format.colorInfo.buildUpon();
    } else {
      builder =
          new ColorInfo.Builder()
              .setColorSpace(C.COLOR_SPACE_BT2020)
              .setColorRange(C.COLOR_RANGE_LIMITED);
      @Nullable NalUnitUtil.H265SpsData sps = findSps(format.initializationData);
      if (sps != null) {
        if (sps.colorSpace != Format.NO_VALUE) {
          builder.setColorSpace(sps.colorSpace);
        }
        if (sps.colorRange != Format.NO_VALUE) {
          builder.setColorRange(sps.colorRange);
        }
        builder
            .setLumaBitdepth(sps.bitDepthLumaMinus8 + 8)
            .setChromaBitdepth(sps.bitDepthChromaMinus8 + 8);
      }
    }
    return format.buildUpon().setColorInfo(builder.setColorTransfer(colorTransfer).build()).build();
  }

  @Nullable
  private static NalUnitUtil.H265SpsData findSps(List<byte[]> initializationData) {
    for (byte[] data : initializationData) {
      boolean[] prefixFlags = new boolean[3];
      int start = NalUnitUtil.findNalUnit(data, 0, data.length, prefixFlags);
      while (start < data.length) {
        int next = NalUnitUtil.findNalUnit(data, start + 3, data.length, prefixFlags);
        if (NalUnitUtil.getH265NalUnitType(data, start) == NAL_UNIT_TYPE_SPS) {
          try {
            return NalUnitUtil.parseH265SpsNalUnit(data, start + 3, next, null);
          } catch (RuntimeException e) {
            return null;
          }
        }
        start = next;
      }
    }
    return null;
  }
}
