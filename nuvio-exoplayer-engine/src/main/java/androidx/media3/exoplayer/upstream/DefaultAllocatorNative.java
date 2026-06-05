package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;

final class DefaultAllocatorNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;

  // ─── posix_memalign allocation ───────────────────────────────────────────────

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
    long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0) {
      return;
    }
    try {
      nativeFreeAllocation(nativeHandle);
      allocation.nativeHandle = 0;
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
    }
  }

  // ─── AHardwareBuffer allocation ──────────────────────────────────────────────

  @Nullable
  public static Allocation createHardwareBufferAllocation(int size) {
    if (!isAvailable()) {
      return null;
    }
    try {
      return nativeCreateHwBufferAllocation(size);
    } catch (UnsatisfiedLinkError e) {
      // Don't set isAvailable = false — posix_memalign path may still work
      return null;
    }
  }

  public static void freeHardwareBufferAllocation(Allocation allocation) {
    long nativeHandle = allocation.nativeHandle;
    if (nativeHandle == 0) {
      return;
    }
    try {
      nativeFreeHwBufferAllocation(nativeHandle);
      allocation.nativeHandle = 0;
    } catch (UnsatisfiedLinkError e) {
      // Best effort
    }
  }

  /** Returns {@code true} if AHardwareBuffer is available at the native level (API 26+). */
  public static boolean isHardwareBufferAvailable() {
    if (!isAvailable()) {
      return false;
    }
    try {
      return nativeIsHwBufferAvailable();
    } catch (UnsatisfiedLinkError e) {
      return false;
    }
  }

  // ─── Library loading ─────────────────────────────────────────────────────────

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

  // ─── Native method declarations ──────────────────────────────────────────────

  private static native Allocation nativeCreateAllocation(int size);

  private static native void nativeFreeAllocation(long handle);

  private static native Allocation nativeCreateHwBufferAllocation(int size);

  private static native void nativeFreeHwBufferAllocation(long handle);

  private static native boolean nativeIsHwBufferAvailable();

  private DefaultAllocatorNative() {}
}
