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

/** Decoded content of an ASF replicated data block in a payload header. */
final class ReplicatedData {

  static final long ABSENT = -1L; // field not present / unreadable

  final long objSize; // declared media-object size in bytes, or ABSENT
  final long presentationMs; // presentation timestamp in ms, or ABSENT
  final boolean tsIsPts; // true when presentationMs is from DVR-MS ext (0x2A); preroll excluded
  final long
      timeDelta; // compressed multi-payload (repLen==1): per-sub-payload delta in ms; else ABSENT

  ReplicatedData(long objSize, long presentationMs, boolean tsIsPts, long timeDelta) {
    this.objSize = objSize;
    this.presentationMs = presentationMs;
    this.tsIsPts = tsIsPts;
    this.timeDelta = timeDelta;
  }

  static ReplicatedData absent() {
    return new ReplicatedData(ABSENT, ABSENT, false, ABSENT);
  }

  static ReplicatedData compressed(int timeDelta) {
    return new ReplicatedData(ABSENT, ABSENT, false, timeDelta);
  }
}
