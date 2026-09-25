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
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses the ASF Header Object and all child objects. Single-use: construct, call {@link #read}
 * once, then access results via the accessors.
 */
final class AsfHeaderReader {

  private static final String TAG = AsfHeaderReader.class.getSimpleName();

  /** 100-ns units per second; used to convert AvgFrameTime to fps. */
  private static final double HUNDRED_NS_PER_SEC = 10_000_000.0;

  /** Guard against corrupt files declaring a huge header size. */
  private static final int MAX_HEADER_BODY_SIZE = 10 * 1024 * 1024;

  private final SparseArray<AudioStreamInfo> audioStreams = new SparseArray<>();
  private final SparseArray<VideoStreamInfo> videoStreams = new SparseArray<>();
  private final SparseArray<List<PayloadExtension>> payloadExtensions = new SparseArray<>();
  private final SparseArray<long[]> pendingVideoExtProps = new SparseArray<>();

  private long packetCount;
  private int packetSize;
  private long durationUs = C.TIME_UNSET;
  private boolean isBroadcast;
  private long prerollMs;
  private long firstPacketPosition;

  void read(ExtractorInput input) throws IOException {
    readHeaderObject(input);
    readDataObjectHeader(input);
  }

  SparseArray<AudioStreamInfo> audioStreams() {
    return audioStreams;
  }

  SparseArray<VideoStreamInfo> videoStreams() {
    return videoStreams;
  }

  SparseArray<List<PayloadExtension>> payloadExtensions() {
    return payloadExtensions;
  }

  long packetCount() {
    return packetCount;
  }

  int packetSize() {
    return packetSize;
  }

  long durationUs() {
    return durationUs;
  }

  boolean isBroadcast() {
    return isBroadcast;
  }

  long prerollMs() {
    return prerollMs;
  }

  long firstPacketPosition() {
    return firstPacketPosition;
  }

  private void readHeaderObject(ExtractorInput input) throws IOException {
    byte[] preamble = new byte[30];
    input.readFully(preamble, 0, 30);
    ParsableByteArray preambleBuf = new ParsableByteArray(preamble);
    if (!AsfGuid.matches(preambleBuf, AsfGuid.HEADER_OBJECT)) {
      throw new IOException("Not a valid ASF file: missing Header Object GUID");
    }
    long headerObjectSize = AsfLittleEndian.readU64(preambleBuf);
    preambleBuf.skipBytes(6); // num_headers (4) + reserved (2)
    long bodyLenLong = headerObjectSize - 30L;
    if (bodyLenLong < 0 || bodyLenLong > MAX_HEADER_BODY_SIZE) {
      throw new IOException("ASF header size out of range: " + headerObjectSize);
    }
    int bodyLen = (int) bodyLenLong;
    byte[] body = new byte[bodyLen];
    input.readFully(body, 0, bodyLen);
    parseChildObjects(new ParsableByteArray(body));
  }

  private void readDataObjectHeader(ExtractorInput input) throws IOException {
    // Data Object layout: GUID(16) + size(8) + FileID GUID(16) + packetCount(8) + reserved(2) = 50
    byte[] dataHeader = new byte[50];
    input.readFully(dataHeader, 0, 50);
    ParsableByteArray dataHeaderBuf = new ParsableByteArray(dataHeader);
    if (!AsfGuid.matches(dataHeaderBuf, AsfGuid.DATA_OBJECT)) {
      throw new IOException("Expected ASF Data Object after Header Object");
    }
    dataHeaderBuf.skipBytes(8); // object size
    dataHeaderBuf.skipBytes(16); // File ID GUID
    long filePacketCount = AsfLittleEndian.readU64(dataHeaderBuf);
    if (packetCount == 0 && filePacketCount > 0) {
      packetCount = filePacketCount;
    }
    firstPacketPosition = input.getPosition();
  }

  /**
   * Iterates child objects inside a Header Object body buffer. Header Extension children are
   * recursively flattened into the same pass.
   */
  private void parseChildObjects(ParsableByteArray buf) {
    while (buf.bytesLeft() >= 24) {
      byte[] objGuid = AsfGuid.read(buf);
      long objSize = buf.readLittleEndianLong();
      int bodyLen = (int) (objSize - 24L);
      if (bodyLen < 0 || bodyLen > buf.bytesLeft()) {
        Log.w(TAG, "Corrupt child object size=" + objSize + "; aborting header parse");
        break;
      }
      int startPos = buf.getPosition();
      if (Arrays.equals(objGuid, AsfGuid.FILE_PROPERTIES)) {
        parseFileProperties(buf, bodyLen);
      } else if (Arrays.equals(objGuid, AsfGuid.STREAM_PROPERTIES)) {
        parseStreamProperties(buf, bodyLen);
      } else if (Arrays.equals(objGuid, AsfGuid.EXT_STREAM_PROPERTIES)) {
        parseExtStreamProperties(buf, bodyLen);
      } else if (Arrays.equals(objGuid, AsfGuid.HEADER_EXTENSION)) {
        parseHeaderExtension(buf, bodyLen);
      } else {
        buf.skipBytes(bodyLen);
      }
      int consumed = buf.getPosition() - startPos;
      if (consumed < bodyLen) {
        buf.skipBytes(bodyLen - consumed);
      }
    }
  }

  private void parseFileProperties(ParsableByteArray buf, int bodyLen) {
    if (bodyLen < 80) {
      return;
    }
    buf.skipBytes(16); // File ID GUID
    buf.skipBytes(8); // file_size
    buf.skipBytes(8); // create_time
    buf.skipBytes(8); // packet_count
    long playDuration100ns = AsfLittleEndian.readU64(buf);
    buf.skipBytes(8); // send_time
    prerollMs = buf.readLittleEndianUnsignedInt();
    buf.skipBytes(4); // ignore
    long flags = buf.readLittleEndianUnsignedInt();
    buf.skipBytes(4); // min_pktsize
    packetSize = (int) buf.readLittleEndianUnsignedInt(); // max_pktsize
    buf.skipBytes(4); // max_bitrate
    isBroadcast = (flags & 0x01) != 0;
    long playDurationMs = playDuration100ns / 10_000L;
    if (!isBroadcast && playDurationMs > prerollMs) {
      durationUs = (playDurationMs - prerollMs) * 1_000L;
    }
  }

  private void parseHeaderExtension(ParsableByteArray buf, int bodyLen) {
    if (bodyLen < 22) {
      buf.skipBytes(bodyLen);
      return;
    }
    buf.skipBytes(18); // reserved GUID (16) + reserved size field (2)
    long extDataSize = buf.readLittleEndianUnsignedInt();
    int extDataLen = (int) Math.min(extDataSize, bodyLen - 22);
    if (extDataLen > 0) {
      parseChildObjects(new ParsableByteArray(AsfLittleEndian.readBytes(buf, extDataLen)));
    }
  }

  private void parseStreamProperties(ParsableByteArray buf, int bodyLen) {
    if (bodyLen < 54) {
      return;
    }
    byte[] streamType = AsfGuid.read(buf);
    buf.skipBytes(16); // error correction type GUID
    buf.skipBytes(8); // time offset
    int typeSpecLen = (int) buf.readLittleEndianUnsignedInt();
    int errCorrLen = (int) buf.readLittleEndianUnsignedInt();
    int streamNumber = buf.readLittleEndianUnsignedShort() & 0x7F;
    buf.skipBytes(4); // reserved
    int maxDataLen = bodyLen - 54;
    if (typeSpecLen < 0
        || errCorrLen < 0
        || typeSpecLen > maxDataLen
        || errCorrLen > maxDataLen - typeSpecLen) {
      Log.w(TAG, "Stream #" + streamNumber + ": invalid type-specific or error-correction length");
      return;
    }
    byte[] typeSpec = AsfLittleEndian.readBytes(buf, typeSpecLen);
    if (Arrays.equals(streamType, AsfGuid.STREAM_TYPE_AUDIO)) {
      parseAudioStream(streamNumber, typeSpec, buf, errCorrLen);
    } else if (Arrays.equals(streamType, AsfGuid.STREAM_TYPE_VIDEO)) {
      parseVideoStream(streamNumber, typeSpec);
      buf.skipBytes(errCorrLen);
    } else {
      buf.skipBytes(errCorrLen);
    }
  }

  private void parseExtStreamProperties(ParsableByteArray buf, int bodyLen) {
    if (bodyLen < 64) {
      return;
    }
    buf.skipBytes(16); // start_time (8) + end_time (8)
    long dataBitrate = buf.readLittleEndianUnsignedInt();
    buf.skipBytes(28); // bucket_datasize(4) + init_bucket_fullness(4) + alt_leak_datarate(4) +
    // alt_bucket_datasize(4) + alt_init_bucket_fullness(4) + max_object_size(4) + flags(4)
    int streamNum = buf.readLittleEndianUnsignedShort() & 0x7F;
    buf.skipBytes(2); // stream_language_id_index
    long avgFrameTime100ns = AsfLittleEndian.readU64(buf);
    int streamNameCount = buf.readLittleEndianUnsignedShort();
    int payloadExtCount = buf.readLittleEndianUnsignedShort();
    applyVideoExtProps(streamNum, dataBitrate, avgFrameTime100ns);
    int remaining = bodyLen - 64;
    ParsableByteArray extBody =
        new ParsableByteArray(AsfLittleEndian.readBytes(buf, Math.min(remaining, buf.bytesLeft())));
    if (!skipStreamNames(extBody, streamNameCount)) {
      return;
    }
    List<PayloadExtension> exts = readPayloadExtensions(extBody, payloadExtCount);
    if (!exts.isEmpty()) {
      payloadExtensions.put(streamNum, exts);
    }
  }

  /**
   * Updates or stashes Extended Stream Properties for a video stream. Extended Stream Properties
   * may appear before or after the Stream Properties object.
   */
  private void applyVideoExtProps(int streamNum, long dataBitrate, long avgFrameTime100ns) {
    float frameRate =
        avgFrameTime100ns > 0 ? (float) (HUNDRED_NS_PER_SEC / avgFrameTime100ns) : Format.NO_VALUE;
    int bitrateInt = dataBitrate > 0 ? (int) dataBitrate : Format.NO_VALUE;
    VideoStreamInfo existing = videoStreams.get(streamNum);
    if (existing != null) {
      videoStreams.put(
          streamNum,
          new VideoStreamInfo(
              existing.streamNumber,
              existing.width,
              existing.height,
              existing.fourcc,
              existing.bitsPerSample,
              existing.codecPrivate,
              frameRate,
              bitrateInt));
    } else {
      pendingVideoExtProps.put(streamNum, new long[] {dataBitrate, avgFrameTime100ns});
    }
  }

  /**
   * @return {@code false} if the buffer ran out of data mid-entry.
   */
  private static boolean skipStreamNames(ParsableByteArray buf, int count) {
    for (int i = 0; i < count; i++) {
      if (buf.bytesLeft() < 4) {
        return false;
      }
      buf.skipBytes(2); // language_id_index
      int nameLen = buf.readLittleEndianUnsignedShort();
      if (buf.bytesLeft() < nameLen) {
        return false;
      }
      buf.skipBytes(nameLen);
    }
    return true;
  }

  private static List<PayloadExtension> readPayloadExtensions(ParsableByteArray buf, int count) {
    List<PayloadExtension> exts = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      if (buf.bytesLeft() < 22) { // GUID(16) + ext_size(2) + ext_info_len(4)
        break;
      }
      byte[] extGuid = AsfGuid.read(buf);
      int extSize = buf.readLittleEndianUnsignedShort();
      int extInfoLen = (int) buf.readLittleEndianUnsignedInt();
      if (extInfoLen < 0 || extInfoLen > buf.bytesLeft()) {
        break;
      }
      buf.skipBytes(extInfoLen);
      exts.add(new PayloadExtension(extGuid[0], extSize));
    }
    return exts;
  }

  private void parseAudioStream(
      int streamNumber, byte[] typeSpec, ParsableByteArray errCorrBuf, int errCorrLen) {
    if (typeSpec.length < 16) {
      return;
    }
    ParsableByteArray buf = new ParsableByteArray(typeSpec);
    int wFormatTag = buf.readLittleEndianUnsignedShort();
    int channelCount = buf.readLittleEndianUnsignedShort();
    int sampleRate = (int) buf.readLittleEndianUnsignedInt();
    int avgBytesPerSec = (int) buf.readLittleEndianUnsignedInt();
    int blockAlign = buf.readLittleEndianUnsignedShort();
    buf.skipBytes(2); // wBitsPerSample (not used for WMA)
    int cbSize = buf.bytesLeft() >= 2 ? buf.readLittleEndianUnsignedShort() : 0;
    @Nullable
    byte[] extra =
        (cbSize > 0 && buf.bytesLeft() >= cbSize) ? AsfLittleEndian.readBytes(buf, cbSize) : null;
    if (!AsfUtil.isSupportedWmaTag(wFormatTag)) {
      Log.w(
          TAG,
          "Stream #"
              + streamNumber
              + ": unsupported WAVEFORMATEX tag 0x"
              + Integer.toHexString(wFormatTag));
      return;
    }
    int[] descramble = readDescramblingParams(streamNumber, errCorrBuf, errCorrLen);
    audioStreams.put(
        streamNumber,
        new AudioStreamInfo(
            streamNumber,
            wFormatTag,
            channelCount,
            sampleRate,
            avgBytesPerSec,
            blockAlign,
            extra,
            descramble[0],
            descramble[1],
            descramble[2]));
  }

  /**
   * Reads descrambling params (ds_span, ds_packet_size, ds_chunk_size) from the Error Correction
   * Data block. Requires {@code errCorrLen >= 8}.
   */
  private int[] readDescramblingParams(
      int streamNumber, ParsableByteArray errCorrBuf, int errCorrLen) {
    if (errCorrLen < 8 || errCorrBuf.bytesLeft() < 8) {
      return new int[] {0, 0, 0};
    }
    int dsSpan = errCorrBuf.readUnsignedByte();
    int dsPacketSize = errCorrBuf.readLittleEndianUnsignedShort();
    int dsChunkSize = errCorrBuf.readLittleEndianUnsignedShort();
    errCorrBuf.skipBytes(3); // ds_data_size (2) + ds_silence_data (1)
    if (dsSpan > 1
        && (dsChunkSize == 0
            || dsPacketSize / dsChunkSize <= 1
            || dsPacketSize % dsChunkSize != 0)) {
      Log.w(TAG, "Stream #" + streamNumber + ": invalid descrambling params; disabling");
      return new int[] {0, 0, 0};
    }
    return new int[] {dsSpan, dsPacketSize, dsChunkSize};
  }

  private void parseVideoStream(int streamNumber, byte[] typeSpec) {
    if (typeSpec.length < 11) {
      return;
    }
    ParsableByteArray buf = new ParsableByteArray(typeSpec);
    int encodedWidth = (int) buf.readLittleEndianUnsignedInt();
    int encodedHeight = (int) buf.readLittleEndianUnsignedInt();
    buf.skipBytes(3); // reserved (1) + size field (2)
    if (buf.bytesLeft() < 40) {
      return;
    }
    int biSize = (int) buf.readLittleEndianUnsignedInt();
    int biWidth = buf.readLittleEndianInt();
    int biHeight = buf.readLittleEndianInt();
    buf.skipBytes(2); // biPlanes
    int biBitCount = buf.readLittleEndianUnsignedShort();
    int fourcc = buf.readLittleEndianInt();
    buf.skipBytes(20); // remaining BITMAPINFOHEADER fields
    int extraDataSize = biSize > 40 ? biSize - 40 : 0;
    @Nullable
    byte[] extra =
        (extraDataSize > 0 && buf.bytesLeft() >= extraDataSize)
            ? AsfLittleEndian.readBytes(buf, extraDataSize)
            : null;
    if (!AsfUtil.isSupportedVideoFourcc(fourcc)) {
      Log.w(
          TAG,
          "Stream #" + streamNumber + ": unsupported FOURCC " + AsfUtil.fourccToString(fourcc));
      return;
    }
    int width = biWidth > 0 ? biWidth : encodedWidth;
    int height = biHeight != 0 ? Math.abs(biHeight) : encodedHeight;
    float[] extProps = consumePendingVideoExtProps(streamNumber);
    videoStreams.put(
        streamNumber,
        new VideoStreamInfo(
            streamNumber,
            width,
            height,
            fourcc,
            biBitCount,
            extra,
            extProps[0],
            (int) extProps[1]));
  }

  /**
   * Consumes and returns [frameRate, averageBitrate] stashed for {@code streamNumber}, or NO_VALUE
   * pair.
   */
  private float[] consumePendingVideoExtProps(int streamNumber) {
    long[] pendingProps = pendingVideoExtProps.get(streamNumber);
    if (pendingProps == null) {
      return new float[] {Format.NO_VALUE, Format.NO_VALUE};
    }
    pendingVideoExtProps.remove(streamNumber);
    float frameRate =
        pendingProps[1] > 0 ? (float) (HUNDRED_NS_PER_SEC / pendingProps[1]) : Format.NO_VALUE;
    float bitrate = pendingProps[0] > 0 ? (float) pendingProps[0] : Format.NO_VALUE;
    return new float[] {frameRate, bitrate};
  }
}
