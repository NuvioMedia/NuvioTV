package com.nuvio.tv.core.player.dvmkv;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

/**
 * Utility methods for parsing DTS frames, vendored and extended for DTS-HD and DTS:X support.
 */
@UnstableApi
public final class DtsUtil {

  /**
   * Returns the DTS MIME type of the given sample.
   *
   * @param data The sample data.
   * @return The DTS MIME type.
   */
  public static String getDtsAudioMimeType(byte[] data) {
    if (data.length < 4) {
      return MimeTypes.AUDIO_DTS;
    }
    int word = (data[0] & 0xFF) << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
    switch (androidx.media3.extractor.DtsUtil.getFrameType(word)) {
      case androidx.media3.extractor.DtsUtil.FRAME_TYPE_CORE:
        int frameSize = androidx.media3.extractor.DtsUtil.getDtsFrameSize(data);
        if (frameSize != C.LENGTH_UNSET && data.length >= frameSize + 4) {
          int offset = frameSize;
          int nextWord = (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
          if (androidx.media3.extractor.DtsUtil.getFrameType(nextWord)
              == androidx.media3.extractor.DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
            return MimeTypes.AUDIO_DTS_HD;
          }
        }
        return MimeTypes.AUDIO_DTS;
      case androidx.media3.extractor.DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM:
        return MimeTypes.AUDIO_DTS_HD;
      case androidx.media3.extractor.DtsUtil.FRAME_TYPE_UHD_SYNC:
      case androidx.media3.extractor.DtsUtil.FRAME_TYPE_UHD_NON_SYNC:
        return MimeTypes.AUDIO_DTS_X;
      default:
        return MimeTypes.AUDIO_DTS;
    }
  }

  private DtsUtil() {}
}
