/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Software video decoder for the NuvioTV FFmpeg extension. It follows the structure of the
 * AndroidX Media3 libvpx / libgav1 decoders (Apache-2.0).
 */
package androidx.media3.decoder.ffmpeg;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.PriorityQueue;

/**
 * FFmpeg software video decoder, used for streams that Android TV hardware often cannot decode
 * (VC-1, and high bit depth H.264 such as Hi10P). Frames come out as 8-bit I420; the native side
 * dithers 9 to 16-bit and 4:2:2 / 4:4:4 pictures down to that.
 *
 * <p>The FFmpeg decoders return at most one frame per packet (after a delay for reordering and
 * frame threading), so each input produces zero or one output.
 *
 * <p>VC-1 in Matroska (VfW mode) and ASF is stamped in decode order, while other muxers write
 * presentation timestamps. Each VC-1 output frame therefore takes the smallest timestamp among the
 * inputs not yet output, which is the presentation order in both cases. H.264 inputs always carry
 * presentation timestamps, so its frames keep the timestamp FFmpeg carried through from the packet.
 */
@UnstableApi
public final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  // LINT.IfChange
  private static final int VIDEO_DECODER_SUCCESS = 0;
  private static final int VIDEO_DECODER_NO_FRAME = 1;
  private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;
  // LINT.ThenChange(../../../../../../jni/ffmpeg_jni.cc)

  /** More pending timestamps than this means some inputs never produced a frame; drop the oldest. */
  private static final int MAX_PENDING_TIMESTAMPS = 16;

  private final String codecName;
  /** Whether output frames are stamped from the pending input timestamps (VC-1). */
  private final boolean usesPendingTimestamps;
  private final long nativeContext;
  private final PriorityQueue<Long> pendingTimestampsUs;

  private volatile @C.VideoOutputMode int outputMode;

  /**
   * Creates an FFmpeg video decoder.
   *
   * @param numInputBuffers The number of input buffers.
   * @param numOutputBuffers The number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer.
   * @param threads The number of decoding threads, or 0 to let FFmpeg choose.
   * @param format The format of the stream to decode.
   * @throws FfmpegDecoderException If the decoder cannot be created.
   */
  public FfmpegVideoDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      Format format)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native library.");
    }
    String mimeType = Assertions.checkNotNull(format.sampleMimeType);
    @Nullable String codecName = FfmpegLibrary.getCodecName(mimeType);
    if (codecName == null) {
      throw new FfmpegDecoderException("Unsupported video MIME type: " + mimeType);
    }
    this.codecName = codecName;
    usesPendingTimestamps = !"h264".equals(codecName);
    pendingTimestampsUs = new PriorityQueue<>();
    nativeContext =
        ffmpegInitialize(
            codecName, getExtraData(format.initializationData), format.width, format.height, threads);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize FFmpeg " + codecName + " decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  /** Sets the output mode for frames rendered by the decoder. */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      ffmpegFlush(nativeContext);
      pendingTimestampsUs.clear();
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    int result = ffmpegDecode(nativeContext, inputData, inputSize, inputBuffer.timeUs);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("FFmpeg " + codecName + " decode error.");
    } else if (result == VIDEO_DECODER_ERROR_INVALID_DATA) {
      // A damaged packet should not stop playback; FFmpeg resyncs on the next one. Its picture is
      // never shown, so its timestamp is not queued.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    long frameTimeUs;
    if (usesPendingTimestamps) {
      pendingTimestampsUs.add(inputBuffer.timeUs);
      if (result == VIDEO_DECODER_NO_FRAME) {
        while (pendingTimestampsUs.size() > MAX_PENDING_TIMESTAMPS) {
          pendingTimestampsUs.poll();
        }
        outputBuffer.shouldBeSkipped = true;
        return null;
      }
      frameTimeUs = Assertions.checkNotNull(pendingTimestampsUs.poll());
    } else {
      if (result == VIDEO_DECODER_NO_FRAME) {
        outputBuffer.shouldBeSkipped = true;
        return null;
      }
      frameTimeUs = ffmpegGetFrameTimeUs(nativeContext);
      if (frameTimeUs == C.TIME_UNSET) {
        frameTimeUs = inputBuffer.timeUs;
      }
    }
    if (!isAtLeastOutputStartTimeUs(frameTimeUs)) {
      // Decoded only to keep reference frames; no need to copy it out.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    outputBuffer.shouldBeSkipped = false;
    outputBuffer.init(frameTimeUs, outputMode, /* supplementalData= */ null);
    if (!ffmpegCopyFrame(nativeContext, outputBuffer)) {
      return new FfmpegDecoderException("Failed to copy the decoded frame.");
    }
    outputBuffer.format = inputBuffer.format;
    return null;
  }

  /** Renders the output buffer to the surface. Used with {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV}. */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegDecoderException("Invalid output mode.");
    }
    if (!ffmpegRenderFrame(nativeContext, surface, outputBuffer)) {
      throw new FfmpegDecoderException("Failed to render the frame to the surface.");
    }
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
  }

  @Nullable
  private static byte[] getExtraData(List<byte[]> initializationData) {
    if (initializationData.isEmpty()) {
      return null;
    }
    if (initializationData.size() == 1) {
      return initializationData.get(0);
    }
    int size = 0;
    for (byte[] data : initializationData) {
      size += data.length;
    }
    byte[] extraData = new byte[size];
    int offset = 0;
    for (byte[] data : initializationData) {
      System.arraycopy(data, 0, extraData, offset, data.length);
      offset += data.length;
    }
    return extraData;
  }

  private native long ffmpegInitialize(
      String codecName, @Nullable byte[] extraData, int width, int height, int threads);

  private native int ffmpegDecode(long context, ByteBuffer encoded, int length, long timeUs);

  /** Returns the timestamp of the decoded frame, or {@link C#TIME_UNSET} if it has none. */
  private native long ffmpegGetFrameTimeUs(long context);

  private native boolean ffmpegCopyFrame(long context, VideoDecoderOutputBuffer outputBuffer);

  private native boolean ffmpegRenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer outputBuffer);

  private native void ffmpegFlush(long context);

  private native void ffmpegRelease(long context);
}
