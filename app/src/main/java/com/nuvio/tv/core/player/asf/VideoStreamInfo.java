/*
 * Copyright 2024 The Android Open Source Project
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
package com.nuvio.tv.core.player.asf;

import androidx.annotation.Nullable;

/**
 * Immutable descriptor for one ASF video stream, parsed from a Stream Properties Object.
 *
 * <p>SAR may change mid-stream via a {@link PayloadExtension#TYPE_SAR} extension; the packet reader
 * maintains a SAR table rather than mutating this class.
 */
final class VideoStreamInfo {

  final int streamNumber;
  final int width;
  final int height;
  final int fourcc; // FOURCC compression tag (e.g. WVC1)
  final int bitsPerSample; // biBitCount from BITMAPINFOHEADER
  @Nullable final byte[] codecPrivate;
  final float frameRate; // fps from AvgFrameTime (100-ns units); -1 if unknown
  final int averageBitrate; // bps from DataBitrate; Format.NO_VALUE if unknown

  VideoStreamInfo(
      int streamNumber,
      int width,
      int height,
      int fourcc,
      int bitsPerSample,
      @Nullable byte[] codecPrivate,
      float frameRate,
      int averageBitrate) {
    this.streamNumber = streamNumber;
    this.width = width;
    this.height = height;
    this.fourcc = fourcc;
    this.bitsPerSample = bitsPerSample;
    this.codecPrivate = codecPrivate;
    this.frameRate = frameRate;
    this.averageBitrate = averageBitrate;
  }
}
