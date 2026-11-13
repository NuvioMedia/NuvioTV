package com.nuvio.tv.core.player.avi;

import androidx.media3.common.util.ParsableByteArray;

/** The OpenDML extended header ('dmlh'): the frame count of the whole file, not only the first RIFF. */
/* package */ final class OpenDmlHeaderChunk implements AviChunk {

  public static OpenDmlHeaderChunk parseFrom(ParsableByteArray body) {
    return new OpenDmlHeaderChunk(body.bytesLeft() >= 4 ? body.readLittleEndianUnsignedInt() : 0);
  }

  public final long totalFrames;

  private OpenDmlHeaderChunk(long totalFrames) {
    this.totalFrames = totalFrames;
  }

  @Override
  public int getType() {
    return AviExtractor.FOURCC_dmlh;
  }
}
