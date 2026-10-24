package com.nuvio.tv.core.player.avi;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableBitArray;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unpacks MPEG-4 Part 2 video written with packed B-frames (DivX 5, XviD "packed bitstream").
 *
 * <p>Such files keep one chunk per displayed frame. A reference frame travels in the same chunk as
 * the B-frame shown before it, and a not-coded VOP further on holds the slot where the reference
 * frame is shown. Decoders expect one VOP per buffer, so the two become separate samples, the
 * placeholder is dropped and each sample gets the time of the slot it is shown in. Samples are
 * output in decode order with presentation times. Streams without packing pass through unchanged.
 */
/* package */ final class PackedBFrameUnpacker {

  /** Receives the samples in decode order. */
  interface SampleOutput {
    void sample(byte[] data, int offset, int size, long timeUs, @C.BufferFlags int flags);
  }

  private static final int VOP_START_CODE = 0xB6;
  private static final int USER_DATA_START_CODE = 0xB2;
  private static final int VOL_START_CODE_FIRST = 0x20;
  private static final int VOL_START_CODE_LAST = 0x2F;
  private static final int VOP_TYPE_B = 2;
  private static final int MAX_PLACEHOLDER_BYTES = 19;
  private static final int MIN_VOL_BYTES = 18;
  private static final int MAX_USER_DATA_BYTES = 64;
  private static final int REFERENCES_FOR_B_FRAMES = 2;

  private boolean packed;
  private int timeIncrementBits;
  private int anchorsSinceReset;
  @Nullable private Held heldAnchor;
  private final ArrayList<Held> heldBFrames = new ArrayList<>();

  int splitChunks;
  int placeholdersUsed;
  int placeholdersDropped;
  int leadingBFramesDropped;
  int missingPlaceholders;

  /** Whether the stream was found to use packed B-frames. */
  boolean isPacked() {
    return packed;
  }

  /** Forgets held frames, after a seek. */
  void reset() {
    heldAnchor = null;
    heldBFrames.clear();
    anchorsSinceReset = 0;
  }

  void onChunk(
      byte[] data, int size, long timeUs, boolean keyFrame, SampleOutput output) {
    int flags = keyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0;
    int first = nextVop(data, 0, size);
    readHeaders(data, first < 0 ? size : first);
    int second = first < 0 ? -1 : nextVop(data, first + 4, size);
    int type = first < 0 ? -1 : vopType(data, first, size);
    boolean twin = second >= 0 && isAnchor(type) && vopType(data, second, size) == VOP_TYPE_B;
    if (twin) {
      packed = true;
    }
    if (!packed || first < 0) {
      if (isAnchor(type)) {
        anchorsSinceReset++;
      }
      output.sample(data, 0, size, timeUs, flags);
      return;
    }
    if (twin) {
      splitChunks++;
      releaseWithoutPlaceholder(timeUs, output);
      heldAnchor = new Held(Arrays.copyOf(data, second), timeUs, flags);
      anchorsSinceReset++;
      onBFrame(data, second, size - second, timeUs, output);
      return;
    }
    if (second < 0 && !isCoded(data, first, size)) {
      if (heldAnchor != null) {
        placeholdersUsed++;
        release(timeUs, output);
      } else {
        placeholdersDropped++;
      }
      return;
    }
    if (second < 0 && type == VOP_TYPE_B) {
      onBFrame(data, 0, size, timeUs, output);
      return;
    }
    releaseWithoutPlaceholder(timeUs, output);
    if (isAnchor(type)) {
      anchorsSinceReset++;
    }
    output.sample(data, 0, size, timeUs, flags);
  }

  private void onBFrame(byte[] data, int offset, int size, long timeUs, SampleOutput output) {
    if (anchorsSinceReset < REFERENCES_FOR_B_FRAMES) {
      leadingBFramesDropped++;
    } else if (heldAnchor != null) {
      heldBFrames.add(
          new Held(Arrays.copyOfRange(data, offset, offset + size), timeUs, /* flags= */ 0));
    } else {
      output.sample(data, offset, size, timeUs, /* flags= */ 0);
    }
  }

  // The placeholder never came, so the reference frame is shown between the last held frame and
  // the current chunk.
  private void releaseWithoutPlaceholder(long timeUs, SampleOutput output) {
    Held anchor = heldAnchor;
    if (anchor == null) {
      return;
    }
    missingPlaceholders++;
    long lastUs = anchor.timeUs;
    for (Held frame : heldBFrames) {
      lastUs = Math.max(lastUs, frame.timeUs);
    }
    release(lastUs < timeUs ? lastUs + (timeUs - lastUs) / 2 : lastUs + 1, output);
  }

  private void release(long anchorTimeUs, SampleOutput output) {
    Held anchor = heldAnchor;
    if (anchor == null) {
      return;
    }
    output.sample(anchor.data, 0, anchor.data.length, anchorTimeUs, anchor.flags);
    for (Held frame : heldBFrames) {
      output.sample(frame.data, 0, frame.data.length, frame.timeUs, frame.flags);
    }
    heldAnchor = null;
    heldBFrames.clear();
  }

  private void readHeaders(byte[] data, int limit) {
    for (int at = nextStartCode(data, 0, limit); at >= 0; at = nextStartCode(data, at + 3, limit)) {
      int code = data[at + 3] & 0xFF;
      if (code >= VOL_START_CODE_FIRST && code <= VOL_START_CODE_LAST) {
        readVideoObjectLayer(data, at + 4, limit);
      } else if (code == USER_DATA_START_CODE && isPackedUserData(data, at + 4, limit)) {
        packed = true;
      }
    }
  }

  private void readVideoObjectLayer(byte[] data, int offset, int limit) {
    if (limit - offset < MIN_VOL_BYTES) {
      return;
    }
    ParsableBitArray bits = new ParsableBitArray(data, limit);
    bits.setPosition(offset * 8);
    bits.skipBits(9); // random_accessible_vol, video_object_type_indication
    int verid = 1;
    if (bits.readBit()) {
      verid = bits.readBits(4);
      bits.skipBits(3);
    }
    if (bits.readBits(4) == 15) { // extended pixel aspect ratio
      bits.skipBits(16);
    }
    if (bits.readBit()) { // vol_control_parameters
      bits.skipBits(3);
      if (bits.readBit()) {
        bits.skipBits(79); // vbv_parameters
      }
    }
    int shape = bits.readBits(2);
    if (shape == 3 && verid != 1) {
      bits.skipBits(4);
    }
    bits.skipBits(1);
    int resolution = bits.readBits(16);
    if (resolution > 0) {
      timeIncrementBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(resolution - 1));
    }
  }

  private boolean isCoded(byte[] data, int vop, int size) {
    if (timeIncrementBits == 0) {
      return size > MAX_PLACEHOLDER_BYTES;
    }
    ParsableBitArray bits = new ParsableBitArray(data, size);
    bits.setPosition((vop + 4) * 8 + 2);
    int needed = timeIncrementBits + 3;
    boolean moduloTimeBase = true;
    while (moduloTimeBase && bits.bitsLeft() > needed) {
      moduloTimeBase = bits.readBit();
    }
    if (moduloTimeBase || bits.bitsLeft() < needed) {
      return size > MAX_PLACEHOLDER_BYTES;
    }
    bits.skipBits(1 + timeIncrementBits + 1);
    return bits.readBit();
  }

  private static boolean isPackedUserData(byte[] data, int offset, int limit) {
    int end = offset;
    int max = Math.min(limit, offset + MAX_USER_DATA_BYTES);
    while (end < max && data[end] != 0) {
      end++;
    }
    String text = new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    return text.startsWith("DivX") && text.endsWith("p");
  }

  private static boolean isAnchor(int vopType) {
    return vopType >= 0 && vopType != VOP_TYPE_B;
  }

  private static int vopType(byte[] data, int vop, int limit) {
    return vop + 4 < limit ? (data[vop + 4] & 0xFF) >> 6 : -1;
  }

  private static int nextVop(byte[] data, int from, int limit) {
    for (int at = nextStartCode(data, from, limit); at >= 0; at = nextStartCode(data, at + 3, limit)) {
      if ((data[at + 3] & 0xFF) == VOP_START_CODE) {
        return at;
      }
    }
    return -1;
  }

  private static int nextStartCode(byte[] data, int from, int limit) {
    for (int i = Math.max(from, 0); i + 3 < limit; i++) {
      if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
        return i;
      }
    }
    return -1;
  }

  private static final class Held {
    final byte[] data;
    final long timeUs;
    final @C.BufferFlags int flags;

    Held(byte[] data, long timeUs, @C.BufferFlags int flags) {
      this.data = data;
      this.timeUs = timeUs;
      this.flags = flags;
    }
  }
}
