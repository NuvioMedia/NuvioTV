/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This modified decoder module is distributed with NuvioTV under GPL-3.0-only.
 * It is based on AndroidX Media3 decoder_ffmpeg; the original Apache-2.0 notice
 * is preserved below. See this module's NOTICE.md for provenance details.
 */
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

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Objects;

/**
 * Decodes and renders video using FFmpeg.
 *
 * <p>Only the video decoders compiled into the native library are claimed (VC-1 and H.264 in the
 * NuvioTV build), and H.264 only in its high bit depth profiles such as Hi10P (see {@link
 * FfmpegVideoFormats}), so the platform {@code MediaCodec} renderer keeps handling everything else.
 * {@link androidx.media3.exoplayer.DefaultRenderersFactory} loads this class by name when the
 * extension renderer mode is on.
 */
@UnstableApi
public final class ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "ExperimentalFfmpegVideoRenderer";

  private static final int DEFAULT_NUM_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OUTPUT_BUFFERS = 4;

  /**
   * The default input buffer size. A 1080p VC-1 or H.264 frame rarely exceeds this; the buffer grows
   * if one does.
   */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 1024 * 1024;

  @Nullable private FfmpegVideoDecoder decoder;

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
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public final @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    @Nullable String mimeType = format.sampleMimeType;
    if (mimeType == null || !MimeTypes.isVideo(mimeType) || !FfmpegLibrary.isAvailable()) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    // Every video track is offered here; most (HEVC, VP9, AV1) are not in the FFmpeg build.
    @Nullable String codecName = FfmpegLibrary.getCodecName(mimeType);
    if (codecName == null || !FfmpegLibrary.hasDecoder(codecName)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    // 8-bit 4:2:0 H.264 belongs to the hardware decoder. Not claiming it at all keeps this renderer
    // from taking it even when it is ordered first (extension renderer mode "prefer").
    if (!FfmpegVideoFormats.isSoftwareDecodeCandidate(format)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    FfmpegVideoDecoder decoder =
        new FfmpegVideoDecoder(
            DEFAULT_NUM_INPUT_BUFFERS,
            DEFAULT_NUM_OUTPUT_BUFFERS,
            initialInputBufferSize,
            /* threads= */ 0,
            format);
    this.decoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    @Nullable FfmpegVideoDecoder decoder = this.decoder;
    if (decoder == null) {
      throw new FfmpegDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    decoder.renderToSurface(outputBuffer, surface);
    outputBuffer.release();
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    @Nullable FfmpegVideoDecoder decoder = this.decoder;
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    if (!Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)) {
      return new DecoderReuseEvaluation(
          decoderName, oldFormat, newFormat, REUSE_RESULT_NO, DISCARD_REASON_MIME_TYPE_CHANGED);
    }
    if (!oldFormat.initializationDataEquals(newFormat)) {
      // The sequence header lives in the extradata the decoder was opened with.
      return new DecoderReuseEvaluation(
          decoderName,
          oldFormat,
          newFormat,
          REUSE_RESULT_NO,
          DISCARD_REASON_INITIALIZATION_DATA_CHANGED);
    }
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
        /* discardReasons= */ 0);
  }
}
