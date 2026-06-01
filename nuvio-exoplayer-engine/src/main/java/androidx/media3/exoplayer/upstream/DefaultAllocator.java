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

import static java.lang.Math.max;

import androidx.annotation.Nullable;
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Arrays;

/** Default implementation of {@link Allocator}. */
@UnstableApi
public final class DefaultAllocator implements Allocator {

  private static final int AVAILABLE_EXTRA_CAPACITY = 100;

  private final boolean trimOnReset;
  private final int individualAllocationSize;
  @Nullable private final byte[] initialAllocationBlock;

  private int targetBufferSize;
  private int allocatedCount;
  private int availableCount;
  private @NullableType Allocation[] availableAllocations;

  /**
   * Constructs an instance without creating any {@link Allocation}s up front.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances. If set to false, trimming can
   *     be forced by calling {@link #setTargetBufferSize(int)} manually when required.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   */
  public DefaultAllocator(boolean trimOnReset, int individualAllocationSize) {
    this(trimOnReset, individualAllocationSize, 0);
  }

  /**
   * Constructs an instance with some {@link Allocation}s created up front.
   *
   * <p>Note: {@link Allocation}s created up front will never be discarded by {@link #trim()}.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances. If set to false, trimming can
   *     be forced by calling {@link #setTargetBufferSize(int)} manually when required.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   * @param initialAllocationCount The number of allocations to create up front.
   */
  public DefaultAllocator(
      boolean trimOnReset, int individualAllocationSize, int initialAllocationCount) {
    Assertions.checkArgument(individualAllocationSize > 0);
    Assertions.checkArgument(initialAllocationCount >= 0);
    this.trimOnReset = trimOnReset;
    this.individualAllocationSize = individualAllocationSize;
    this.availableCount = initialAllocationCount;
    this.availableAllocations = new Allocation[initialAllocationCount + AVAILABLE_EXTRA_CAPACITY];
    if (initialAllocationCount > 0) {
      initialAllocationBlock = null;
      for (int i = 0; i < initialAllocationCount; i++) {
        availableAllocations[i] = createAllocation(individualAllocationSize);
      }
    } else {
      initialAllocationBlock = null;
    }
  }

  public synchronized void reset() {
    if (trimOnReset) {
      setTargetBufferSize(0);
    }
  }

  public synchronized void setTargetBufferSize(int targetBufferSize) {
    boolean targetBufferSizeReduced = targetBufferSize < this.targetBufferSize;
    this.targetBufferSize = targetBufferSize;
    if (targetBufferSizeReduced) {
      trim();
    }
  }

  @Override
  public synchronized Allocation allocate() {
    allocatedCount++;
    Allocation allocation;
    if (availableCount > 0) {
      allocation = Assertions.checkNotNull(availableAllocations[--availableCount]);
      availableAllocations[availableCount] = null;
    } else {
      allocation = createAllocation(individualAllocationSize);
      if (allocatedCount > availableAllocations.length) {
        // Make availableAllocations be large enough to contain all allocations made by this
        // allocator so that release() does not need to grow the availableAllocations array. See
        // [Internal ref: b/209801945].
        availableAllocations = Arrays.copyOf(availableAllocations, availableAllocations.length * 2);
      }
    }
    return allocation;
  }

  @Override
  public synchronized void release(Allocation allocation) {
    availableAllocations[availableCount++] = allocation;
    allocatedCount--;
    if (NuvioEngineConfig.get().isNativeAllocationEnabled() && shouldTrim()) {
      trim();
    }
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

  @Override
  public synchronized void release(@Nullable AllocationNode allocationNode) {
    while (allocationNode != null) {
      availableAllocations[availableCount++] = allocationNode.getAllocation();
      allocatedCount--;
      allocationNode = allocationNode.next();
    }
    if (NuvioEngineConfig.get().isNativeAllocationEnabled() && shouldTrim()) {
      trim();
    }
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

  @Override
  public synchronized void trim() {
    int targetAllocationCount = Util.ceilDivide(targetBufferSize, individualAllocationSize);
    int targetAvailableCount = max(0, targetAllocationCount - allocatedCount);
    if (targetAvailableCount >= availableCount) {
      // We're already at or below the target.
      return;
    }

    if (initialAllocationBlock != null) {
      // Some allocations are backed by an initial block. We need to make sure that we hold onto all
      // such allocations. Re-order the available allocations so that the ones backed by the initial
      // block come first.
      int lowIndex = 0;
      int highIndex = availableCount - 1;
      while (lowIndex <= highIndex) {
        Allocation lowAllocation = Assertions.checkNotNull(availableAllocations[lowIndex]);
        if (lowAllocation.data == initialAllocationBlock) {
          lowIndex++;
        } else {
          Allocation highAllocation = Assertions.checkNotNull(availableAllocations[highIndex]);
          if (highAllocation.data != initialAllocationBlock) {
            highIndex--;
          } else {
            availableAllocations[lowIndex++] = highAllocation;
            availableAllocations[highIndex--] = lowAllocation;
          }
        }
      }
      // lowIndex is the index of the first allocation not backed by an initial block.
      targetAvailableCount = max(targetAvailableCount, lowIndex);
      if (targetAvailableCount >= availableCount) {
        // We're already at or below the target.
        return;
      }
    }

    // Discard allocations beyond the target.
    for (int i = targetAvailableCount; i < availableCount; i++) {
      Allocation allocation = availableAllocations[i];
      if (allocation != null && allocation.buffer != null) {
        freeAllocation(allocation);
      }
    }
    Arrays.fill(availableAllocations, targetAvailableCount, availableCount, null);
    availableCount = targetAvailableCount;
  }

  @Override
  public synchronized int getTotalBytesAllocated() {
    return allocatedCount * individualAllocationSize;
  }

  public synchronized int getAvailableBytes() {
    return availableCount * individualAllocationSize;
  }

  public synchronized int getMemoryFootprint() {
    return (allocatedCount + availableCount) * individualAllocationSize;
  }

  @Override
  public int getIndividualAllocationLength() {
    return individualAllocationSize;
  }

  private static Allocation createAllocation(int size) {
    if (!NuvioEngineConfig.get().isNativeAllocationEnabled()) {
      return new Allocation(new byte[size], 0);
    }
    @Nullable Allocation allocation = DefaultAllocatorNative.createAllocation(size);
    return allocation != null
        ? allocation
        : new Allocation(java.nio.ByteBuffer.allocateDirect(size), 0);
  }

  private static void freeAllocation(Allocation allocation) {
    if (allocation.nativeHandle != 0) {
      DefaultAllocatorNative.freeAllocation(allocation);
    }
  }

  private boolean shouldTrim() {
    int targetAllocationCount = Util.ceilDivide(targetBufferSize, individualAllocationSize);
    int targetAvailableCount = max(0, targetAllocationCount - allocatedCount);
    return availableCount > targetAvailableCount;
  }

}
