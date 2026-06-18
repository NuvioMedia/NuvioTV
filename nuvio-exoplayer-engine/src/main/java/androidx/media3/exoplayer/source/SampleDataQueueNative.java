package androidx.media3.exoplayer.source;

import java.nio.ByteBuffer;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

public final class SampleDataQueueNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;

  public static boolean copyFromArray(
      byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
    if (length == 0) {
      return true;
    }
    if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.length) {
      return false;
    }
    if (targetOffset < 0 || targetOffset + length > target.capacity()) {
      return false;
    }
    if (!isAvailable()) {
      return false;
    }
    try {
      return nativeCopyFromArray(source, sourceOffset, target, targetOffset, length);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return false;
    }
  }

  public static boolean copyToArray(
      ByteBuffer source, int sourceOffset, byte[] target, int targetOffset, int length) {
    if (length == 0) {
      return true;
    }
    if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.capacity()) {
      return false;
    }
    if (targetOffset < 0 || targetOffset + length > target.length) {
      return false;
    }
    if (!isAvailable()) {
      return false;
    }
    try {
      return nativeCopyToArray(source, sourceOffset, target, targetOffset, length);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return false;
    }
  }

  public static boolean copyBetweenDirectBuffers(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
    if (length == 0) {
      return true;
    }
    if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.capacity()) {
      return false;
    }
    if (targetOffset < 0 || targetOffset + length > target.capacity()) {
      return false;
    }
    if (!isAvailable()) {
      return false;
    }
    try {
      return nativeCopyBetweenDirectBuffers(source, sourceOffset, target, targetOffset, length);
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return false;
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
  private static native boolean nativeCopyFromArray(
      byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

  @FastNative
  private static native boolean nativeCopyToArray(
      ByteBuffer source, int sourceOffset, byte[] target, int targetOffset, int length);

  @FastNative
  private static native boolean nativeCopyBetweenDirectBuffers(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

  @CriticalNative
  public static native void nativeCopyAddresses(
      long sourceAddr, int sourceOffset, long targetAddr, int targetOffset, int length);

  private static final java.lang.reflect.Field ADDRESS_FIELD;
  static {
    java.lang.reflect.Field field = null;
    try {
      field = java.nio.Buffer.class.getDeclaredField("address");
      field.setAccessible(true);
    } catch (Exception e) {}
    ADDRESS_FIELD = field;
  }

  public static long getDirectBufferAddress(ByteBuffer buffer) {
    if (ADDRESS_FIELD == null) return 0L;
    try {
      return ADDRESS_FIELD.getLong(buffer);
    } catch (Exception e) {
      return 0L;
    }
  }

  private static final ThreadLocal<BufferAddressCache> targetCache = new ThreadLocal<BufferAddressCache>() {
    @Override
    protected BufferAddressCache initialValue() {
      return new BufferAddressCache();
    }
  };

  private static final class BufferAddressCache {
    private java.lang.ref.WeakReference<ByteBuffer> lastBufferRef = new java.lang.ref.WeakReference<>(null);
    long lastAddress;
  }

  public static long getDirectBufferAddressCached(ByteBuffer buffer) {
    if (buffer == null) return 0L;
    BufferAddressCache cache = targetCache.get();
    ByteBuffer lastBuffer = cache.lastBufferRef.get();
    if (lastBuffer == buffer) {
      return cache.lastAddress;
    }
    long address = getDirectBufferAddress(buffer);
    cache.lastBufferRef = new java.lang.ref.WeakReference<>(buffer);
    cache.lastAddress = address;
    return address;
  }

  public static boolean copyBetweenAddressesDirect(
      long sourceAddr, int sourceOffset, long targetAddr, int targetOffset, int length) {
    if (sourceAddr == 0L || targetAddr == 0L) {
      return false;
    }
    if (!isAvailable()) {
      return false;
    }
    try {
      nativeCopyAddresses(sourceAddr, sourceOffset, targetAddr, targetOffset, length);
      return true;
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return false;
    }
  }

  public static boolean copyBetweenAddresses(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
    long sourceAddr = getDirectBufferAddressCached(source);
    long targetAddr = getDirectBufferAddressCached(target);
    if (sourceAddr == 0L || targetAddr == 0L) {
      return false;
    }
    if (!isAvailable()) {
      return false;
    }
    try {
      nativeCopyAddresses(sourceAddr, sourceOffset, targetAddr, targetOffset, length);
      return true;
    } catch (UnsatisfiedLinkError e) {
      isAvailable = false;
      return false;
    }
  }

  private SampleDataQueueNative() {}
}
