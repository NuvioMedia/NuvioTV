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

    /** [ingestRpu] results other than a mapping hash (hashes are always > 0). */
    const val RPU_UNREADABLE = -1L
    const val MAPPING_UNREADABLE = -2L
    /** An RPU of another profile: the result is OTHER_PROFILE_BASE - profile. */
    const val OTHER_PROFILE_BASE = -100L
    /** No native library: nothing was parsed. */
    const val NOT_PARSED = 0L

    /**
     * Parses one RPU NAL (from its 2-byte NAL header on). Returns the hash of its mapping (> 0)
     * when it is a usable Profile 5 RPU, else one of the result codes above.
     */
    fun ingestRpu(data: ByteArray, offset: Int, length: Int): Long {
        if (!isSupported) return NOT_PARSED
        return runCatching { nativeIngestRpu(data, offset, length) }.getOrDefault(RPU_UNREADABLE)
    }

    /** Fills [out] (size^3 * 4 half floats) with the LUT of mapping [hash]; false if unknown. */
    fun buildLut(hash: Long, size: Int, out: ShortArray): Boolean {
        if (!isSupported || hash == 0L) return false
        return runCatching { nativeBuildLut(hash, size, out) }
            .onFailure { Log.w(TAG, "LUT build failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Marks the buffers of [surface] as HDR10 (BT.2020 PQ), for output where EGL has no BT.2020 PQ
     * colour space. False when the system can't (before Android 9) or the library isn't loaded.
     */
    fun setHdr10Dataspace(surface: android.view.Surface): Boolean {
        if (!DoviBridge.isAvailable()) return false
        return runCatching { nativeSetHdr10Dataspace(surface) }
            .onFailure { Log.w(TAG, "Setting the HDR10 dataspace failed: ${it.message}") }
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
    private external fun nativeSetHdr10Dataspace(surface: android.view.Surface): Boolean

    @JvmStatic
    private external fun nativeReset()
}
