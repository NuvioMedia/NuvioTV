package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

public final class DefaultAllocatorNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;

  private static final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NuvioNativeAllocatorDeallocator");
        thread.setDaemon(true);
        return thread;
      });

  private static final int ARENA_CHUNK_SIZE = 65536;
  private static final int ARENA_MAX_CHUNKS = 512;

  private static final java.util.Queue<Allocation> arenaPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private static volatile long arenaBaseAddress = 0L;
  private static volatile long arenaEndAddress = 0L;
  private static Allocation arenaBaseAllocation = null;
  private static volatile boolean arenaInitialized = false;

  private static synchronized void initializeArena() {
    if (arenaInitialized) {
      return;
    }
    arenaInitialized = true;
    if (!isAvailable()) {
      return;
    }
    int totalSize = ARENA_MAX_CHUNKS * ARENA_CHUNK_SIZE;
    try {
      arenaBaseAllocation = nativeCreateAllocation(totalSize);
      if (arenaBaseAllocation == null || arenaBaseAllocation.buffer == null) {
        return;
      }
      arenaBaseAddress = arenaBaseAllocation.nativeHandle;
      arenaEndAddress = arenaBaseAddress + totalSize;

      java.nio.ByteBuffer baseBuffer = arenaBaseAllocation.buffer;
      for (int i = 0; i < ARENA_MAX_CHUNKS; i++) {
        int offset = i * ARENA_CHUNK_SIZE;

        // Slice the base buffer for this chunk
        java.nio.ByteBuffer chunkBuffer = baseBuffer.duplicate();
        chunkBuffer.position(offset);
        chunkBuffer.limit(offset + ARENA_CHUNK_SIZE);
        java.nio.ByteBuffer sliced = chunkBuffer.slice();

        long chunkAddress = arenaBaseAddress + offset;
        Allocation chunkAllocation = new Allocation(sliced, 0, chunkAddress);
        arenaPool.offer(chunkAllocation);
      }
    } catch (Exception | UnsatisfiedLinkError e) {
      // Fallback in case of failures
    }
  }

  @Nullable
  private static Allocation createAllocationDirect(int size) {
    if (!isAvailable()) {
      return null;
    }
    try {
      return nativeCreateAllocation(size);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return null;
    }
  }

  @Nullable
  public static Allocation createAllocation(int size) {
    if (size == ARENA_CHUNK_SIZE) {
      if (!arenaInitialized) {
        initializeArena();
      }
      if (arenaBaseAddress != 0L) {
        Allocation allocation = arenaPool.poll();
        if (allocation != null) {
          return allocation;
        }
      }
    }
    return createAllocationDirect(size);
  }

  public static void freeAllocation(Allocation allocation) {
    final long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0) {
      return;
    }
    // Check if the allocation belongs to the pre-allocated Arena pool
    if (nativeHandle >= arenaBaseAddress && nativeHandle < arenaEndAddress) {
      allocation.nativeHandle = 0; // Clear handle immediately
      // Offer a fresh wrapper back to the pool to prevent reference leaks
      Allocation recAllocation = new Allocation(allocation.buffer, allocation.offset, nativeHandle);
      arenaPool.offer(recAllocation);
      return;
    }

    allocation.nativeHandle = 0; // Clear immediately to prevent double-free queueing
    try {
      // Defer the actual native free by 2 seconds to allow any active loader/playback threads
      // to safely exit and stop accessing the direct ByteBuffer.
      scheduler.schedule(() -> {
        try {
          nativeFreeAllocation(nativeHandle);
        } catch (UnsatisfiedLinkError e) {
          isAvailable = false;
        }
      }, 2000, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // Fallback to immediate free if scheduler is shut down
      try {
        nativeFreeAllocation(nativeHandle);
      } catch (UnsatisfiedLinkError e2) {
        isAvailable = false;
      }
    }
  }

  private static boolean isAvailable() {
    if (loadAttempted) {
      return isAvailable;
    }
    return loadLibrary();
  }

  private static synchronized boolean loadLibrary() {
    if (loadAttempted) {
      return isAvailable;
    }
    loadAttempted = true;
    try {
      System.loadLibrary(LIBRARY_NAME);
      isAvailable = true;
    } catch (SecurityException | UnsatisfiedLinkError e) {
      isAvailable = false;
    }
    return isAvailable;
  }

  @FastNative
  private static native Allocation nativeCreateAllocation(int size);

  @CriticalNative
  private static native void nativeFreeAllocation(long handle);

  private DefaultAllocatorNative() {}
}
