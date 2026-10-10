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
import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and decodes ASF data packets, routing sample data to the appropriate {@link TrackOutput}.
 */
final class AsfPacketReader {

  private static final String TAG = AsfPacketReader.class.getSimpleName();

  private static final int MAX_STREAM_NUMBER = 128; // ASF stream numbers are 7-bit (0-127)

  private final SparseArray<AudioStreamInfo> audioStreams;
  private final SparseArray<VideoStreamInfo> videoStreams;
  private final SparseArray<List<PayloadExtension>> payloadExtensions;
  private final SparseArray<TrackOutput> trackOutputs;
  private final int packetSize;
  private final long packetCount;
  private final long firstPacketPosition;
  private final long prerollMs;

  private final int[] lastSarNum = new int[MAX_STREAM_NUMBER];
  private final int[] lastSarDen = new int[MAX_STREAM_NUMBER];

  private final SparseArray<PendingSample> pendingSamples = new SparseArray<>();

  // 0 = unknown, >0 = standard ECC (0x82), <0 = absent/other
  private int usesStdEcc = 0;

  private final byte[] packetBuffer;

  // processPayloadExtensions() writes results here to avoid per-payload allocation.
  private long scratchPresentationMs;
  private boolean scratchTsIsPts;
  private int scratchRemaining;

  AsfPacketReader(
      SparseArray<AudioStreamInfo> audioStreams,
      SparseArray<VideoStreamInfo> videoStreams,
      SparseArray<List<PayloadExtension>> payloadExtensions,
      SparseArray<TrackOutput> trackOutputs,
      int packetSize,
      long packetCount,
      long firstPacketPosition,
      long prerollMs) {
    this.audioStreams = audioStreams;
    this.videoStreams = videoStreams;
    this.payloadExtensions = payloadExtensions;
    this.trackOutputs = trackOutputs;
    this.packetSize = packetSize;
    this.packetCount = packetCount;
    this.firstPacketPosition = firstPacketPosition;
    this.prerollMs = prerollMs;
    this.packetBuffer = new byte[packetSize];
  }

  /** Reads one packet from {@code input} and dispatches samples. Returns {@code false} on EOS. */
  boolean read(ExtractorInput input) throws IOException {
    if (hasReadAllPackets(input.getPosition())) {
      flushPendingSamplesAtEnd();
      return false;
    }
    int bytesRead = readPacket(input);
    if (bytesRead == 0) {
      flushPendingSamplesAtEnd();
      return false;
    }
    parsePacket(new ParsableByteArray(packetBuffer, bytesRead), bytesRead < packetSize);
    return true;
  }

  private int readPacket(ExtractorInput input) throws IOException {
    int totalBytesRead = 0;
    while (totalBytesRead < packetSize) {
      int bytesRead = input.read(packetBuffer, totalBytesRead, packetSize - totalBytesRead);
      if (bytesRead == C.RESULT_END_OF_INPUT) {
        break;
      }
      totalBytesRead += bytesRead;
    }
    return totalBytesRead;
  }

  private boolean hasReadAllPackets(long inputPosition) {
    if (packetCount <= 0 || firstPacketPosition < 0 || inputPosition < firstPacketPosition) {
      return false;
    }
    return (inputPosition - firstPacketPosition) / packetSize >= packetCount;
  }

  /** Resets transient state after a seek. */
  void reset() {
    pendingSamples.clear();
    usesStdEcc = 0;
  }

  private void parsePacket(ParsableByteArray buf, boolean allowIncompletePayload) {
    int propFlags;
    int segFlags;
    if (usesStdEcc > 0) {
      @Nullable int[] ecc = resyncEcc(buf);
      if (ecc == null) {
        Log.e(TAG, "ASF packet sync lost");
        return;
      }
      propFlags = ecc[0];
      segFlags = ecc[1];
    } else {
      @Nullable int[] header = readEccHeader(buf);
      if (header == null) {
        return;
      }
      propFlags = header[0];
      segFlags = header[1];
    }
    decodePpi(buf, propFlags, segFlags, allowIncompletePayload);
  }

