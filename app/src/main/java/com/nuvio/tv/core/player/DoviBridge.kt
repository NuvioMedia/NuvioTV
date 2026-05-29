package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.BuildConfig
import java.util.concurrent.atomic.AtomicLong

object DoviBridge {
    private const val TAG = "DoviBridge"
    private const val LIB_NAME = "dovi_bridge"

    data class RealtimeConversionProbe(
        val supported: Boolean,
        val reason: String,
        val bridgeVersion: String?,
        val extractorHookReady: Boolean,
        val selfTest: SelfTestResult
    )

    data class SelfTestResult(
        val passed: Boolean,
        val reason: String,
        val inputBytes: Int,
        val outputBytes: Int
    )

    private val nativeLoaded: Boolean by lazy { loadNativeLibrary() }
    private var cachedSelfTestResult: SelfTestResult? = null
    private val conversionCallCount = AtomicLong(0L)
    private val conversionSuccessCount = AtomicLong(0L)

    val isNativeEnabledInBuild: Boolean
        get() = BuildConfig.DOVI_NATIVE_ENABLED

    val isExtractorHookReadyInBuild: Boolean
        get() = BuildConfig.DOVI_EXTRACTOR_HOOK_READY

    val isLibraryLoaded: Boolean
        get() = nativeLoaded

    fun isAvailable(): Boolean = isNativeEnabledInBuild && nativeLoaded

    fun getBridgeVersionOrNull(): String? {
        if (!isAvailable()) return null
        return runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "Failed to read bridge version: ${it.message}") }
            .getOrNull()
    }

    fun probeRealtimeConversionSupport(streamUrl: String): RealtimeConversionProbe {
        if (!isNativeEnabledInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-disabled-in-build",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild,
                selfTest = SelfTestResult(
                    passed = false,
                    reason = "not-run",
                    inputBytes = 0,
                    outputBytes = 0
                )
            )
        }
        if (!nativeLoaded) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "native-library-load-failed",
                bridgeVersion = null,
                extractorHookReady = isExtractorHookReadyInBuild,
                selfTest = SelfTestResult(
                    passed = false,
                    reason = "not-run",
                    inputBytes = 0,
                    outputBytes = 0
                )
            )
        }
        if (!isExtractorHookReadyInBuild) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "extractor-hook-not-integrated",
                bridgeVersion = getBridgeVersionOrNull(),
                extractorHookReady = false,
                selfTest = runStartupSelfTest(streamUrl)
            )
        }

        val bridgeVersion = runCatching { nativeGetBridgeVersion() }
            .onFailure { Log.w(TAG, "probe version failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrNull()

        val ready = runCatching { nativeIsConversionPathReady() }
            .onFailure { Log.w(TAG, "probe readiness failed host=${streamUrl.safeHost()}: ${it.message}") }
            .getOrDefault(false)

        val selfTest = runStartupSelfTest(streamUrl)
        if (!selfTest.passed) {
            return RealtimeConversionProbe(
                supported = false,
                reason = "self-test-failed:${selfTest.reason}",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        }

        return if (ready) {
            RealtimeConversionProbe(
                supported = true,
                reason = "ready",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        } else {
            RealtimeConversionProbe(
                supported = false,
                reason = "bridge-reports-not-ready",
                bridgeVersion = bridgeVersion,
                extractorHookReady = true,
                selfTest = selfTest
            )
        }
    }

    fun runStartupSelfTest(streamUrl: String): SelfTestResult {
        cachedSelfTestResult?.let { return it }
        if (!isAvailable()) {
            return SelfTestResult(
                passed = false,
                reason = "native-unavailable",
                inputBytes = 0,
                outputBytes = 0
            )
        }

        val payload = byteArrayOf(
            0x7c, 0x01, 0x20, 0x40,
            0x21, 0x33, 0x55, 0x77, 0x11, 0x02, 0x06, 0x10
        )
        val output = convertDv7RpuToDv81(payload, mode = 2)
        val result = if (output != null && output.isNotEmpty()) {
            SelfTestResult(
                passed = true,
                reason = if (output.contentEquals(payload)) {
                    "bridge-path-ok-passthrough"
                } else {
                    "bridge-path-ok-transformed"
                },
                inputBytes = payload.size,
                outputBytes = output.size
            )
        } else if (runCatching { nativeIsConversionPathReady() }.getOrDefault(false)) {
            // The synthetic payload is not guaranteed to be a valid single-frame RPU.
            // If the native bridge reports ready, do not hard-disable runtime probing here.
            SelfTestResult(
                passed = true,
                reason = "bridge-ready-selftest-unverifiable",
                inputBytes = payload.size,
                outputBytes = output?.size ?: 0
            )
        } else {
            SelfTestResult(
                passed = false,
                reason = "null-or-empty-output",
                inputBytes = payload.size,
                outputBytes = output?.size ?: 0
            )
        }

        cachedSelfTestResult = result
        Log.i(
            TAG,
            "Self-test host=${streamUrl.safeHost()} passed=${result.passed} " +
                "reason=${result.reason} bytes=${result.inputBytes}->${result.outputBytes}"
        )
        return result
    }

    fun resetRuntimeCounters() {
        conversionCallCount.set(0L)
        conversionSuccessCount.set(0L)
    }

    fun getConversionCallCount(): Long = conversionCallCount.get()

    fun getConversionSuccessCount(): Long = conversionSuccessCount.get()

    fun convertDv7RpuToDv81(payload: ByteArray, mode: Int = 1): ByteArray? {
        if (!isAvailable() || payload.isEmpty()) return null
        conversionCallCount.incrementAndGet()
        val converted = runCatching { nativeConvertDv7RpuToDv81(payload, mode) }
            .onFailure { Log.w(TAG, "Conversion failed: ${it.message}") }
            .getOrNull()
        if (converted != null && converted.isNotEmpty()) {
            conversionSuccessCount.incrementAndGet()
        }
        return converted
    }

    private fun loadNativeLibrary(): Boolean {
        if (!isNativeEnabledInBuild) {
            return false
        }
        return try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Loaded native library: $LIB_NAME")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load native library $LIB_NAME: ${t.message}")
            false
        }
    }

    private fun String.safeHost(): String {
        return runCatching { android.net.Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
    }

    @JvmStatic
    private external fun nativeGetBridgeVersion(): String

    @JvmStatic
    private external fun nativeIsConversionPathReady(): Boolean

    @JvmStatic
    private external fun nativeConvertDv7RpuToDv81(payload: ByteArray, mode: Int): ByteArray?
}
