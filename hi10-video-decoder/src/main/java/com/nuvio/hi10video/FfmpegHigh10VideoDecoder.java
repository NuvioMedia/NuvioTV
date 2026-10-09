/*
 * Video decoder architecture and flush queue adapted from AndroidX Media PR #1591,
 * commit 1649087fbe3ce1b2c51abc320782be0b600b311b.
 */
package com.nuvio.hi10video;

import android.util.Log;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

final class FfmpegHigh10VideoDecoder
    extends SimpleDecoder<
        DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException> {
  private static final String TAG = "NuvioHi10";
  private static final int SUCCESS = 0;
  private static final int INVALID_DATA = -1;
  private static final int OTHER_ERROR = -2;
  private static final int TRY_AGAIN = -3;
  private static final int SURFACE_ERROR = -4;

  private static final class PendingInput {
    final ByteBuffer data;
    final long timeUs;

    PendingInput(ByteBuffer data, long timeUs) {
      this.data = data;
      this.timeUs = timeUs;
    }
  }

  private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>();
  @Nullable private final High10PipelineProbe pipelineProbe = BuildConfig.DEBUG
      ? new High10PipelineProbe(SystemClock::elapsedRealtimeNanos) : null;
  private volatile int pendingInputCount;
  @Nullable private final byte[] extraData;
  private long nativeContext;
  @C.VideoOutputMode private volatile int outputMode;

  FfmpegHigh10VideoDecoder(
      int inputBuffers,
      int outputBuffers,
      int initialInputBufferSize,
      int threads,
      Format format)
      throws FfmpegHigh10VideoDecoderException {
    super(new DecoderInputBuffer[inputBuffers], new VideoDecoderOutputBuffer[outputBuffers]);
    if (!NuvioHi10VideoLibrary.hasH264Decoder()) {
      throw new FfmpegHigh10VideoDecoderException("Isolated FFmpeg H.264 decoder unavailable.");
    }
    extraData = concatenate(format.initializationData);
    nativeContext =
        nativeInitialize(extraData, threads, format.rotationDegrees, format.width, format.height);
    if (nativeContext == 0) {
      throw new FfmpegHigh10VideoDecoderException("Failed to initialize FFmpeg H.264 decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
    Log.i(
        TAG,
        "renderer=FfmpegHigh10VideoRenderer decoder=FFmpeg h264 profile=High10 "
            + "conversion=yuv420p10le->yuv420p output=ANativeWindow");
  }

  void setOutputMode(@C.VideoOutputMode int outputMode) {
    // Media3 also calls this when the Surface changes without changing its mode.
    // SurfaceHolder can reuse the same Java Surface for a new native generation.
    // Rendering to the cached, abandoned window would consume the one frame that
    // Media3 permits while paused, leaving the recreated Surface black.
    nativeReleaseWindow(nativeContext);
    this.outputMode = outputMode;
  }

  @Override
  public String getName() {
    return "nuvio-hi10-ffmpeg-" + NuvioHi10VideoLibrary.getVersion();
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        NuvioHi10VideoLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(
        outputBuffer -> {
          if (pipelineProbe != null) pipelineProbe.frameReleased(outputBuffer);
          releaseOutputBuffer(outputBuffer);
        });
  }

  @Override
  @Nullable
  protected FfmpegHigh10VideoDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = nativeReset(nativeContext);
      pendingInputs.clear();
      pendingInputCount = 0;
      if (pipelineProbe != null) pipelineProbe.reset();
      if (nativeContext == 0) {
        return new FfmpegHigh10VideoDecoderException("FFmpeg flush failed.");
      }
    }

    if (pipelineProbe != null) pipelineProbe.decodeStarted(inputBuffer);

    while (!pendingInputs.isEmpty()) {
      PendingInput pending = pendingInputs.peekFirst();
      int result = nativeSendPacket(nativeContext, pending.data, pending.data.limit(), pending.timeUs);
      if (result == TRY_AGAIN) {
        break;
      }
      pendingInputs.removeFirst();
      pendingInputCount = pendingInputs.size();
      if (pipelineProbe != null) pipelineProbe.pendingDepth(pendingInputCount);
      if (result == OTHER_ERROR) {
        return new FfmpegHigh10VideoDecoderException("Pending packet decode failed.");
      }
    }

    ByteBuffer input = Util.castNonNull(inputBuffer.data);
    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
    int sendResult = nativeSendPacket(nativeContext, input, input.limit(), inputBuffer.timeUs);
    if (sendResult == TRY_AGAIN) {
      pendingInputs.addLast(copyInput(input, inputBuffer.timeUs));
      pendingInputCount = pendingInputs.size();
      if (pipelineProbe != null) pipelineProbe.pendingDepth(pendingInputCount);
    } else if (sendResult == INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (sendResult == OTHER_ERROR) {
      return new FfmpegHigh10VideoDecoderException("Packet decode failed.");
    }

    if (!decodeOnly) {
      outputBuffer.init(inputBuffer.timeUs, outputMode, null);
    }
    int receiveResult = nativeReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
    if (receiveResult == SUCCESS && !decodeOnly) {
      if (pipelineProbe != null) pipelineProbe.frameDecoded(outputBuffer);
    }
    if (receiveResult == OTHER_ERROR) {
      return new FfmpegHigh10VideoDecoderException("Frame decode failed.");
    }
    if (receiveResult == INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
    } else if (!decodeOnly) {
      outputBuffer.format = inputBuffer.format;
    }
    return null;
  }

  private static PendingInput copyInput(ByteBuffer input, long timeUs) {
    int originalPosition = input.position();
    input.position(0);
    ByteBuffer copy = ByteBuffer.allocateDirect(input.limit());
    copy.put(input);
    copy.flip();
    input.position(originalPosition);
    return new PendingInput(copy, timeUs);
  }

  synchronized FfmpegHigh10VideoPerformance getPerformanceSnapshot(
      int rendered, int dropped, int skipped, int maxConsecutiveDropped) {
    if (pipelineProbe == null) {
      throw new IllegalStateException("Playback telemetry is available only in Debug builds.");
    }
    long[] nativeValues = nativeGetPerformanceSnapshot(nativeContext);
    return new FfmpegHigh10VideoPerformance(
        rendered,
        dropped,
        skipped,
        maxConsecutiveDropped,
        pendingInputCount,
        nativeValues == null ? new long[0] : nativeValues,
        pipelineProbe.snapshot());
  }

  void onInputQueued(DecoderInputBuffer inputBuffer) {
    if (pipelineProbe != null) pipelineProbe.inputQueued(inputBuffer);
  }

  void onFrameRendered(VideoDecoderOutputBuffer outputBuffer) {
    if (pipelineProbe != null) pipelineProbe.frameRendered(outputBuffer);
  }

  @Override
  protected FfmpegHigh10VideoDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegHigh10VideoDecoderException("Unexpected FFmpeg video error", error);
  }

  void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegHigh10VideoDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegHigh10VideoDecoderException("Invalid output mode.");
    }
    int result =
        nativeRenderFrame(
            nativeContext, surface, outputBuffer, outputBuffer.width, outputBuffer.height);
    if (result == OTHER_ERROR || result == SURFACE_ERROR) {
      throw new FfmpegHigh10VideoDecoderException("Surface render failed: " + result);
    }
  }

  @Override
  public synchronized void release() {
    super.release();
    nativeRelease(nativeContext);
    nativeContext = 0;
    pendingInputs.clear();
    pendingInputCount = 0;
  }

  @Nullable
  private static byte[] concatenate(java.util.List<byte[]> data) {
    int size = 0;
    for (byte[] item : data) {
      size += item.length;
    }
    if (size == 0) {
      return null;
    }
    ByteBuffer result = ByteBuffer.allocate(size);
    for (byte[] item : data) {
      result.put(item);
    }
    return result.array();
  }

  private native long nativeInitialize(
      @Nullable byte[] extraData, int threads, int rotationDegrees, int width, int height);

  private native long nativeReset(long context);

  private native void nativeRelease(long context);

  private native int nativeSendPacket(long context, ByteBuffer data, int length, long timeUs);

  private native int nativeReceiveFrame(
      long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

  private native void nativeReleaseWindow(long context);

  private native int nativeRenderFrame(
      long context,
      Surface surface,
      VideoDecoderOutputBuffer outputBuffer,
      int displayedWidth,
      int displayedHeight);

  @Nullable
  private native long[] nativeGetPerformanceSnapshot(long context);
}