  private @Nullable int[] resyncEcc(ParsableByteArray buf) {
    int prev2 = -1, prev1 = -1, curr = -1;
    int limit = Math.min(buf.bytesLeft(), 32768);
    while (limit-- > 0) {
      prev2 = prev1;
      prev1 = curr;
      curr = buf.readUnsignedByte();
      if (prev2 == 0x82 && prev1 == 0x00 && curr == 0x00) {
        break;
      }
    }
    if ((prev2 & 0x8f) != 0x82 || prev1 != 0 || curr != 0) {
      return null;
    }
    if (buf.bytesLeft() < 2) {
      return null;
    }
    return new int[] {buf.readUnsignedByte(), buf.readUnsignedByte()};
  }

  private @Nullable int[] readEccHeader(ParsableByteArray buf) {
    if (buf.bytesLeft() < 2) {
      return null;
    }
    int firstByte = buf.readUnsignedByte();
    int propFlags;
    if ((firstByte & 0x80) != 0) {
      if ((firstByte & 0x60) == 0) {
        if (buf.bytesLeft() < 2) {
          return null;
        }
        int secondByte = buf.readUnsignedByte();
        int thirdByte = buf.readUnsignedByte();
        int extra = (firstByte & 0x0F) - 2;
        if (extra > buf.bytesLeft()) {
          return null;
        }
        if (extra > 0) {
          buf.skipBytes(extra);
        }
        if (usesStdEcc == 0) {
          usesStdEcc = (firstByte == 0x82 && secondByte == 0x00 && thirdByte == 0x00) ? 1 : -1;
        }
      } else {
        int extra = firstByte & 0x0F;
        if (extra > buf.bytesLeft()) {
          return null;
        }
        buf.skipBytes(extra);
        usesStdEcc = -1;
      }
      if (buf.bytesLeft() < 2) {
        return null;
      }
      propFlags = buf.readUnsignedByte();
    } else {
      usesStdEcc = -1;
      propFlags = firstByte;
    }
    int segFlags = buf.readUnsignedByte();
    return new int[] {propFlags, segFlags};
  }

  private void decodePpi(
      ParsableByteArray buf, int propFlags, int segFlags, boolean allowIncompletePayload) {
    boolean multiPayload = (propFlags & 0x01) != 0;
    int seqType = (propFlags >> 1) & 0x03;
    int paddingType = (propFlags >> 3) & 0x03;
    int packetLenType = (propFlags >> 5) & 0x03;
    int repLenType = segFlags & 0x03;
    int offsetLenType = (segFlags >> 2) & 0x03;
    int objNumLenType = (segFlags >> 4) & 0x03;
    long packetLengthLong = AsfLittleEndian.readVarLen(buf, packetLenType);
    int packetLength = packetLenType == 0 ? packetSize : (int) packetLengthLong;
    AsfLittleEndian.readVarLen(buf, seqType);
    int paddingLen = (int) AsfLittleEndian.readVarLen(buf, paddingType);
    if (packetLength <= 0 || packetLength > packetSize || paddingLen >= packetLength) {
      return;
    }
    long sendTimeMs = buf.readLittleEndianUnsignedInt();
    buf.skipBytes(2);
    int dataEnd = packetLength - paddingLen;
    if (dataEnd < buf.getPosition()) {
      return;
    }
    if (dataEnd > buf.limit()) {
      if (!allowIncompletePayload) {
        return;
      }
      dataEnd = buf.limit();
    }
    if (multiPayload) {
      decodeMultiPayload(buf, sendTimeMs, dataEnd, repLenType, offsetLenType, objNumLenType);
    } else {
      decodeSinglePayload(buf, sendTimeMs, dataEnd, repLenType, offsetLenType, objNumLenType);
    }
  }

