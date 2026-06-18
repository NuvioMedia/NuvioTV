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

  @Nullable
  public static Allocation createAllocation(int size) {
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

  public static void freeAllocation(Allocation allocation) {
    final long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0) {
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
