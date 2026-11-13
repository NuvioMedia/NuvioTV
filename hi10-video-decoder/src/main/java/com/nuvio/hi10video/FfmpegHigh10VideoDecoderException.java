/* Adapted from AndroidX Media commit 1649087fbe3ce1b2c51abc320782be0b600b311b. */
package com.nuvio.hi10video;

import androidx.media3.decoder.DecoderException;

/** Error raised by the isolated FFmpeg High10 video decoder. */
public final class FfmpegHigh10VideoDecoderException extends DecoderException {
  FfmpegHigh10VideoDecoderException(String message) {
    super(message);
  }

  FfmpegHigh10VideoDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