  private void decodeMultiPayload(
      ParsableByteArray buf,
      long sendTimeMs,
      int dataEnd,
      int repLenType,
      int offsetLenType,
      int objNumLenType) {
    if (buf.getPosition() >= dataEnd) {
      return;
    }
    int payloadLenType = (buf.readUnsignedByte() >> 6) & 0x03;
    while (buf.getPosition() < dataEnd && buf.bytesLeft() >= 4) {
      int raw = buf.readUnsignedByte();
      boolean keyFrame = (raw & 0x80) != 0;
      int streamNum = raw & 0x7F;
      AsfLittleEndian.readVarLen(buf, objNumLenType);
      int objectOffset = (int) AsfLittleEndian.readVarLen(buf, offsetLenType);
      ReplicatedData rep = readReplicatedData(buf, repLenType, streamNum);
      int payloadLen = (int) AsfLittleEndian.readVarLen(buf, payloadLenType);
      int payloadBytesLeft = dataEnd - buf.getPosition();
      if (payloadLen <= 0 || payloadLen > payloadBytesLeft) {
        break;
      }
      if (rep.timeDelta != ReplicatedData.ABSENT) {
        decodeCompressedPayload(buf, streamNum, payloadLen, objectOffset, rep.timeDelta, keyFrame);
        continue;
      }
      long timeUs = toTimeUs(rep, sendTimeMs);
      dispatchPayload(buf, streamNum, payloadLen, rep.objSize, timeUs, keyFrame, objectOffset);
    }
  }

  private void decodeCompressedPayload(
      ParsableByteArray buf,
      int streamNum,
      int payloadLen,
      int timeStartMs,
      long timeDeltaMs,
      boolean keyFrame) {
    int endPosition = buf.getPosition() + payloadLen;
    long sampleTimeMs = timeStartMs;
    while (buf.getPosition() < endPosition) {
      int sampleSize = buf.readUnsignedByte();
      if (sampleSize == 0) {
        sampleTimeMs += timeDeltaMs;
        continue;
      }
      if (sampleSize > endPosition - buf.getPosition()) {
        buf.setPosition(endPosition);
        return;
      }
      long timeUs = Math.max(0, (sampleTimeMs - prerollMs) * 1_000L);
      dispatchPayload(buf, streamNum, sampleSize, sampleSize, timeUs, keyFrame, 0);
      sampleTimeMs += timeDeltaMs;
    }
  }

  private void decodeSinglePayload(
      ParsableByteArray buf,
      long sendTimeMs,
      int dataEnd,
      int repLenType,
      int offsetLenType,
      int objNumLenType) {
    int raw = buf.readUnsignedByte();
    boolean keyFrame = (raw & 0x80) != 0;
    int streamNum = raw & 0x7F;
    AsfLittleEndian.readVarLen(buf, objNumLenType);
    int objectOffset = (int) AsfLittleEndian.readVarLen(buf, offsetLenType);
    ReplicatedData rep = readReplicatedData(buf, repLenType, streamNum);
    int payloadLen = dataEnd - buf.getPosition();
    if (payloadLen <= 0) {
      return;
    }
    long timeUs = toTimeUs(rep, sendTimeMs);
    dispatchPayload(buf, streamNum, payloadLen, rep.objSize, timeUs, keyFrame, objectOffset);
  }

  private ReplicatedData readReplicatedData(ParsableByteArray buf, int repLenType, int streamNum) {
    int repLen = (int) AsfLittleEndian.readVarLen(buf, repLenType);
    if (repLen == 1) {
      return ReplicatedData.compressed(buf.readUnsignedByte());
    }
    if (repLen < 8) {
      if (repLen > 0) {
        buf.skipBytes(repLen);
      }
      return ReplicatedData.absent();
    }
    long objSize = buf.readLittleEndianUnsignedInt();
    long presentationMs = buf.readLittleEndianUnsignedInt();
    int remaining = repLen - 8;
    boolean tsIsPts = false;
    List<PayloadExtension> exts = payloadExtensions.get(streamNum);
    if (exts != null) {
      processPayloadExtensions(buf, exts, remaining, streamNum, presentationMs);
      presentationMs = scratchPresentationMs;
      tsIsPts = scratchTsIsPts;
      remaining = scratchRemaining;
    }
    if (remaining > 0) {
      buf.skipBytes(remaining);
    }
    return new ReplicatedData(objSize, presentationMs, tsIsPts, ReplicatedData.ABSENT);
  }

