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

/**
 * One payload extension entry from an Extended Stream Properties Object.
 *
 * <p>Type tag is the first byte of the extension GUID (FFmpeg: {@code ASFPayload.type = g[0]}).
 * Known types: {@code 0x54} = SAR, {@code 0x2A} = DVR-MS timestamps.
 */
final class PayloadExtension {

  static final byte TYPE_SAR = 0x54;
  static final byte TYPE_PTS = 0x2A;
  static final int VARIABLE_SIZE = 0xFFFF; // fixedSize sentinel: size is per-payload LE16

  final byte type;
  final int fixedSize; // bytes of extension data per payload, or VARIABLE_SIZE

  PayloadExtension(byte type, int fixedSize) {
    this.type = type;
    this.fixedSize = fixedSize;
  }
}
