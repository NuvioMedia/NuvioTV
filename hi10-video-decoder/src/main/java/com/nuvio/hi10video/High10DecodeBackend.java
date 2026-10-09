/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

/** Native decode boundary. Successful receive must assign decoded frame PTS and Format. */
interface High10DecodeBackend {
  int ACCEPTED = 0;
  int INVALID_DATA = -1;
  int ERROR = -2;
  int AGAIN = -3;
  int EOF = -5;

  int send(DecoderInputBuffer input) throws FfmpegHigh10VideoDecoderException;
  int beginDrain() throws FfmpegHigh10VideoDecoderException;
  int receive(VideoDecoderOutputBuffer output) throws FfmpegHigh10VideoDecoderException;
  void flush() throws FfmpegHigh10VideoDecoderException;
  void release();
}
