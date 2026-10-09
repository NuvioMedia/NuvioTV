/*
 * Video renderer architecture adapted from AndroidX Media PR #1591,
 * commit 1649087fbe3ce1b2c51abc320782be0b600b311b.
 */
package com.nuvio.hi10video;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;

import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.List;

/** Software renderer that intentionally claims only AVC High 10 Profile. */
@UnstableApi
public final class FfmpegHigh10VideoRenderer extends DecoderVideoRenderer {
  private static final String TAG = "FfmpegHigh10VideoRenderer";
  private static final int DEFAULT_INPUT_BUFFERS = 4;
  private static final int DEFAULT_OUTPUT_BUFFERS = 16;
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1920, 64) * Util.ceilDivide(1080, 64) * (64 * 64 * 3 / 2) / 2;
  @Nullable private static volatile FfmpegHigh10VideoRenderer activeRenderer;

  private final int threads;
  private final int inputBuffers;
  private final int outputBuffers;
  @Nullable private FfmpegHigh10VideoDecoder decoder;
  @Nullable private Format currentInputFormat;
  private boolean restoringSeekFormat;

  public FfmpegHigh10VideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        Math.max(Runtime.getRuntime().availableProcessors(), 1),
        DEFAULT_INPUT_BUFFERS,
        DEFAULT_OUTPUT_BUFFERS);
  }

  FfmpegHigh10VideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int inputBuffers,
      int outputBuffers) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = threads;
    this.inputBuffers = inputBuffers;
    this.outputBuffers = outputBuffers;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    boolean nativeAvailable = NuvioHi10VideoLibrary.hasH264Decoder();
    boolean shouldQueryHardware =
        MimeTypes.VIDEO_H264.equals(format.sampleMimeType)
            && AvcHigh10ProfileDetector.isHigh10(format)
            && nativeAvailable
            && format.cryptoType == C.CRYPTO_TYPE_NONE
            && format.rotationDegrees == 0;
    boolean hardwareAvailable = false;
    if (shouldQueryHardware) {
      try {
        hardwareAvailable = hasCompatibleHardwareDecoder(
            format, MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false));
      } catch (MediaCodecUtil.DecoderQueryException error) {
        Log.w(TAG, "Hardware query failed; isolated software decoder remains eligible", error);
      }
    }
    return supportsFormatForTest(
        format,
        nativeAvailable,
        hardwareAvailable);
  }

  static @RendererCapabilities.Capabilities int supportsFormatForTest(
      Format format, boolean nativeH264Available) {
    return supportsFormatForTest(format, nativeH264Available, false);
  }

  static @RendererCapabilities.Capabilities int supportsFormatForTest(
      Format format, boolean nativeH264Available, boolean hardwareHigh10Available) {
    if (!MimeTypes.VIDEO_H264.equals(format.sampleMimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (!AvcHigh10ProfileDetector.isHigh10(format)
        || !nativeH264Available
        || hardwareHigh10Available) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    if (format.rotationDegrees != 0) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  static boolean hasCompatibleHardwareDecoder(Format format, List<MediaCodecInfo> decoders)
      throws MediaCodecUtil.DecoderQueryException {
    for (MediaCodecInfo codec : decoders) {
      // Media3 supplies hardware classification on API24–28 as well as API29+.
      if (codec.hardwareAccelerated && !codec.softwareOnly && !codec.secure
          && High10MediaCodecVideoRenderer.isCompatible(format, codec)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegHigh10VideoDecoderException>
      createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
          throws FfmpegHigh10VideoDecoderException {
    if (cryptoConfig != null
        || RendererCapabilities.getFormatSupport(supportsFormatForTest(format, true)) != C.FORMAT_HANDLED) {
      throw new FfmpegHigh10VideoDecoderException("Unsupported software AVC High10 format.");
    }
    TraceUtil.beginSection("createFfmpegHigh10VideoDecoder");
    try {
      int initialInputBufferSize =
          format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
      decoder =
          new FfmpegHigh10VideoDecoder(
              inputBuffers, outputBuffers, initialInputBufferSize, threads, format);
      activeRenderer = this;
      return decoder;
    } finally {
      TraceUtil.endSection();
    }
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegHigh10VideoDecoderException {
    try {
      if (decoder == null) {
        throw new FfmpegHigh10VideoDecoderException("Decoder is not initialized.");
      }
      if (decoder.isCurrentOutput(outputBuffer)) {
        FfmpegHigh10VideoDecoder.SurfaceSubmission submission =
            decoder.renderToSurface(outputBuffer, surface);
        if (submission == FfmpegHigh10VideoDecoder.SurfaceSubmission.SUBMITTED) {
          decoder.onFrameRendered(outputBuffer);
        } else if (submission == FfmpegHigh10VideoDecoder.SurfaceSubmission.SURFACE_DROPPED) {
          decoder.onSurfaceDropped();
        }
      }
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected void onQueueInputBuffer(DecoderInputBuffer inputBuffer) {
    if (decoder != null) {
      decoder.onInputQueued(inputBuffer);
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected void onInputFormatChanged(FormatHolder formatHolder) throws ExoPlaybackException {
    super.onInputFormatChanged(formatHolder);
    currentInputFormat = formatHolder.format;
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    super.onPositionReset(positionUs, joining);
    if (currentInputFormat != null) {
      // Media3 1.8 clears formatQueue on seek without rearming its first-input marker.
      // Before the first output, that leaves outputFormat null. Register the unchanged
      // format for the next input through the protected API, without replacing decoder.
      FormatHolder holder = new FormatHolder();
      holder.format = currentInputFormat;
      restoringSeekFormat = true;
      try {
        super.onInputFormatChanged(holder);
      } finally {
        restoringSeekFormat = false;
      }
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    if (restoringSeekFormat && oldFormat.equals(newFormat)) {
      return new DecoderReuseEvaluation(
          decoderName, oldFormat, newFormat, REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0);
    }
    return new DecoderReuseEvaluation(
        decoderName, oldFormat, newFormat, REUSE_RESULT_NO, DISCARD_REASON_MIME_TYPE_CHANGED);
  }

  @Override
  protected void onDisabled() {
    currentInputFormat = null;
    DecoderCounters counters = decoderCounters;
    super.onDisabled();
    counters.ensureUpdated();
    Log.i(
        TAG,
        "counters rendered=" + counters.renderedOutputBufferCount
            + " dropped=" + counters.droppedBufferCount
            + " skipped=" + counters.skippedOutputBufferCount
            + " maxConsecutiveDropped=" + counters.maxConsecutiveDroppedBufferCount);
    if (activeRenderer == this) {
      activeRenderer = null;
    }
  }

  /** Returns authoritative live counters and native stage timings for debug telemetry. */
  @Nullable
  public static FfmpegHigh10VideoPerformance getPerformanceSnapshot() {
    if (!BuildConfig.DEBUG) return null;
    FfmpegHigh10VideoRenderer renderer = activeRenderer;
    FfmpegHigh10VideoDecoder activeDecoder = renderer == null ? null : renderer.decoder;
    if (renderer == null || activeDecoder == null) {
      return null;
    }
    DecoderCounters counters = renderer.decoderCounters;
    counters.ensureUpdated();
    return activeDecoder.getPerformanceSnapshot(
        counters.renderedOutputBufferCount,
        counters.droppedBufferCount,
        counters.skippedOutputBufferCount,
        counters.maxConsecutiveDroppedBufferCount);
  }
}
