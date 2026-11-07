/* Adapted from AndroidX Media commit 1649087fbe3ce1b2c51abc320782be0b600b311b. */
package com.nuvio.hi10video;

import androidx.media3.common.C;
import androidx.media3.common.util.LibraryLoader;

/** Loads and queries the isolated native video library. */
final class NuvioHi10VideoLibrary {
  private static final LibraryLoader LOADER =
      new LibraryLoader("nuvioHi10Video") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };

  private static String version;
  private static int paddingSize = C.LENGTH_UNSET;

  private NuvioHi10VideoLibrary() {}

  static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  static boolean hasH264Decoder() {
    return isAvailable() && nativeHasH264Decoder();
  }

  static String getVersion() {
    if (!isAvailable()) {
      return null;
    }
    if (version == null) {
      version = nativeGetVersion();
    }
    return version;
  }

  static int getInputBufferPaddingSize() {
    if (!isAvailable()) {
      return C.LENGTH_UNSET;
    }
    if (paddingSize == C.LENGTH_UNSET) {
      paddingSize = nativeGetInputBufferPaddingSize();
    }
    return paddingSize;
  }

  private static native String nativeGetVersion();

  private static native int nativeGetInputBufferPaddingSize();

  private static native boolean nativeHasH264Decoder();
}
