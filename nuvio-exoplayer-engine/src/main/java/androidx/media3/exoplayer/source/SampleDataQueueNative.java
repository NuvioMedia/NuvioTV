package androidx.media3.exoplayer.source;

import java.nio.ByteBuffer;

final class SampleDataQueueNative {

  private static final String LIBRARY_NAME = "media3_exoplayer_jni";

  private static volatile boolean loadAttempted;
  private static volatile boolean isAvailable;

  public static boolean copyFromArray(
      byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
    if (length == 0) {
      return true;
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

  private static native boolean nativeCopyFromArray(
      byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

  private static native boolean nativeCopyToArray(
      ByteBuffer source, int sourceOffset, byte[] target, int targetOffset, int length);

  private static native boolean nativeCopyBetweenDirectBuffers(
      ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

  private SampleDataQueueNative() {}
}
