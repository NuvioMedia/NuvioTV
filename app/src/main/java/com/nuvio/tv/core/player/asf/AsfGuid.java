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

import androidx.media3.common.util.ParsableByteArray;

/** ASF object GUIDs used by this package, plus GUID read/match helpers. */
final class AsfGuid {

  static final byte[] HEADER_OBJECT = {
    0x30,
    0x26,
    (byte) 0xB2,
    0x75,
    (byte) 0x8E,
    0x66,
    (byte) 0xCF,
    0x11,
    (byte) 0xA6,
    (byte) 0xD9,
    0x00,
    (byte) 0xAA,
    0x00,
    0x62,
    (byte) 0xCE,
    0x6C
  };

  static final byte[] DATA_OBJECT = {
    0x36,
    0x26,
    (byte) 0xB2,
    0x75,
    (byte) 0x8E,
    0x66,
    (byte) 0xCF,
    0x11,
    (byte) 0xA6,
    (byte) 0xD9,
    0x00,
    (byte) 0xAA,
    0x00,
    0x62,
    (byte) 0xCE,
    0x6C
  };

  static final byte[] FILE_PROPERTIES = {
    (byte) 0xA1,
    (byte) 0xDC,
    (byte) 0xAB,
    (byte) 0x8C,
    0x47,
    (byte) 0xA9,
    (byte) 0xCF,
    0x11,
    (byte) 0x8E,
    (byte) 0xE4,
    0x00,
    (byte) 0xC0,
    0x0C,
    0x20,
    0x53,
    0x65
  };

  static final byte[] STREAM_PROPERTIES = {
    (byte) 0x91,
    0x07,
    (byte) 0xDC,
    (byte) 0xB7,
    (byte) 0xB7,
    (byte) 0xA9,
    (byte) 0xCF,
    0x11,
    (byte) 0x8E,
    (byte) 0xE6,
    0x00,
    (byte) 0xC0,
    0x0C,
    0x20,
    0x53,
    0x65
  };

  static final byte[] EXT_STREAM_PROPERTIES = {
    (byte) 0xCB,
    (byte) 0xA5,
    (byte) 0xE6,
    0x14,
    0x72,
    (byte) 0xC6,
    0x32,
    0x43,
    (byte) 0x83,
    (byte) 0x99,
    (byte) 0xA9,
    0x69,
    0x52,
    0x06,
    0x5B,
    0x5A
  };

  static final byte[] HEADER_EXTENSION = {
    (byte) 0xB5,
    0x03,
    (byte) 0xBF,
    0x5F,
    0x2E,
    (byte) 0xA9,
    (byte) 0xCF,
    0x11,
    (byte) 0x8E,
    (byte) 0xE3,
    0x00,
    (byte) 0xC0,
    0x0C,
    0x20,
    0x53,
    0x65
  };

  static final byte[] STREAM_TYPE_AUDIO = {
    0x40,
    (byte) 0x9E,
    0x69,
    (byte) 0xF8,
    0x4D,
    0x5B,
    (byte) 0xCF,
    0x11,
    (byte) 0xA8,
    (byte) 0xFD,
    0x00,
    (byte) 0x80,
    0x5F,
    0x5C,
    0x44,
    0x2B
  };

  static final byte[] STREAM_TYPE_VIDEO = {
    (byte) 0xC0,
    (byte) 0xEF,
    0x19,
    (byte) 0xBC,
    0x4D,
    0x5B,
    (byte) 0xCF,
    0x11,
    (byte) 0xA8,
    (byte) 0xFD,
    0x00,
    (byte) 0x80,
    0x5F,
    0x5C,
    0x44,
    0x2B
  };

  /**
   * Returns {@code true} when the next 16 bytes of {@code buf} match {@code guid}. Consumes the 16
   * bytes regardless of match result.
   */
  static boolean matches(ParsableByteArray buf, byte[] guid) {
    if (buf.bytesLeft() < 16) {
      return false;
    }
    for (int i = 0; i < 16; i++) {
      if (buf.readUnsignedByte() != (guid[i] & 0xFF)) {
        buf.skipBytes(15 - i);
        return false;
      }
    }
    return true;
  }

  /** Reads 16 bytes from {@code buf} and returns them as a new array. */
  static byte[] read(ParsableByteArray buf) {
    byte[] result = new byte[16];
    buf.readBytes(result, 0, 16);
    return result;
  }
}
