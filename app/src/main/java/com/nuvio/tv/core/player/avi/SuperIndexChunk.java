package com.nuvio.tv.core.player.avi;

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableByteArray;
import java.util.Arrays;

/** An OpenDML super index ('indx' in a stream list): where the stream's standard index chunks are. */
/* package */ final class SuperIndexChunk implements AviChunk {

  private static final int AVI_INDEX_OF_INDEXES = 0;
  private static final int ENTRY_LONGS = 4;
  private static final int HEADER_BYTES = 24;

  @Nullable
  public static SuperIndexChunk parseFrom(ParsableByteArray body) {
    if (body.bytesLeft() < HEADER_BYTES) {
      return null;
    }
    int longsPerEntry = body.readLittleEndianUnsignedShort();
    body.skipBytes(1); // bIndexSubType.
    int indexType = body.readUnsignedByte();
    long entriesInUse = body.readLittleEndianUnsignedInt();
    body.skipBytes(16); // dwChunkId, dwReserved[3].
    if (indexType != AVI_INDEX_OF_INDEXES || longsPerEntry != ENTRY_LONGS) {
      return null;
    }
    int count = (int) Math.min(entriesInUse, body.bytesLeft() / (ENTRY_LONGS * 4));
    long[] offsets = new long[count];
    int[] sizes = new int[count];
    int used = 0;
    for (int i = 0; i < count; i++) {
      long offset = body.readLittleEndianLong();
      long size = body.readLittleEndianUnsignedInt();
      body.skipBytes(4); // dwDuration.
      if (offset > 0) {
        offsets[used] = offset;
        sizes[used] = (int) Math.min(size, Integer.MAX_VALUE);
        used++;
      }
    }
    if (used == 0) {
      return null;
    }
    return new SuperIndexChunk(Arrays.copyOf(offsets, used), Arrays.copyOf(sizes, used));
  }

  /** File positions of the standard index chunks ('ix##'), at their chunk header. */
  public final long[] indexOffsets;

  public final int[] indexSizes;

  private SuperIndexChunk(long[] indexOffsets, int[] indexSizes) {
    this.indexOffsets = indexOffsets;
    this.indexSizes = indexSizes;
  }

  @Override
  public int getType() {
    return AviExtractor.FOURCC_indx;
  }
}
