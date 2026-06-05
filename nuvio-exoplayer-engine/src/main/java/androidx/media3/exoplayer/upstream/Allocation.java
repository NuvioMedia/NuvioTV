/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/**
 * An allocation within a byte array.
 *
 * <p>The allocation's length is obtained by calling {@link
 * Allocator#getIndividualAllocationLength()} on the {@link Allocator} from which it was obtained.
 */
@UnstableApi
public final class Allocation {

  /**
   * The array containing the allocated space. The allocated space might not be at the start of the
   * array, and so {@link #offset} must be used when indexing into it.
   */
  @Nullable public final byte[] data;

  /**
   * Off-heap direct ByteBuffer containing the allocated space.
   */
  @Nullable public final java.nio.ByteBuffer buffer;

  /** The offset of the allocated space in {@link #data}. */
  public final int offset;

  long nativeHandle;

  /** Whether this allocation is backed by an AHardwareBuffer (gralloc). */
  final boolean isHardwareBuffer;

  /**
   * @param data The array containing the allocated space.
   * @param offset The offset of the allocated space in {@code data}.
   */
  public Allocation(@Nullable byte[] data, int offset) {
    this.data = data;
    this.buffer = null;
    this.offset = offset;
    this.nativeHandle = 0;
    this.isHardwareBuffer = false;
  }

  /**
   * @param buffer The off-heap buffer containing the allocated space.
   * @param offset The offset of the allocated space.
   */
  public Allocation(@Nullable java.nio.ByteBuffer buffer, int offset) {
    this.data = null;
    this.buffer = buffer;
    this.offset = offset;
    this.nativeHandle = 0;
    this.isHardwareBuffer = false;
  }

  Allocation(@Nullable java.nio.ByteBuffer buffer, int offset, long nativeHandle) {
    this.data = null;
    this.buffer = buffer;
    this.offset = offset;
    this.nativeHandle = nativeHandle;
    this.isHardwareBuffer = false;
  }

  /**
   * Constructor for AHardwareBuffer-backed allocations (called from JNI).
   *
   * @param buffer The off-heap buffer mapped from the HardwareBuffer's CPU address.
   * @param offset The offset of the allocated space.
   * @param nativeHandle Handle to the native HwBufferAllocation struct.
   * @param isHardwareBuffer Must be {@code true} — distinguishes HwBuffer from posix_memalign.
   */
  Allocation(@Nullable java.nio.ByteBuffer buffer, int offset, long nativeHandle,
             boolean isHardwareBuffer) {
    this.data = null;
    this.buffer = buffer;
    this.offset = offset;
    this.nativeHandle = nativeHandle;
    this.isHardwareBuffer = isHardwareBuffer;
  }
}

