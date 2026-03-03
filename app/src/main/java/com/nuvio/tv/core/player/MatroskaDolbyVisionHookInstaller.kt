package com.nuvio.tv.core.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.DolbyVisionCompatibility
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.DolbyVisionConfig
import androidx.media3.extractor.DefaultExtractorsFactory
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object MatroskaDolbyVisionHookInstaller {
    private const val TAG = "Dv7ExtractorHook"
    private const val MATROSKA_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mkv.MatroskaExtractor\$DolbyVisionSampleTransformer"
    private const val MP4_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mp4.Mp4Extractor\$DolbyVisionSampleTransformer"
    private const val FMP4_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.mp4.FragmentedMp4Extractor\$DolbyVisionSampleTransformer"
    private const val TS_H265_TRANSFORMER_CLASS_NAME =
        "androidx.media3.extractor.ts.H265Reader\$DolbyVisionNalTransformer"
    private const val MATROSKA_SETTER_NAME = "setMatroskaDolbyVisionSampleTransformer"
    private const val MP4_SETTER_NAME = "setMp4DolbyVisionSampleTransformer"
    private const val FMP4_SETTER_NAME = "setFragmentedMp4DolbyVisionSampleTransformer"
    private const val TS_H265_SETTER_NAME = "setTsDolbyVisionNalTransformer"
    private const val NAL_TYPE_UNSPEC62 = 62

    fun maybeInstall(
        extractorsFactory: DefaultExtractorsFactory,
        enabled: Boolean,
        streamUrl: String
    ): Boolean {
        if (!enabled) {
            clearGlobalHooks()
            return false
        }
        if (!DoviBridge.isAvailable()) {
            clearGlobalHooks()
            Log.i(TAG, "Skip install: DoviBridge unavailable host=${streamUrl.safeHost()}")
            return false
        }

        return runCatching {
            val handler = createInvocationHandler(streamUrl.safeHost())
            val matroskaInstalled = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = MATROSKA_TRANSFORMER_CLASS_NAME,
                setterName = MATROSKA_SETTER_NAME,
                invocationHandler = handler
            )
            val mp4Installed = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = MP4_TRANSFORMER_CLASS_NAME,
                setterName = MP4_SETTER_NAME,
                invocationHandler = handler
            )
            val fmp4Installed = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = FMP4_TRANSFORMER_CLASS_NAME,
                setterName = FMP4_SETTER_NAME,
                invocationHandler = handler
            )
            val tsInstalled = installHook(
                extractorsFactory = extractorsFactory,
                transformerClassName = TS_H265_TRANSFORMER_CLASS_NAME,
                setterName = TS_H265_SETTER_NAME,
                invocationHandler = handler
            )
            matroskaInstalled || mp4Installed || fmp4Installed || tsInstalled
        }.onFailure {
            Log.w(TAG, "Failed to install hook host=${streamUrl.safeHost()}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installHook(
        extractorsFactory: DefaultExtractorsFactory,
        transformerClassName: String,
        setterName: String,
        invocationHandler: InvocationHandler
    ): Boolean {
        return runCatching {
            val transformerClass = Class.forName(transformerClassName)
            val setter = extractorsFactory.javaClass.getMethod(setterName, transformerClass)
            val proxy = Proxy.newProxyInstance(
                transformerClass.classLoader,
                arrayOf(transformerClass),
                invocationHandler
            )
            setter.invoke(extractorsFactory, proxy)
            when (setterName) {
                MATROSKA_SETTER_NAME -> DolbyVisionCompatibility.setMatroskaDolbyVisionSampleTransformer(proxy)
                MP4_SETTER_NAME -> DolbyVisionCompatibility.setMp4DolbyVisionSampleTransformer(proxy)
                FMP4_SETTER_NAME -> DolbyVisionCompatibility.setFragmentedMp4DolbyVisionSampleTransformer(proxy)
                TS_H265_SETTER_NAME -> DolbyVisionCompatibility.setTsDolbyVisionNalTransformer(proxy)
            }
            true
        }.getOrDefault(false)
    }

    private fun clearGlobalHooks() {
        DolbyVisionCompatibility.setMatroskaDolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setMp4DolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setFragmentedMp4DolbyVisionSampleTransformer(null)
        DolbyVisionCompatibility.setTsDolbyVisionNalTransformer(null)
    }

    private fun createInvocationHandler(host: String): InvocationHandler {
        val lastDetectedProfile = AtomicReference<Int?>(null)
        val nonDv7ProfileLogged = AtomicBoolean(false)

        fun rememberProfile(profile: Int?): Int? {
            if (profile != null) {
                lastDetectedProfile.set(profile)
            }
            return profile ?: lastDetectedProfile.get()
        }

        fun shouldAllowConversion(profile: Int?): Boolean {
            val resolvedProfile = rememberProfile(profile)
            if (resolvedProfile == 7) {
                return true
            }
            if (resolvedProfile != null && nonDv7ProfileLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Skipping experimental DV conversion for non-DV7 profile=$resolvedProfile host=$host"
                )
            }
            return false
        }

        return InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onDolbyVisionBlockAdditionalData" -> {
                    val blockAdditionalData = args?.getOrNull(0) as? ByteArray ?: return@InvocationHandler null
                    val dolbyVisionConfigBytes = args.getOrNull(2) as? ByteArray
                    if (!shouldAllowConversion(resolveDolbyVisionProfile(configBytes = dolbyVisionConfigBytes))) {
                        return@InvocationHandler null
                    }
                    DoviBridge.convertDv7RpuToDv81(blockAdditionalData, mode = 2)
                        ?.takeIf { it.isNotEmpty() }
                }

                "onHevcSample" -> null
                "onDolbyVisionCodecString" -> {
                    val codecs = args?.getOrNull(0) as? String
                    val dolbyVisionConfigBytes = args?.getOrNull(1) as? ByteArray
                    val profile = resolveDolbyVisionProfile(codecs = codecs, configBytes = dolbyVisionConfigBytes)
                    if (!shouldAllowConversion(profile)) {
                        return@InvocationHandler null
                    }
                    normalizeDolbyVisionCodecString(codecs)
                }
                "transformHevcSample" -> {
                    val sampleLengthDelimited = args?.getOrNull(0) as? ByteArray ?: return@InvocationHandler null
                    val nalUnitLengthFieldLength =
                        (args.getOrNull(1) as? Number)?.toInt() ?: return@InvocationHandler null
                    val thirdArg = args.getOrNull(2)
                    val blockAdditionalData = thirdArg as? ByteArray
                    val codecs = thirdArg as? String
                    val dolbyVisionConfigBytes = args.getOrNull(3) as? ByteArray
                    if (!shouldAllowConversion(
                            resolveDolbyVisionProfile(
                                codecs = codecs,
                                configBytes = dolbyVisionConfigBytes
                            )
                        )
                    ) {
                        return@InvocationHandler null
                    }
                    val rewrittenSample =
                        rewriteMp4HevcSample(sampleLengthDelimited, nalUnitLengthFieldLength)
                            ?: sampleLengthDelimited
                    if (blockAdditionalData == null) {
                        if (rewrittenSample !== sampleLengthDelimited) rewrittenSample else null
                    } else {
                        val convertedBlockAdditional =
                            DoviBridge.convertDv7RpuToDv81(blockAdditionalData, mode = 2)
                                ?.takeIf { it.isNotEmpty() }
                                ?: blockAdditionalData
                        appendLengthDelimitedNal(
                            sampleLengthDelimited = rewrittenSample,
                            nalUnitLengthFieldLength = nalUnitLengthFieldLength,
                            nalPayload = convertedBlockAdditional
                        )
                    }
                }
                "transformDolbyVisionRpuNal" -> {
                    val nalPayload = args?.getOrNull(0) as? ByteArray ?: return@InvocationHandler null
                    val codecs = args?.getOrNull(1) as? String
                    if (!shouldAllowConversion(resolveDolbyVisionProfile(codecs = codecs))) {
                        return@InvocationHandler null
                    }
                    maybeConvertDolbyVisionRpuNal(nalPayload)
                }
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NuvioDv7MatroskaTransformerProxy(host=$host)"
                else -> null
            }
        }
    }

    private fun rewriteMp4HevcSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4) return null
        var offset = 0
        var changed = false
        val out = ByteArrayOutputStream(sampleLengthDelimited.size + 128)
        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            if (nalSize < 0) return null
            offset += nalUnitLengthFieldLength
            if (offset + nalSize > sampleLengthDelimited.size) return null
            val originalNal = sampleLengthDelimited.copyOfRange(offset, offset + nalSize)
            val convertedNal = transformNalForCompatibility(originalNal)
            if (convertedNal == null) {
                changed = true
                offset += nalSize
                continue
            }
            if (convertedNal !== originalNal) {
                changed = true
            }
            if (!writeLengthField(out, convertedNal.size, nalUnitLengthFieldLength)) {
                return null
            }
            out.write(convertedNal)
            offset += nalSize
        }
        if (offset != sampleLengthDelimited.size) return null
        if (!changed) return null
        if (out.size() <= 0) return null
        return out.toByteArray()
    }

    private fun transformNalForCompatibility(nalPayload: ByteArray): ByteArray? {
        if (nalPayload.isEmpty()) return nalPayload
        val nalType = getNalUnitType(nalPayload)
        val layerId = getNuhLayerId(nalPayload)
        // Drop enhancement-layer NAL units; they are not decodable on single-layer HEVC paths.
        if (layerId > 0 && nalType != NAL_TYPE_UNSPEC62) {
            return null
        }
        if (nalType != NAL_TYPE_UNSPEC62) {
            return nalPayload
        }
        val converted = DoviBridge.convertDv7RpuToDv81(nalPayload, mode = 2)
            ?.takeIf { it.isNotEmpty() }
            ?: nalPayload
        return normalizeNuhLayerIdToZero(converted)
    }

    private fun maybeConvertDolbyVisionRpuNal(nalPayload: ByteArray): ByteArray {
        if (nalPayload.isEmpty()) return nalPayload
        val nalType = getNalUnitType(nalPayload)
        if (nalType != NAL_TYPE_UNSPEC62) return nalPayload
        val converted = DoviBridge.convertDv7RpuToDv81(nalPayload, mode = 2)
            ?.takeIf { it.isNotEmpty() }
            ?: nalPayload
        return normalizeNuhLayerIdToZero(converted)
    }

    private fun getNalUnitType(nalPayload: ByteArray): Int {
        return (nalPayload[0].toInt() ushr 1) and 0x3F
    }

    private fun getNuhLayerId(nalPayload: ByteArray): Int {
        if (nalPayload.size < 2) return 0
        val b0 = nalPayload[0].toInt() and 0x01
        val b1 = nalPayload[1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2) return nalPayload
        if (getNuhLayerId(nalPayload) == 0) return nalPayload
        val out = nalPayload.copyOf()
        // Keep nal_unit_type and temporal_id_plus1, force nuh_layer_id to 0.
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }

    private fun readLengthField(data: ByteArray, offset: Int, lengthBytes: Int): Int {
        var value = 0
        for (i in 0 until lengthBytes) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(
        out: ByteArrayOutputStream,
        value: Int,
        lengthBytes: Int
    ): Boolean {
        if (value < 0) return false
        val maxNalSize = when (lengthBytes) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            4 -> Int.MAX_VALUE
            else -> return false
        }
        if (value > maxNalSize) return false
        for (shift in (lengthBytes - 1) downTo 0) {
            out.write((value ushr (shift * 8)) and 0xFF)
        }
        return true
    }

    private fun normalizeDolbyVisionCodecString(codecs: String?): String? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').toMutableList()
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        val profileValue = parts[1].toIntOrNull() ?: return null
        if (profileValue != 7) return null
        val width = parts[1].length.coerceAtLeast(2)
        parts[1] = "8".padStart(width, '0')
        return parts.joinToString(".")
    }

    private fun resolveDolbyVisionProfile(
        codecs: String? = null,
        configBytes: ByteArray? = null
    ): Int? {
        resolveDolbyVisionProfileFromCodecString(codecs)?.let { return it }
        if (configBytes == null || configBytes.isEmpty()) return null
        return runCatching {
            DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
        }.getOrNull()
    }

    private fun resolveDolbyVisionProfileFromCodecString(codecs: String?): Int? {
        val raw = codecs?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split('.')
        if (parts.size < 2) return null
        val prefix = parts[0].lowercase()
        if (prefix != "dvhe" && prefix != "dvh1") return null
        return parts[1].toIntOrNull()
    }

    private fun appendLengthDelimitedNal(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        nalPayload: ByteArray
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4 || nalPayload.isEmpty()) return null
        val maxNalSize = when (nalUnitLengthFieldLength) {
            1 -> 0xFF
            2 -> 0xFFFF
            3 -> 0xFFFFFF
            else -> Int.MAX_VALUE
        }
        if (nalPayload.size > maxNalSize) {
            return null
        }

        val out = ByteArray(sampleLengthDelimited.size + nalUnitLengthFieldLength + nalPayload.size)
        System.arraycopy(sampleLengthDelimited, 0, out, 0, sampleLengthDelimited.size)

        var value = nalPayload.size
        var offset = sampleLengthDelimited.size + nalUnitLengthFieldLength - 1
        repeat(nalUnitLengthFieldLength) {
            out[offset] = (value and 0xFF).toByte()
            value = value ushr 8
            offset--
        }

        System.arraycopy(
            nalPayload,
            0,
            out,
            sampleLengthDelimited.size + nalUnitLengthFieldLength,
            nalPayload.size
        )
        return out
    }

    private fun String.safeHost(): String {
        return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
    }
}
