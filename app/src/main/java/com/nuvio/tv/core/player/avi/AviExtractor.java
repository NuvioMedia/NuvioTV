/*
 * Copyright 2022 The Android Open Source Project
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
package com.nuvio.tv.core.player.avi;

import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.NoOpExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from the AVI container format.
 *
 * <p>Spec: https://docs.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference.
 */
@UnstableApi
public final class AviExtractor implements Extractor {

  private static final String TAG = "AviExtractor";

  public static final int FOURCC_RIFF = 0x46464952;
  public static final int FOURCC_AVI_ = 0x20495641; // AVI<space>
  public static final int FOURCC_LIST = 0x5453494c;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_avih = 0x68697661;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_hdrl = 0x6c726468;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strl = 0x6c727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_movi = 0x69766f6d;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_idx1 = 0x31786469;

  public static final int FOURCC_JUNK = 0x4b4e554a;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_indx = 0x78646e69;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_odml = 0x6c6d646f;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_dmlh = 0x686c6d64;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strf = 0x66727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strn = 0x6e727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_strh = 0x68727473;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_auds = 0x73647561;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_txts = 0x73747874;

  @SuppressWarnings("ConstantCaseForConstants")
  public static final int FOURCC_vids = 0x73646976;

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_SKIPPING_TO_HDRL,
    STATE_READING_HDRL_HEADER,
    STATE_READING_HDRL_BODY,
    STATE_FINDING_MOVI_HEADER,
    STATE_FINDING_IDX1_HEADER,
    STATE_READING_IDX1_BODY,
    STATE_READING_SAMPLES,
    STATE_READING_STANDARD_INDEX_HEADER,
    STATE_READING_STANDARD_INDEX_BODY,
  })
  private @interface State {}

  private static final int STATE_SKIPPING_TO_HDRL = 0;
  private static final int STATE_READING_HDRL_HEADER = 1;
  private static final int STATE_READING_HDRL_BODY = 2;
  private static final int STATE_FINDING_MOVI_HEADER = 3;
  private static final int STATE_FINDING_IDX1_HEADER = 4;
  private static final int STATE_READING_IDX1_BODY = 5;
  private static final int STATE_READING_SAMPLES = 6;
  private static final int STATE_READING_STANDARD_INDEX_HEADER = 7;
  private static final int STATE_READING_STANDARD_INDEX_BODY = 8;

  private static final int AVIIF_KEYFRAME = 16;
  private static final int AVI_INDEX_OF_CHUNKS = 1;
  private static final int STANDARD_INDEX_HEADER_BYTES = 24;
  private static final int MAX_STANDARD_INDEX_BYTES = 16 * 1024 * 1024;
  private static final long MAX_HEADER_LIST_SEARCH_BYTES = 1024 * 1024;
  private static final long NON_KEY_FRAME_SIZE_BIT = 0x80000000L;

  /** Receives the chunk index the reader uses, from idx1 or from the OpenDML indexes. */
  public interface IndexListener {

    /**
     * Called once with the first {@code count} entries of each array: the chunk id, the AVIIF
     * flags, the file position of the chunk header and the size of the chunk data.
     */
    void onIndex(int[] chunkIds, int[] flags, long[] chunkPositions, int[] sizes, int count);
  }

  /**
   * Flags controlling the behavior of the extractor. Possible flag value is {@link
   * #FLAG_EMIT_RAW_SUBTITLE_DATA}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      flag = true,
      value = {FLAG_EMIT_RAW_SUBTITLE_DATA})
  public @interface Flags {}

  /**
   * Flag to use the source subtitle formats without modification. If unset, subtitles will be
   * transcoded to {@link MimeTypes#APPLICATION_MEDIA3_CUES} during extraction.
   */
  public static final int FLAG_EMIT_RAW_SUBTITLE_DATA = 1;

  /**
   * Maximum size to skip using {@link ExtractorInput#skip}. Boxes larger than this size are skipped
   * using {@link #RESULT_SEEK}.
   */
  private static final long RELOAD_MINIMUM_SEEK_DISTANCE = 256 * 1024;

  private final ParsableByteArray scratch;
  private final ChunkHeaderHolder chunkHeaderHolder;
  private final boolean parseSubtitlesDuringExtraction;
  private final SubtitleParser.Factory subtitleParserFactory;

  private @State int state;
  private ExtractorOutput extractorOutput;
  private @MonotonicNonNull AviMainHeaderChunk aviHeader;
  private long durationUs;
  private ChunkReader[] chunkReaders;

  private long pendingReposition;
  @Nullable private ChunkReader currentChunkReader;
  private int hdrlSize;
  private long moviStart;
  private long moviEnd;
  private int idx1BodySize;
  private boolean seekMapHasBeenOutput;
  private boolean followsRiffSegments;
  private long[] standardIndexPositions;
  private int nextStandardIndex;
  private int standardIndexBodySize;
  private final IndexEntries indexEntries;
  @Nullable private IndexListener indexListener;

  /**
   * @deprecated Use {@link #AviExtractor(int, SubtitleParser.Factory)} instead.
   */
  @Deprecated
  public AviExtractor() {
    this(FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED);
  }

  /**
   * Constructs an instance.
   *
   * @param extractorFlags Flags that control the extractor's behavior.
   * @param subtitleParserFactory The {@link SubtitleParser.Factory} for parsing subtitles during
   *     extraction.
   */
  public AviExtractor(@Flags int extractorFlags, SubtitleParser.Factory subtitleParserFactory) {
    this.subtitleParserFactory = subtitleParserFactory;
    parseSubtitlesDuringExtraction = (extractorFlags & FLAG_EMIT_RAW_SUBTITLE_DATA) == 0;
    scratch = new ParsableByteArray(/* limit= */ 12);
    chunkHeaderHolder = new ChunkHeaderHolder();
    extractorOutput = new NoOpExtractorOutput();
    chunkReaders = new ChunkReader[0];
    standardIndexPositions = new long[0];
    indexEntries = new IndexEntries();
    moviStart = C.INDEX_UNSET;
    moviEnd = C.INDEX_UNSET;
    hdrlSize = C.LENGTH_UNSET;
    durationUs = C.TIME_UNSET;
  }

  public void setIndexListener(@Nullable IndexListener indexListener) {
    this.indexListener = indexListener;
  }

  // Extractor implementation.

  @Override
  public void init(ExtractorOutput output) {
    this.state = STATE_SKIPPING_TO_HDRL;
    this.extractorOutput =
        parseSubtitlesDuringExtraction
            ? new SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
            : output;
    pendingReposition = C.INDEX_UNSET;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    input.peekFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
    scratch.setPosition(0);
    if (scratch.readLittleEndianInt() != FOURCC_RIFF) {
      return false;
    }
    scratch.skipBytes(4); // Skip the RIFF chunk length.
    return scratch.readLittleEndianInt() == FOURCC_AVI_;
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    if (resolvePendingReposition(input, seekPosition)) {
      return RESULT_SEEK;
    }
    switch (state) {
      case STATE_SKIPPING_TO_HDRL:
        // Check for RIFF and AVI fourcc's just in case the caller did not sniff, in order to
        // provide a meaningful error if the input is not an AVI file.
        if (sniff(input)) {
          input.skipFully(/* length= */ 12);
        } else {
          throw ParserException.createForMalformedContainer(
              /* message= */ "AVI Header List not found", /* cause= */ null);
        }
        state = STATE_READING_HDRL_HEADER;
        return RESULT_CONTINUE;
      case STATE_READING_HDRL_HEADER:
        input.peekFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
        input.resetPeekPosition();
        scratch.setPosition(0);
        chunkHeaderHolder.populateFrom(scratch);
        chunkHeaderHolder.listType = scratch.readLittleEndianInt();
        if (chunkHeaderHolder.chunkType != FOURCC_LIST
            || chunkHeaderHolder.listType != FOURCC_hdrl) {
          // Some muxers write a JUNK chunk between the RIFF header and the header list.
          long size = chunkHeaderHolder.size & 0xFFFFFFFFL;
          long next = input.getPosition() + 8 + size + (size & 1);
          if (next > MAX_HEADER_LIST_SEARCH_BYTES) {
            throw ParserException.createForMalformedContainer(
                /* message= */ "hdrl expected, found: " + chunkHeaderHolder.listType,
                /* cause= */ null);
          }
          pendingReposition = next;
          return RESULT_CONTINUE;
        }
        input.skipFully(12);
        hdrlSize = chunkHeaderHolder.size;
        state = STATE_READING_HDRL_BODY;
        return RESULT_CONTINUE;
      case STATE_READING_HDRL_BODY:
        // hdrlSize includes the LIST type (hdrl), so we subtract 4 to the size.
        int bytesToRead = hdrlSize - 4;
        ParsableByteArray hdrlBody = new ParsableByteArray(bytesToRead);
        input.readFully(hdrlBody.getData(), /* offset= */ 0, bytesToRead);
        parseHdrlBody(hdrlBody);
        state = STATE_FINDING_MOVI_HEADER;
        return RESULT_CONTINUE;
      case STATE_FINDING_MOVI_HEADER:
        if (moviStart != C.INDEX_UNSET && input.getPosition() != moviStart) {
          pendingReposition = moviStart;
          return RESULT_CONTINUE;
        }
        input.peekFully(scratch.getData(), /* offset= */ 0, /* length= */ 12);
        input.resetPeekPosition();
        scratch.setPosition(0);
        chunkHeaderHolder.populateFrom(scratch);
        int listType = scratch.readLittleEndianInt();
        if (chunkHeaderHolder.chunkType == FOURCC_RIFF) {
          // We are at the start of the file. The movi chunk is in the RIFF chunk, so we skip the
          // header, so as to read the RIFF chunk's body.
          input.skipFully(12);
          return RESULT_CONTINUE;
        }
        if (chunkHeaderHolder.chunkType != FOURCC_LIST || listType != FOURCC_movi) {
          // The chunk header (8 bytes) plus the whole body.
          pendingReposition = input.getPosition() + chunkHeaderHolder.size + 8;
          return RESULT_CONTINUE;
        }
        moviStart = input.getPosition();
        // Size includes the list type, but not the LIST or size fields, so we add 8.
        moviEnd = moviStart + chunkHeaderHolder.size + 8;
        if (!seekMapHasBeenOutput) {
          if (standardIndexPositions.length > 0) {
            nextStandardIndex = 0;
            state = STATE_READING_STANDARD_INDEX_HEADER;
            pendingReposition = standardIndexPositions[0];
            return RESULT_CONTINUE;
          }
          if (Assertions.checkNotNull(aviHeader).hasIndex()) {
            state = STATE_FINDING_IDX1_HEADER;
            pendingReposition = moviEnd;
            return RESULT_CONTINUE;
          } else {
            extractorOutput.seekMap(new SeekMap.Unseekable(durationUs));
            seekMapHasBeenOutput = true;
          }
        }
        // No need to parse the idx1, so we start reading the samples from the movi chunk straight
        // away. We skip 12 bytes to move to the start of the movi's body.
        pendingReposition = input.getPosition() + 12;
        state = STATE_READING_SAMPLES;
        return RESULT_CONTINUE;
      case STATE_FINDING_IDX1_HEADER:
        input.readFully(scratch.getData(), /* offset= */ 0, /* length= */ 8);
        scratch.setPosition(0);
        int idx1Fourcc = scratch.readLittleEndianInt();
        int boxSize = scratch.readLittleEndianInt();
        if (idx1Fourcc == FOURCC_idx1) {
          state = STATE_READING_IDX1_BODY;
          idx1BodySize = boxSize;
        } else {
          // This one is not idx1, skip to the next box.
          pendingReposition = input.getPosition() + boxSize;
        }
        return RESULT_CONTINUE;
      case STATE_READING_IDX1_BODY:
        ParsableByteArray idx1Body = new ParsableByteArray(idx1BodySize);
        input.readFully(idx1Body.getData(), /* offset= */ 0, /* length= */ idx1BodySize);
        parseIdx1Body(idx1Body);
        state = STATE_READING_SAMPLES;
        pendingReposition = moviStart;
        return RESULT_CONTINUE;
      case STATE_READING_STANDARD_INDEX_HEADER:
        input.readFully(scratch.getData(), /* offset= */ 0, /* length= */ 8);
        scratch.setPosition(4);
        long standardIndexSize = scratch.readLittleEndianUnsignedInt();
        if (standardIndexSize < STANDARD_INDEX_HEADER_BYTES
            || standardIndexSize > MAX_STANDARD_INDEX_BYTES) {
          useLegacyIndex();
          return RESULT_CONTINUE;
        }
        standardIndexBodySize = (int) standardIndexSize;
        state = STATE_READING_STANDARD_INDEX_BODY;
        return RESULT_CONTINUE;
      case STATE_READING_STANDARD_INDEX_BODY:
        ParsableByteArray standardIndexBody = new ParsableByteArray(standardIndexBodySize);
        input.readFully(
            standardIndexBody.getData(), /* offset= */ 0, /* length= */ standardIndexBodySize);
        if (!parseStandardIndex(standardIndexBody)) {
          useLegacyIndex();
          return RESULT_CONTINUE;
        }
        nextStandardIndex++;
        if (nextStandardIndex < standardIndexPositions.length) {
          state = STATE_READING_STANDARD_INDEX_HEADER;
          pendingReposition = standardIndexPositions[nextStandardIndex];
        } else {
          commitIndex(/* sortByPosition= */ true);
          state = STATE_READING_SAMPLES;
          pendingReposition = moviStart;
        }
        return RESULT_CONTINUE;
      case STATE_READING_SAMPLES:
        return readMoviChunks(input);
      default:
        throw new AssertionError(); // Should never happen.
    }
  }

  @Override
  public void seek(long position, long timeUs) {
    pendingReposition = C.INDEX_UNSET;
    currentChunkReader = null;
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.seekToPosition(position);
    }
    if (position == 0) {
      if (chunkReaders.length == 0) {
        // Still unprepared.
        state = STATE_SKIPPING_TO_HDRL;
      } else {
        state = STATE_FINDING_MOVI_HEADER;
      }
      return;
    }
    state = STATE_READING_SAMPLES;
  }

  @Override
  public void release() {
    // Nothing to release.
  }

  // Internal methods.

  /**
   * Returns whether a {@link #RESULT_SEEK} is required for the pending reposition. A seek may not
   * be necessary when the desired position (as held by {@link #pendingReposition}) is after the
   * {@link ExtractorInput#getPosition() current position}, but not further than {@link
   * #RELOAD_MINIMUM_SEEK_DISTANCE}.
   */
  private boolean resolvePendingReposition(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    boolean needSeek = false;
    if (pendingReposition != C.INDEX_UNSET) {
      long currentPosition = input.getPosition();
      if (pendingReposition < currentPosition
          || pendingReposition > currentPosition + RELOAD_MINIMUM_SEEK_DISTANCE) {
        seekPosition.position = pendingReposition;
        needSeek = true;
      } else {
        // The distance to the target position is short enough that it makes sense to just skip the
        // bytes, instead of doing a seek which might re-create an HTTP connection.
        input.skipFully((int) (pendingReposition - currentPosition));
      }
    }
    pendingReposition = C.INDEX_UNSET;
    return needSeek;
  }

  private void parseHdrlBody(ParsableByteArray hrdlBody) throws IOException {
    ListChunk headerList = ListChunk.parseFrom(FOURCC_hdrl, hrdlBody);
    if (headerList.getType() != FOURCC_hdrl) {
      throw ParserException.createForMalformedContainer(
          /* message= */ "Unexpected header list type " + headerList.getType(), /* cause= */ null);
    }
    @Nullable AviMainHeaderChunk aviHeader = headerList.getChild(AviMainHeaderChunk.class);
    if (aviHeader == null) {
      throw ParserException.createForMalformedContainer(
          /* message= */ "AviHeader not found", /* cause= */ null);
    }
    this.aviHeader = aviHeader;
    long totalFrames = aviHeader.totalFrames & 0xFFFFFFFFL;
    for (AviChunk aviChunk : headerList.children) {
      if (aviChunk.getType() == FOURCC_odml) {
        // OpenDML: the movie goes on in further RIFF AVIX segments after the first one.
        followsRiffSegments = true;
        @Nullable
        OpenDmlHeaderChunk openDmlHeader =
            ((ListChunk) aviChunk).getChild(OpenDmlHeaderChunk.class);
        if (openDmlHeader != null) {
          totalFrames = max(totalFrames, openDmlHeader.totalFrames);
        }
      }
    }
    // This is usually wrong, so it will be overwritten by video if present
    durationUs = totalFrames * aviHeader.frameDurationUs;
    ArrayList<ChunkReader> chunkReaderList = new ArrayList<>();
    ArrayList<SuperIndexChunk> superIndexes = new ArrayList<>();
    boolean everyStreamHasSuperIndex = true;
    int streamId = 0;
    for (AviChunk aviChunk : headerList.children) {
      if (aviChunk.getType() == FOURCC_strl) {
        ListChunk streamList = (ListChunk) aviChunk;
        // Note the streamId needs to increment even if the corresponding `strl` is discarded.
        // See
        // https://docs.microsoft.com/en-us/windows/win32/directshow/avi-riff-file-reference#avi-stream-headers.
        @Nullable ChunkReader chunkReader = processStreamList(streamList, streamId++);
        if (chunkReader != null) {
          chunkReaderList.add(chunkReader);
          @Nullable SuperIndexChunk superIndex = streamList.getChild(SuperIndexChunk.class);
          if (superIndex == null) {
            everyStreamHasSuperIndex = false;
          } else {
            superIndexes.add(superIndex);
          }
        }
      }
    }
    chunkReaders = chunkReaderList.toArray(new ChunkReader[0]);
    if (everyStreamHasSuperIndex && !superIndexes.isEmpty()) {
      followsRiffSegments = true;
      int count = 0;
      for (SuperIndexChunk superIndex : superIndexes) {
        count += superIndex.indexOffsets.length;
      }
      standardIndexPositions = new long[count];
      int at = 0;
      for (SuperIndexChunk superIndex : superIndexes) {
        for (long offset : superIndex.indexOffsets) {
          standardIndexPositions[at++] = offset;
        }
      }
      Arrays.sort(standardIndexPositions);
    }
    extractorOutput.endTracks();
  }

  /** Builds and outputs the {@link SeekMap} from the idx1 chunk. */
  private void parseIdx1Body(ParsableByteArray body) {
    long seekOffset = peekSeekOffset(body);
    indexEntries.clear();
    while (body.bytesLeft() >= 16) {
      int chunkId = body.readLittleEndianInt();
      int flags = body.readLittleEndianInt();
      long offset = body.readLittleEndianUnsignedInt() + seekOffset;
      int size = body.readLittleEndianInt();
      indexEntries.add(chunkId, flags, offset, size);
    }
    commitIndex(/* sortByPosition= */ false);
  }

  /** Adds the entries of an OpenDML standard index ('ix##'); false when it cannot be read. */
  private boolean parseStandardIndex(ParsableByteArray body) {
    int longsPerEntry = body.readLittleEndianUnsignedShort();
    body.skipBytes(1); // bIndexSubType.
    int indexType = body.readUnsignedByte();
    long entriesInUse = body.readLittleEndianUnsignedInt();
    int chunkId = body.readLittleEndianInt();
    long baseOffset = body.readLittleEndianLong();
    body.skipBytes(4); // dwReserved.
    if (indexType != AVI_INDEX_OF_CHUNKS || longsPerEntry != 2 || baseOffset < 0) {
      return false;
    }
    long count = Math.min(entriesInUse, body.bytesLeft() / 8);
    for (long i = 0; i < count; i++) {
      // The offset points at the chunk data, after the 8 byte chunk header.
      long dataOffset = body.readLittleEndianUnsignedInt();
      long size = body.readLittleEndianUnsignedInt();
      int flags = (size & NON_KEY_FRAME_SIZE_BIT) == 0 ? AVIIF_KEYFRAME : 0;
      indexEntries.add(
          chunkId, flags, baseOffset + dataOffset - 8, (int) (size & ~NON_KEY_FRAME_SIZE_BIT));
    }
    return true;
  }

  /** Falls back to idx1 when the OpenDML indexes cannot be read. */
  private void useLegacyIndex() {
    Log.w(TAG, "OpenDML index unreadable, using idx1");
    standardIndexPositions = new long[0];
    indexEntries.clear();
    if (Assertions.checkNotNull(aviHeader).hasIndex()) {
      state = STATE_FINDING_IDX1_HEADER;
      pendingReposition = moviEnd;
    } else {
      extractorOutput.seekMap(new SeekMap.Unseekable(durationUs));
      seekMapHasBeenOutput = true;
      state = STATE_READING_SAMPLES;
      pendingReposition = moviStart;
    }
  }

  /** Hands the collected index to the chunk readers and outputs the {@link SeekMap}. */
  private void commitIndex(boolean sortByPosition) {
    if (sortByPosition) {
      indexEntries.sortByPosition();
    }
    for (int i = 0; i < indexEntries.count; i++) {
      ChunkReader chunkReader = getChunkReader(indexEntries.chunkIds[i]);
      if (chunkReader == null) {
        // We ignore unknown chunk IDs.
        continue;
      }
      chunkReader.appendIndexChunk(
          indexEntries.positions[i],
          /* isKeyFrame= */ (indexEntries.flags[i] & AVIIF_KEYFRAME) == AVIIF_KEYFRAME);
    }
    for (ChunkReader chunkReader : chunkReaders) {
      chunkReader.commitIndex();
    }
    if (indexListener != null) {
      indexListener.onIndex(
          indexEntries.chunkIds,
          indexEntries.flags,
          indexEntries.positions,
          indexEntries.sizes,
          indexEntries.count);
    }
    indexEntries.release();
    seekMapHasBeenOutput = true;
    if (chunkReaders.length == 0) {
      extractorOutput.seekMap(new SeekMap.Unseekable(durationUs));
    } else {
      extractorOutput.seekMap(new AviSeekMap(durationUs));
    }
  }

  private long peekSeekOffset(ParsableByteArray idx1Body) {
    // The spec states the offset is based on the start of the movi list type fourcc, but it also
    // says some files base the offset on the start of the file. We use a best effort approach to
    // figure out which is the case. See:
    // https://docs.microsoft.com/en-us/previous-versions/windows/desktop/api/Aviriff/ns-aviriff-avioldindex#dwoffset.
    if (idx1Body.bytesLeft() < 16) {
      // There are no full entries in the index, meaning we don't need to apply an offset.
      return 0;
    }
    int startingPosition = idx1Body.getPosition();
    idx1Body.skipBytes(8); // Skip chunkId (4 bytes) and flags (4 bytes).
    int offset = idx1Body.readLittleEndianInt();

    // moviStart points at the start of the LIST, while the seek offset is based at the start of the
    // movi fourCC, so we add 8 to reconcile the difference.
    long seekOffset = offset > moviStart ? 0L : moviStart + 8;
    idx1Body.setPosition(startingPosition);
    return seekOffset;
  }

  @Nullable
  private ChunkReader getChunkReader(int chunkId) {
    for (ChunkReader chunkReader : chunkReaders) {
      if (chunkReader.handlesChunkId(chunkId)) {
        return chunkReader;
      }
    }
    return null;
  }

  private int readMoviChunks(ExtractorInput input) throws IOException {
    if (!followsRiffSegments && input.getPosition() >= moviEnd) {
      return C.RESULT_END_OF_INPUT;
    } else if (currentChunkReader != null) {
      if (currentChunkReader.onChunkData(input)) {
        currentChunkReader = null;
      }
    } else {
      if (!alignInputToEvenPosition(input)
          || !input.peekFully(scratch.getData(), /* offset= */ 0, 8, /* allowEndOfInput= */ true)) {
        return C.RESULT_END_OF_INPUT;
      }
      scratch.setPosition(0);
      int chunkType = scratch.readLittleEndianInt();
      if (chunkType == FOURCC_LIST || (followsRiffSegments && chunkType == FOURCC_RIFF)) {
        // Step into the list (movi, or 'rec ' groups of interleaved chunks) past its type. A RIFF
        // AVIX segment holds the next movi list.
        input.skipFully(12);
        input.resetPeekPosition();
        return RESULT_CONTINUE;
      }
      int size = scratch.readLittleEndianInt();
      if (chunkType == FOURCC_JUNK) {
        pendingReposition = input.getPosition() + size + 8;
        return RESULT_CONTINUE;
      }
      input.skipFully(8);
      input.resetPeekPosition();
      ChunkReader chunkReader = getChunkReader(chunkType);
      if (chunkReader == null) {
        // No handler for this chunk. We skip it.
        pendingReposition = input.getPosition() + size;
        return RESULT_CONTINUE;
      } else {
        chunkReader.onChunkStart(size);
        this.currentChunkReader = chunkReader;
      }
    }
    return RESULT_CONTINUE;
  }

  @Nullable
  private ChunkReader processStreamList(ListChunk streamList, int streamId) {
    AviStreamHeaderChunk aviStreamHeaderChunk = streamList.getChild(AviStreamHeaderChunk.class);
    StreamFormatChunk streamFormatChunk = streamList.getChild(StreamFormatChunk.class);
    if (aviStreamHeaderChunk == null) {
      Log.w(TAG, "Missing Stream Header");
      return null;
    }
    if (streamFormatChunk == null) {
      Log.w(TAG, "Missing Stream Format");
      return null;
    }
    long durationUs = aviStreamHeaderChunk.getDurationUs();
    Format streamFormat = streamFormatChunk.format;
    Format.Builder builder = streamFormat.buildUpon();
    builder.setId(streamId);
    int suggestedBufferSize = aviStreamHeaderChunk.suggestedBufferSize;
    if (suggestedBufferSize != 0) {
      builder.setMaxInputSize(suggestedBufferSize);
    }
    StreamNameChunk streamName = streamList.getChild(StreamNameChunk.class);
    if (streamName != null) {
      builder.setLabel(streamName.name);
    }
    int trackType = MimeTypes.getTrackType(streamFormat.sampleMimeType);
    if (trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO) {
      TrackOutput trackOutput = extractorOutput.track(streamId, trackType);
      trackOutput.format(builder.build());
      trackOutput.durationUs(durationUs);
      this.durationUs = max(this.durationUs, durationUs);
      return new ChunkReader(
          streamId, aviStreamHeaderChunk, trackOutput, streamFormat.sampleMimeType);
    } else {
      // We don't currently support tracks other than video and audio.
      return null;
    }
  }

  /**
   * Skips one byte from the given {@code input} if the current position is odd.
   *
   * <p>This isn't documented anywhere, but AVI files are aligned to even bytes and fill gaps with
   * zeros.
   */
  private static boolean alignInputToEvenPosition(ExtractorInput input) throws IOException {
    if ((input.getPosition() & 1) == 1) {
      return input.skipFully(1, /* allowEndOfInput= */ true);
    }
    return true;
  }

  // Internal classes.

  private class AviSeekMap implements SeekMap {

    private final long durationUs;

    public AviSeekMap(long durationUs) {
      this.durationUs = durationUs;
    }

    @Override
    public boolean isSeekable() {
      return true;
    }

    @Override
    public long getDurationUs() {
      return durationUs;
    }

    @Override
    public SeekPoints getSeekPoints(long timeUs) {
      SeekPoints result = chunkReaders[0].getSeekPoints(timeUs);
      for (int i = 1; i < chunkReaders.length; i++) {
        SeekPoints seekPoints = chunkReaders[i].getSeekPoints(timeUs);
        if (seekPoints.first.position < result.first.position) {
          result = seekPoints;
        }
      }
      return result;
    }
  }

  /** Index entries in growable arrays, as read from idx1 or from the OpenDML indexes. */
  private static final class IndexEntries {
    private static final int INITIAL_CAPACITY = 1024;
    private static final int INDEX_BITS = 22;

    public int[] chunkIds = new int[0];
    public int[] flags = new int[0];
    public long[] positions = new long[0];
    public int[] sizes = new int[0];
    public int count;

    public void add(int chunkId, int flag, long position, int size) {
      if (count == positions.length) {
        int capacity = max(INITIAL_CAPACITY, count * 2);
        chunkIds = Arrays.copyOf(chunkIds, capacity);
        flags = Arrays.copyOf(flags, capacity);
        positions = Arrays.copyOf(positions, capacity);
        sizes = Arrays.copyOf(sizes, capacity);
      }
      chunkIds[count] = chunkId;
      flags[count] = flag;
      positions[count] = position;
      sizes[count] = size;
      count++;
    }

    public void clear() {
      count = 0;
    }

    public void release() {
      chunkIds = new int[0];
      flags = new int[0];
      positions = new long[0];
      sizes = new int[0];
      count = 0;
    }

    /** Puts the entries in file order: each standard index lists one stream of one segment. */
    public void sortByPosition() {
      if (count >= 1 << INDEX_BITS) {
        return;
      }
      long[] keys = new long[count];
      for (int i = 0; i < count; i++) {
        keys[i] = (positions[i] << INDEX_BITS) | i;
      }
      Arrays.sort(keys);
      int[] sortedChunkIds = new int[count];
      int[] sortedFlags = new int[count];
      long[] sortedPositions = new long[count];
      int[] sortedSizes = new int[count];
      for (int i = 0; i < count; i++) {
        int from = (int) (keys[i] & ((1 << INDEX_BITS) - 1));
        sortedChunkIds[i] = chunkIds[from];
        sortedFlags[i] = flags[from];
        sortedPositions[i] = positions[from];
        sortedSizes[i] = sizes[from];
      }
      chunkIds = sortedChunkIds;
      flags = sortedFlags;
      positions = sortedPositions;
      sizes = sortedSizes;
    }
  }

  private static class ChunkHeaderHolder {
    public int chunkType;
    public int size;
    public int listType;

    public void populateWithListHeaderFrom(ParsableByteArray headerBytes) throws ParserException {
      populateFrom(headerBytes);
      if (chunkType != AviExtractor.FOURCC_LIST) {
        throw ParserException.createForMalformedContainer(
            /* message= */ "LIST expected, found: " + chunkType, /* cause= */ null);
      }
      listType = headerBytes.readLittleEndianInt();
    }

    public void populateFrom(ParsableByteArray headerBytes) {
      chunkType = headerBytes.readLittleEndianInt();
      size = headerBytes.readLittleEndianInt();
      listType = 0;
    }
  }
}
