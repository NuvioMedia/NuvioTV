package com.nuvio.tv.core.player

import android.util.Log

/**
 * JNI entry points of the Dolby Vision Profile 5 -> HDR10 mapping (app/src/main/cpp/dovi_p5_*.cpp),
 * which live in the same native library as [DoviBridge].
 */
internal object DolbyVisionP5Native {
    private const val TAG = "DolbyVisionP5Native"

    /** True when the native library is loaded and linked against libdovi. */
    val isSupported: Boolean by lazy {
        DoviBridge.isAvailable() && runCatching { nativeIsSupported() }
            .onFailure { Log.w(TAG, "Support check failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Parses one RPU NAL (from its 2-byte NAL header on) and returns the hash of its mapping, or 0
     * when it is not a usable Profile 5 RPU.
     */
    fun ingestRpu(data: ByteArray, offset: Int, length: Int): Long {
        if (!isSupported) return 0L
        return runCatching { nativeIngestRpu(data, offset, length) }.getOrDefault(0L)
    }

    /** Fills [out] (size^3 * 4 half floats) with the LUT of mapping [hash]; false if unknown. */
    fun buildLut(hash: Long, size: Int, out: ShortArray): Boolean {
        if (!isSupported || hash == 0L) return false
        return runCatching { nativeBuildLut(hash, size, out) }
            .onFailure { Log.w(TAG, "LUT build failed: ${it.message}") }
            .getOrDefault(false)
    }

    fun reset() {
        if (!isSupported) return
        runCatching { nativeReset() }
    }

    @JvmStatic
    private external fun nativeIsSupported(): Boolean

    @JvmStatic
    private external fun nativeIngestRpu(data: ByteArray, offset: Int, length: Int): Long

    @JvmStatic
    private external fun nativeBuildLut(hash: Long, size: Int, out: ShortArray): Boolean

    @JvmStatic
    private external fun nativeReset()
}