  private void processPayloadExtensions(
      ParsableByteArray buf,
      List<PayloadExtension> exts,
      int remaining,
      int streamNum,
      long presentationMs) {
    boolean tsIsPts = false;
    for (PayloadExtension ext : exts) {
      if (remaining <= 0) {
        break;
      }
      int extSize = ext.fixedSize;
      if (extSize == PayloadExtension.VARIABLE_SIZE) {
        if (remaining < 2) {
          break;
        }
        extSize = buf.readLittleEndianUnsignedShort();
        remaining -= 2;
      }
      if (extSize < 0 || extSize > remaining) {
        break;
      }
      int startPos = buf.getPosition();
      if (ext.type == PayloadExtension.TYPE_SAR && extSize >= 2) {
        int num = buf.readUnsignedByte();
        int den = buf.readUnsignedByte();
        if (streamNum < MAX_STREAM_NUMBER
            && num > 0
            && den > 0
            && (num != lastSarNum[streamNum] || den != lastSarDen[streamNum])) {
          lastSarNum[streamNum] = num;
          lastSarDen[streamNum] = den;
          updateVideoSar(streamNum, num, den);
        }
      } else if (ext.type == PayloadExtension.TYPE_PTS && extSize >= 24) {
        buf.skipBytes(8);
        long ts0 = buf.readLittleEndianLong();
        tsIsPts = true; // FFmpeg sets ts_is_pts=1 unconditionally
        presentationMs =
            (ts0 != -1L) ? ts0 / 10_000L : ReplicatedData.ABSENT; // -1 = no valid timestamp
      }
      int consumed = buf.getPosition() - startPos;
      if (consumed < extSize) {
        buf.skipBytes(extSize - consumed);
      }
      remaining -= extSize;
    }
    scratchPresentationMs = presentationMs;
    scratchTsIsPts = tsIsPts;
    scratchRemaining = remaining;
  }

  private long toTimeUs(ReplicatedData rep, long sendTimeMs) {
    long ms;
    if (rep.tsIsPts) {
      if (rep.presentationMs == ReplicatedData.ABSENT) {
        return C.TIME_UNSET; // ts0 was -1: no valid timestamp
      }
      ms = rep.presentationMs;
    } else {
      ms = (rep.presentationMs != ReplicatedData.ABSENT) ? rep.presentationMs : sendTimeMs;
    }
    return Math.max(0, (ms - prerollMs) * 1_000L);
  }

  private void dispatchPayload(
      ParsableByteArray buf,
      int streamNum,
      int payloadLen,
      long objSize,
      long timeUs,
      boolean keyFrame,
      int objectOffset) {
    TrackOutput output = trackOutputs.get(streamNum);
    if (output == null) {
      buf.skipBytes(payloadLen);
      return;
    }
    boolean isAudio = audioStreams.indexOfKey(streamNum) >= 0;
    @C.BufferFlags int flags = (keyFrame || isAudio) ? C.BUFFER_FLAG_KEY_FRAME : 0;
    boolean complete = (objectOffset == 0) && (objSize <= 0 || payloadLen >= objSize);
    if (complete) {
      discardPending(streamNum);
      byte[] data = AsfLittleEndian.readBytes(buf, payloadLen);
      data = maybeDescramble(data, streamNum);
      writeSample(output, data, timeUs, flags);
      return;
    }
    appendFragment(buf, streamNum, payloadLen, objSize, timeUs, flags, objectOffset, output);
  }

