/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;

final class FfmpegHigh10VideoDecoder implements Decoder<
    DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException> {
  private static final String TAG = "NuvioHi10";
  private static final int OTHER_ERROR = -2;
  private static final int SURFACE_ERROR = -4;
  private final Object renderLock = new Object();
  private final High10DecoderLifecycle lifecycle;
  @Nullable private final High10PipelineProbe pipelineProbe = BuildConfig.DEBUG
      ? new High10PipelineProbe(SystemClock::elapsedRealtimeNanos) : null;
  private long nativeContext;
  @C.VideoOutputMode private volatile int outputMode;

  FfmpegHigh10VideoDecoder(int inputBuffers, int outputBuffers, int initialInputBufferSize,
      int threads, Format format) throws FfmpegHigh10VideoDecoderException {
    if (!NuvioHi10VideoLibrary.hasH264Decoder()) {
      throw new FfmpegHigh10VideoDecoderException("Isolated FFmpeg H.264 decoder unavailable.");
    }
    int inputPadding = NuvioHi10VideoLibrary.getInputBufferPaddingSize();
    nativeContext = nativeInitialize(concatenate(format.initializationData), threads,
        format.rotationDegrees, format.width, format.height);
    if (nativeContext == 0) {
      throw new FfmpegHigh10VideoDecoderException("Failed to initialize FFmpeg H.264 decoder.");
    }
    lifecycle = new High10DecoderLifecycle(new NativeBackend(format), inputBuffers, outputBuffers,
        initialInputBufferSize, inputPadding,
        VideoDecoderOutputBuffer::new, new High10DecoderLifecycle.Observer() {
          @Override public void inputStarted(DecoderInputBuffer input) {
            if (pipelineProbe != null && !input.isEndOfStream()) pipelineProbe.decodeStarted(input);
          }
          @Override public void frameDecoded(VideoDecoderOutputBuffer output) {
            if (pipelineProbe != null) pipelineProbe.frameDecoded(output);
          }
          @Override public void outputReleased(VideoDecoderOutputBuffer output) {
            if (pipelineProbe != null) pipelineProbe.frameReleased(output);
          }
          @Override public void flushed() {
            if (pipelineProbe != null) pipelineProbe.reset();
          }
        });
    Log.i(TAG, "renderer=FfmpegHigh10VideoRenderer decoder=FFmpeg h264 profile=High10 "
        + "conversion=yuv420p10le->yuv420p output=ANativeWindow");
  }

  void setOutputMode(@C.VideoOutputMode int outputMode) {
    synchronized (renderLock) {
      // A SurfaceHolder can reuse its Java Surface for a new native generation.
      if (nativeContext != 0) nativeReleaseWindow(nativeContext);
      this.outputMode = outputMode;
    }
  }

  @Override public String getName() {
    return "nuvio-hi10-ffmpeg-" + NuvioHi10VideoLibrary.getVersion();
  }
  @Override public void setOutputStartTimeUs(long timeUs) { lifecycle.setOutputStartTimeUs(timeUs); }
  @Override @Nullable public DecoderInputBuffer dequeueInputBuffer()
      throws FfmpegHigh10VideoDecoderException { return lifecycle.dequeueInputBuffer(); }
  @Override public void queueInputBuffer(DecoderInputBuffer input)
      throws FfmpegHigh10VideoDecoderException {
    lifecycle.queueInputBuffer(input);
    if (pipelineProbe != null) pipelineProbe.pendingDepth(lifecycle.pendingInputCount());
  }
  @Override @Nullable public VideoDecoderOutputBuffer dequeueOutputBuffer()
      throws FfmpegHigh10VideoDecoderException { return lifecycle.dequeueOutputBuffer(); }
  @Override public void flush() { lifecycle.flush(); }
  @Override public void release() { lifecycle.release(); }
  boolean isCurrentOutput(VideoDecoderOutputBuffer output) { return lifecycle.isCurrentOutput(output); }

  FfmpegHigh10VideoPerformance getPerformanceSnapshot(
      int rendered, int dropped, int skipped, int maxConsecutiveDropped) {
    if (pipelineProbe == null) {
      throw new IllegalStateException("Playback telemetry is available only in Debug builds.");
    }
    long[] nativeValues;
    synchronized (renderLock) {
      nativeValues = nativeContext == 0 ? null : nativeGetPerformanceSnapshot(nativeContext);
    }
    return new FfmpegHigh10VideoPerformance(rendered, dropped, skipped, maxConsecutiveDropped,
        lifecycle.pendingInputCount(), nativeValues == null ? new long[0] : nativeValues,
        pipelineProbe.snapshot());
  }

  void onInputQueued(DecoderInputBuffer input) {
    if (pipelineProbe != null) pipelineProbe.inputQueued(input);
  }
  void onFrameRendered(VideoDecoderOutputBuffer output) {
    if (pipelineProbe != null) pipelineProbe.frameRendered(output);
  }

  void renderToSurface(VideoDecoderOutputBuffer output, Surface surface)
      throws FfmpegHigh10VideoDecoderException {
    synchronized (renderLock) {
      if (nativeContext == 0 || output.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
        throw new FfmpegHigh10VideoDecoderException("Invalid decoder context or output mode.");
      }
      int result = nativeRenderFrame(nativeContext, surface, output, output.width, output.height);
      if (result == OTHER_ERROR || result == SURFACE_ERROR) {
        throw new FfmpegHigh10VideoDecoderException("Surface render failed: " + result);
      }
    }
  }

  private final class NativeBackend implements High10DecodeBackend {
    private final Format format;
    NativeBackend(Format format) { this.format = format; }
    @Override public int send(DecoderInputBuffer input) throws FfmpegHigh10VideoDecoderException {
      if (input.data == null || input.isEncrypted()) {
        throw new FfmpegHigh10VideoDecoderException("Missing or encrypted High10 packet.");
      }
      return nativeSendPacket(nativeContext, input.data, input.data.limit(), input.timeUs);
    }
    @Override public int beginDrain() { return nativeBeginDrain(nativeContext); }
    @Override public int receive(VideoDecoderOutputBuffer output) {
      output.init(C.TIME_UNSET, outputMode, null);
      int result = nativeReceiveFrame(nativeContext, outputMode, output, false);
      if (result == ACCEPTED) output.format = format;
      return result;
    }
    @Override public void flush() throws FfmpegHigh10VideoDecoderException {
      if (nativeReset(nativeContext) == 0) {
        throw new FfmpegHigh10VideoDecoderException("FFmpeg flush failed.");
      }
    }
    @Override public void release() {
      synchronized (renderLock) {
        nativeRelease(nativeContext);
        nativeContext = 0;
      }
    }
  }

  @Nullable private static byte[] concatenate(java.util.List<byte[]> data) {
    int size = 0;
    for (byte[] item : data) size += item.length;
    if (size == 0) return null;
    ByteBuffer result = ByteBuffer.allocate(size);
    for (byte[] item : data) result.put(item);
    return result.array();
  }

  private native long nativeInitialize(
      @Nullable byte[] extraData, int threads, int rotationDegrees, int width, int height);
  private native long nativeReset(long context);
  private native void nativeRelease(long context);
  private native int nativeSendPacket(long context, ByteBuffer data, int length, long timeUs);
  private native int nativeBeginDrain(long context);
  private native int nativeReceiveFrame(
      long context, int outputMode, VideoDecoderOutputBuffer output, boolean decodeOnly);
  private native void nativeReleaseWindow(long context);
  private native int nativeRenderFrame(long context, Surface surface,
      VideoDecoderOutputBuffer output, int displayedWidth, int displayedHeight);
  @Nullable private native long[] nativeGetPerformanceSnapshot(long context);
}
