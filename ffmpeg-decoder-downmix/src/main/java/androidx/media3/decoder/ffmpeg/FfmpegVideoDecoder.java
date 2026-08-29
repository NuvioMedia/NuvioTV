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

import static com.google.common.base.Preconditions.checkNotNull;

import android.view.Surface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

/** FFmpeg video decoder. */
/* package */ final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;

  private static final byte[] VC1_START_CODE = {0x00, 0x00, 0x01, 0x0F};

  // LINT.IfChange(decodeLoadLevel)
  /** Decode every frame with normal in-loop filtering. */
  static final int DECODE_LOAD_NORMAL = 0;

  /** Discard non-reference frames before decoding. */
  static final int DECODE_LOAD_NON_REFERENCE = 1;

  /** Also omit in-loop filtering on bidirectional frames while severely late. */
  static final int DECODE_LOAD_AGGRESSIVE = 2;

  /** Decoder load-shedding level selected by {@link FfmpegVideoRecoveryController}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({DECODE_LOAD_NORMAL, DECODE_LOAD_NON_REFERENCE, DECODE_LOAD_AGGRESSIVE})
  @interface DecodeLoadLevel {}

  // LINT.ThenChange(../../../../../jni/ffmpeg_jni.cc:decodeLoadLevel)

  private final String codecName;
  @Nullable private final byte[] extraData;
  private final Format format;

  private long nativeContext;
  private volatile @C.VideoOutputMode int outputMode;
  @DecodeLoadLevel private volatile int decodeLoadLevel;

  FfmpegVideoDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      Format format)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    this.format = format;
    codecName = checkNotNull(FfmpegLibrary.getCodecName(checkNotNull(format.sampleMimeType)));
    extraData = getExtraData(format);
    nativeContext = ffmpegInitialize(codecName, extraData, threads);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(
        buffer -> {
          long frame = buffer.decoderPrivate;
          if (frame != 0) {
            buffer.decoderPrivate = 0;
            ffmpegReleaseFrame(frame);
          }
          releaseOutputBuffer(buffer);
        });
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
      nativeContext = ffmpegReset(nativeContext);
      if (nativeContext == 0) {
        releasePrivateFrame(outputBuffer);
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
    }
    if (!isAtLeastOutputStartTimeUs(inputBuffer.timeUs) && !inputBuffer.isEndOfStream()) {
      releasePrivateFrame(outputBuffer);
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
    ByteBuffer inputData = inputBuffer.isEndOfStream() ? null : Util.castNonNull(inputBuffer.data);
    int inputSize = inputData == null ? 0 : inputData.limit();
    int result = ffmpegDecode(nativeContext, inputData, inputSize, outputBuffer, outputMode);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      releasePrivateFrame(outputBuffer);
      return new FfmpegDecoderException("Error decoding (see logcat).");
    }
    if (result == VIDEO_DECODER_ERROR_INVALID_DATA || result == 0) {
      releasePrivateFrame(outputBuffer);
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    outputBuffer.format = format;
    return null;
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  void setDecodeLoadLevel(@DecodeLoadLevel int decodeLoadLevel) {
    this.decodeLoadLevel = decodeLoadLevel;
    long context = nativeContext;
    if (context != 0) {
      ffmpegSetDecodeLoadLevel(context, decodeLoadLevel);
    }
  }

  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegDecoderException("Invalid output mode.");
    }
    int result = ffmpegRenderFrame(nativeContext, surface, outputBuffer);
    if (result != 0) {
      throw new FfmpegDecoderException("Buffer render error: " + result);
    }
  }

  private static void releasePrivateFrame(VideoDecoderOutputBuffer outputBuffer) {
    long frame = outputBuffer.decoderPrivate;
    if (frame != 0) {
      outputBuffer.decoderPrivate = 0;
      ffmpegReleaseFrame(frame);
    }
  }

  @Nullable
  private static byte[] getExtraData(Format format) {
    List<byte[]> initializationData = format.initializationData;
    byte[] extra = null;
    if (initializationData != null && !initializationData.isEmpty()) {
      extra = initializationData.get(0);
      if (initializationData.size() > 1) {
        int total = 0;
        for (int i = 0; i < initializationData.size(); i++) {
          total += initializationData.get(i).length;
        }
        extra = new byte[total];
        int offset = 0;
        for (int i = 0; i < initializationData.size(); i++) {
          byte[] part = initializationData.get(i);
          System.arraycopy(part, 0, extra, offset, part.length);
          offset += part.length;
        }
      }
    }
    if (!isVc1OrWmv(format.sampleMimeType)) {
      return extra;
    }
    if (extra == null || extra.length == 0) {
      return VC1_START_CODE;
    }
    if (startsWithVc1StartCode(extra)) {
      return extra;
    }
    byte[] out = new byte[VC1_START_CODE.length + extra.length];
    System.arraycopy(VC1_START_CODE, 0, out, 0, VC1_START_CODE.length);
    System.arraycopy(extra, 0, out, VC1_START_CODE.length, extra.length);
    return out;
  }

  private static boolean startsWithVc1StartCode(byte[] data) {
    return data.length >= 4
        && data[0] == 0x00
        && data[1] == 0x00
        && data[2] == 0x01
        && data[3] == 0x0F;
  }

  private static boolean isVc1OrWmv(@Nullable String mimeType) {
    if (mimeType == null) {
      return false;
    }
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

  private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads);

  private native int ffmpegDecode(
      long context,
      @Nullable ByteBuffer encoded,
      int length,
      VideoDecoderOutputBuffer out,
      int outputMode);

  private native long ffmpegReset(long context);

  private native void ffmpegRelease(long context);

  private native void ffmpegSetDecodeLoadLevel(long context, int level);

  private static native void ffmpegReleaseFrame(long frame);

  private native int ffmpegRenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer buffer);
}