  private void appendFragment(
      ParsableByteArray buf,
      int streamNum,
      int payloadLen,
      long objSize,
      long timeUs,
      @C.BufferFlags int flags,
      int objectOffset,
      TrackOutput output) {
    if (objectOffset == 0) {
      discardPending(streamNum);
      pendingSamples.put(streamNum, new PendingSample((int) objSize, timeUs, flags));
    }
    PendingSample sample = pendingSamples.get(streamNum);
    if (sample == null) {
      Log.w(
          TAG,
          "Stream #"
              + streamNum
              + ": continuation fragment without first fragment"
              + " at objectOffset="
              + objectOffset
              + "; dropping");
      buf.skipBytes(payloadLen);
      return;
    }
    byte[] fragment = AsfLittleEndian.readBytes(buf, payloadLen);
    if (!sample.appendAt(fragment, objectOffset, payloadLen)) {
      Log.w(
          TAG,
          "Stream #"
              + streamNum
              + ": out-of-order fragment"
              + " expected="
              + sample.fragOffset
              + " got="
              + objectOffset
              + "; dropping");
      return;
    }
    if (objSize > 0 && sample.bytesWritten >= (int) objSize) {
      pendingSamples.delete(streamNum);
      byte[] assembled = Arrays.copyOf(sample.data, sample.bytesWritten);
      assembled = maybeDescramble(assembled, streamNum);
      writeSample(output, assembled, sample.timeUs, sample.flags);
    }
  }

  private void discardPending(int streamNum) {
    if (pendingSamples.get(streamNum) != null) {
      pendingSamples.delete(streamNum);
      Log.w(TAG, "Stream #" + streamNum + ": discarding incomplete fragment");
    }
  }

  private void flushPendingSamplesAtEnd() {
    for (int i = 0; i < pendingSamples.size(); i++) {
      int streamNum = pendingSamples.keyAt(i);
      PendingSample sample = pendingSamples.valueAt(i);
      TrackOutput output = trackOutputs.get(streamNum);
      if (output == null || sample.bytesWritten <= 0) {
        continue;
      }
      AudioStreamInfo audio = audioStreams.get(streamNum);
      if (audio != null && audio.requiresDescrambling()) {
        continue;
      }
      byte[] assembled = Arrays.copyOf(sample.data, sample.bytesWritten);
      writeSample(output, assembled, sample.timeUs, sample.flags);
    }
    pendingSamples.clear();
  }

  private static void writeSample(
      TrackOutput output, byte[] data, long timeUs, @C.BufferFlags int flags) {
    ParsableByteArray wrapped = new ParsableByteArray(data);
    output.sampleData(wrapped, data.length);
    output.sampleMetadata(timeUs, flags, data.length, 0, null);
  }

  private void updateVideoSar(int streamNum, int sarNum, int sarDen) {
    TrackOutput output = trackOutputs.get(streamNum);
    VideoStreamInfo videoInfo = videoStreams.get(streamNum);
    if (output == null || videoInfo == null) {
      return;
    }
    output.format(AsfUtil.buildVideoFormat(videoInfo, (float) sarNum / sarDen));
  }

  private byte[] maybeDescramble(byte[] data, int streamNum) {
    AudioStreamInfo audio = audioStreams.get(streamNum);
    if (audio == null || !audio.requiresDescrambling()) {
      return data;
    }
    int span = audio.dsSpan;
    int pktSize = audio.dsPacketSize;
    int chunkSize = audio.dsChunkSize;
    if (data.length != pktSize * span) {
      Log.e(
          TAG,
          "Stream #"
              + streamNum
              + ": descramble size mismatch "
              + data.length
              + " != "
              + pktSize
              + " * "
              + span
              + "; skipping");
      return data;
    }
    byte[] result = new byte[data.length];
    int offset = 0;
    while (offset < data.length) {
      int off = offset / chunkSize;
      int row = off / span;
      int col = off % span;
      int idx = row + col * (pktSize / chunkSize);
      System.arraycopy(data, idx * chunkSize, result, offset, chunkSize);
      offset += chunkSize;
    }
    return result;
  }
}
