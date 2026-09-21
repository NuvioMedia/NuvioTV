/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SEAMLESS;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_FALLBACK;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_PRIMARY;
import static androidx.media3.exoplayer.RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.TUNNELING_NOT_SUPPORTED;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Locale;
import java.util.Objects;

/**
 * Decodes and renders video using FFmpeg.
 *
 * <p>This class name is loaded by reflection from {@code DefaultRenderersFactory} in the ExoPlayer
 * AAR. Keep the constructor signature stable.
 */
@UnstableApi
public final class ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "FfmpegVideoRenderer";
  private static final int DEFAULT_THREAD_COUNT = 0;
  private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 8;
  private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 8;
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;
  /** Software VC-1's first I-frame is slow; don't shed/drop during this window. */
  private static final long STARTUP_GRACE_US = 1_500_000;
  /** Only drop a startup frame if it is more than 400ms late. */
  private static final long STARTUP_DROP_THRESHOLD_US = -400_000;
  /** Don't flush to a keyframe during startup unless we are more than 1.2s behind. */
  private static final long STARTUP_KEYFRAME_DROP_THRESHOLD_US = -1_200_000;
  /**
   * ExoPlayer drops any frame later than 30ms. VC-1 software plus the GL upload is often a frame
   * or two behind on this box; showing those frames is smoother than skipping them.
   */
  private static final long LATE_FRAME_DROP_THRESHOLD_US = -150_000;

  private final int threads;
  private final int numInputBuffers;
  private final int numOutputBuffers;
  private final FfmpegVideoRecoveryController recoveryController;

  @Nullable private FfmpegVideoDecoder decoder;
  @FfmpegVideoDecoder.DecodeLoadLevel private int decodeLoadLevel;
  private long startupGraceDeadlineUs;

  /**
   * Creates a new instance.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public ExperimentalFfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = DEFAULT_THREAD_COUNT;
    this.numInputBuffers = DEFAULT_NUM_OF_INPUT_BUFFERS;
    this.numOutputBuffers = DEFAULT_NUM_OF_OUTPUT_BUFFERS;
    recoveryController = new FfmpegVideoRecoveryController();
    startupGraceDeadlineUs = C.TIME_UNSET;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public int supportsFormat(Format format) {
    String mimeType = format.sampleMimeType;
    if (!FfmpegLibrary.isAvailable() || mimeType == null || !MimeTypes.isVideo(mimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    if (FfmpegLibrary.getCodecName(mimeType) == null || !FfmpegLibrary.supportsFormat(mimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    @DecoderSupport
    int decoderSupport =
        isPrimarySoftwareMime(mimeType) ? DECODER_SUPPORT_PRIMARY : DECODER_SUPPORT_FALLBACK;
    return RendererCapabilities.create(
        C.FORMAT_HANDLED,
        ADAPTIVE_NOT_SEAMLESS,
        TUNNELING_NOT_SUPPORTED,
        HARDWARE_ACCELERATION_NOT_SUPPORTED,
        decoderSupport);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    try {
      int initialInputBufferSize =
          format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
      FfmpegVideoDecoder decoder =
          new FfmpegVideoDecoder(
              numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, format);
      decoder.setDecodeLoadLevel(decodeLoadLevel);
      this.decoder = decoder;
      return decoder;
    } finally {
      TraceUtil.endSection();
    }
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (decoder == null) {
      throw new FfmpegDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    decoder.renderToSurface(outputBuffer, surface);
    outputBuffer.release();
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected void onStarted() {
    beginStartupGrace();
    super.onStarted();
  }

  @Override
  protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
    boolean startupGrace = inStartupGrace(elapsedRealtimeUs);
    updateDecoderLoadLevel(earlyUs, elapsedRealtimeUs, startupGrace);
    if (startupGrace) {
      return earlyUs < STARTUP_DROP_THRESHOLD_US;
    }
    return earlyUs < LATE_FRAME_DROP_THRESHOLD_US;
  }

  @Override
  protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
    boolean startupGrace = inStartupGrace(elapsedRealtimeUs);
    updateDecoderLoadLevel(earlyUs, elapsedRealtimeUs, startupGrace);
    if (startupGrace) {
      return earlyUs < STARTUP_KEYFRAME_DROP_THRESHOLD_US;
    }
    boolean veryLate = super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs);
    if (recoveryController.shouldRequestKeyframeResync(veryLate)) {
      return true;
    }
    return veryLate;
  }

  @Override
  protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedSinceLastRenderUs) {
    if (inStartupGrace(SystemClock.elapsedRealtime() * 1000L)) {
      return true;
    }
    return super.shouldForceRenderOutputBuffer(earlyUs, elapsedSinceLastRenderUs);
  }

  @Override
  protected boolean maybeDropBuffersToKeyframe(long positionUs) throws ExoPlaybackException {
    if (inStartupGrace(SystemClock.elapsedRealtime() * 1000L)) {
      return false;
    }
    recoveryController.onDecoderFlushed();
    setDecoderLoadLevel(FfmpegVideoDecoder.DECODE_LOAD_NORMAL);
    return super.maybeDropBuffersToKeyframe(positionUs);
  }

  @Override
  protected void flushDecoder() throws ExoPlaybackException {
    recoveryController.onDecoderFlushed();
    setDecoderLoadLevel(FfmpegVideoDecoder.DECODE_LOAD_NORMAL);
    super.flushDecoder();
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
    beginStartupGrace();
    recoveryController.reset();
    setDecoderLoadLevel(FfmpegVideoDecoder.DECODE_LOAD_NORMAL);
    super.onPositionReset(positionUs, joining);
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    int discardReasons = 0;
    if (!Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)) {
      discardReasons |= DISCARD_REASON_MIME_TYPE_CHANGED;
    }
    if (!oldFormat.initializationDataEquals(newFormat)) {
      discardReasons |= DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
    }
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        discardReasons == 0 ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : REUSE_RESULT_NO,
        discardReasons);
  }

  @Override
  protected void releaseDecoder() {
    try {
      super.releaseDecoder();
    } finally {
      decoder = null;
      recoveryController.reset();
      decodeLoadLevel = FfmpegVideoDecoder.DECODE_LOAD_NORMAL;
    }
  }

  private void beginStartupGrace() {
    startupGraceDeadlineUs = SystemClock.elapsedRealtime() * 1000L + STARTUP_GRACE_US;
  }

  private boolean inStartupGrace(long elapsedRealtimeUs) {
    return startupGraceDeadlineUs != C.TIME_UNSET && elapsedRealtimeUs < startupGraceDeadlineUs;
  }

  private void updateDecoderLoadLevel(
      long earlyUs, long elapsedRealtimeUs, boolean startupGrace) {
    setDecoderLoadLevel(
        recoveryController.updateDecodeLoadLevel(earlyUs, elapsedRealtimeUs, startupGrace));
  }

  private void setDecoderLoadLevel(@FfmpegVideoDecoder.DecodeLoadLevel int decodeLoadLevel) {
    if (this.decodeLoadLevel == decodeLoadLevel) {
      return;
    }
    this.decodeLoadLevel = decodeLoadLevel;
    if (decoder != null) {
      decoder.setDecodeLoadLevel(decodeLoadLevel);
    }
  }

  private static boolean isPrimarySoftwareMime(String mimeType) {
    String mime = mimeType.toLowerCase(Locale.US);
    return MimeTypes.VIDEO_VC1.equals(mime)
        || "video/wvc1".equals(mime)
        || "video/vc1".equals(mime)
        || "video/x-ms-wmv".equals(mime)
        || "video/wmv".equals(mime)
        || "video/x-ms-wmv3".equals(mime)
        || "video/x-ms-wmv1".equals(mime)
        || "video/x-ms-wmv2".equals(mime);
  }
}
