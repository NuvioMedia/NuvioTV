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

import androidx.media3.common.C;
import java.util.Arrays;

/** Accumulates fragments of a single ASF media object spread across multiple packets. */
final class PendingSample {

  byte[] data;
  int bytesWritten;
  long timeUs;
  @C.BufferFlags int flags;
  int fragOffset; // next expected byte offset within the media object (FFmpeg: frag_offset)

  PendingSample(int capacity, long timeUs, @C.BufferFlags int flags) {
    this.data = new byte[Math.max(capacity, 1024)];
    this.timeUs = timeUs;
    this.flags = flags;
  }

  /** Returns false if objectOffset doesn't match the expected position (out-of-order fragment). */
  boolean appendAt(byte[] src, int objectOffset, int length) {
    if (objectOffset != fragOffset) {
      return false;
    }
    int needed = objectOffset + length;
    if (needed > data.length) {
      data = Arrays.copyOf(data, Math.max(needed, data.length * 2));
    }
    System.arraycopy(src, 0, data, objectOffset, length);
    bytesWritten = objectOffset + length;
    fragOffset = bytesWritten;
    return true;
  }
}
