package com.nuvio.tv.core.player

import org.json.JSONObject

/**
 * Snapshot of the most recent playback's diagnostic data.
 *
 * Populated during initializePlayer and onRenderedFirstFrame / onPlayerError.
 * Persisted to DataStore so users can take a photo of Settings without ADB
 * to report which DV decision branch was taken on their device.
 *
 * All fields are nullable / have safe defaults so partial data is okay
 * (e.g. if playback errored before first frame, conversion counts will be 0).
 */
data class LastPlaybackDiagnostics(
    val timestampMs: Long = 0L,
    val host: String = "",

    // Display capabilities (from DolbyVisionBaseLayerPolicy.Result)
    val hdrCapsKnown: Boolean = false,
    val displayDv: Boolean = false,
    val displayHdr10: Boolean = false,
    val displayHdr10Plus: Boolean = false,

    // Codec capabilities
    val codecDv7Supported: Boolean = false,
    val dv81DecoderName: String? = null,  // e.g. "OMX.MTK.VIDEO.DECODER.DVHE.STH"

    // libdovi bridge
    val bridgeReady: Boolean = false,
    val bridgeVersion: String? = null,
    val bridgeReason: String? = null,  // "ready", "hdr10-base-layer-mode", etc.

    // DV decision
    val dv7ModeRequested: String = "",   // AUTO, HDR10_BASE_LAYER, DV81_LIBDOVI, OFF
    val dv7ModeEffective: String = "",
    val dv7AutoDecision: String? = null, // CONVERT_TO_DV81, STRIP_TO_HDR10, etc. (null when not AUTO)

    // Buffer/Network toggles
    val bufferEngineEnabled: Boolean = false,
    val parallelNetworkEnabled: Boolean = false,

    // Outcome
    val firstFrameMs: Long = -1L,        // -1 = never rendered
    val dv7DoviCalls: Int = 0,
    val dv7DoviSuccess: Int = 0,
    val dv7DoviSignalRewrites: Int = 0,
    val dvSourceProfile: String? = null,

    // Video output (captured at first frame from the played video Format)
    val videoResolution: String? = null, // e.g. "3840x2160"
    val videoCodec: String? = null,       // e.g. "Dolby Vision", "HEVC", "AV1"
    val videoHdrType: String? = null,     // e.g. "Dolby Vision", "HDR10", "HLG", "SDR"

    // Buffer telemetry (rebuffers counted after first frame; re-persisted at playback end)
    val rebufferCount: Int = 0,
    val rebufferTotalMs: Long = 0L,

    val result: String = "Pending"       // "Played", "Error: ..."
) {
    /**
     * Serialize to a JSON string for DataStore persistence.
     * Uses org.json (built into Android) to avoid pulling in kotlinx-serialization
     * or moshi for one data class.
     */
    fun toJson(): String = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("host", host)
        put("hdrCapsKnown", hdrCapsKnown)
        put("displayDv", displayDv)
        put("displayHdr10", displayHdr10)
        put("displayHdr10Plus", displayHdr10Plus)
        put("codecDv7Supported", codecDv7Supported)
        put("dv81DecoderName", dv81DecoderName ?: JSONObject.NULL)
        put("bridgeReady", bridgeReady)
        put("bridgeVersion", bridgeVersion ?: JSONObject.NULL)
        put("bridgeReason", bridgeReason ?: JSONObject.NULL)
        put("dv7ModeRequested", dv7ModeRequested)
        put("dv7ModeEffective", dv7ModeEffective)
        put("dv7AutoDecision", dv7AutoDecision ?: JSONObject.NULL)
        put("bufferEngineEnabled", bufferEngineEnabled)
        put("parallelNetworkEnabled", parallelNetworkEnabled)
        put("firstFrameMs", firstFrameMs)
        put("dv7DoviCalls", dv7DoviCalls)
        put("dv7DoviSuccess", dv7DoviSuccess)
        put("dv7DoviSignalRewrites", dv7DoviSignalRewrites)
        put("dvSourceProfile", dvSourceProfile ?: JSONObject.NULL)
        put("videoResolution", videoResolution ?: JSONObject.NULL)
        put("videoCodec", videoCodec ?: JSONObject.NULL)
        put("videoHdrType", videoHdrType ?: JSONObject.NULL)
        put("rebufferCount", rebufferCount)
        put("rebufferTotalMs", rebufferTotalMs)
        put("result", result)
    }.toString()

    companion object {
        val EMPTY = LastPlaybackDiagnostics()

        /**
         * Parse from a JSON string previously produced by [toJson].
         * Returns [EMPTY] on any parse failure or invalid input.
         */
        fun fromJson(json: String): LastPlaybackDiagnostics = try {
            val o = JSONObject(json)
            LastPlaybackDiagnostics(
                timestampMs = o.optLong("timestampMs", 0L),
                host = o.optString("host", ""),
                hdrCapsKnown = o.optBoolean("hdrCapsKnown", false),
                displayDv = o.optBoolean("displayDv", false),
                displayHdr10 = o.optBoolean("displayHdr10", false),
                displayHdr10Plus = o.optBoolean("displayHdr10Plus", false),
                codecDv7Supported = o.optBoolean("codecDv7Supported", false),
                dv81DecoderName = o.optString("dv81DecoderName", "").let { if (it.isBlank() || it == "null") null else it },
                bridgeReady = o.optBoolean("bridgeReady", false),
                bridgeVersion = o.optString("bridgeVersion", "").let { if (it.isBlank() || it == "null") null else it },
                bridgeReason = o.optString("bridgeReason", "").let { if (it.isBlank() || it == "null") null else it },
                dv7ModeRequested = o.optString("dv7ModeRequested", ""),
                dv7ModeEffective = o.optString("dv7ModeEffective", ""),
                dv7AutoDecision = o.optString("dv7AutoDecision", "").let { if (it.isBlank() || it == "null") null else it },
                bufferEngineEnabled = o.optBoolean("bufferEngineEnabled", false),
                parallelNetworkEnabled = o.optBoolean("parallelNetworkEnabled", false),
                firstFrameMs = o.optLong("firstFrameMs", -1L),
                dv7DoviCalls = o.optInt("dv7DoviCalls", 0),
                dv7DoviSuccess = o.optInt("dv7DoviSuccess", 0),
                dv7DoviSignalRewrites = o.optInt("dv7DoviSignalRewrites", 0),
                dvSourceProfile = o.optString("dvSourceProfile", "").let { if (it.isBlank() || it == "null") null else it },
                videoResolution = o.optString("videoResolution", "").let { if (it.isBlank() || it == "null") null else it },
                videoCodec = o.optString("videoCodec", "").let { if (it.isBlank() || it == "null") null else it },
                videoHdrType = o.optString("videoHdrType", "").let { if (it.isBlank() || it == "null") null else it },
                rebufferCount = o.optInt("rebufferCount", 0),
                rebufferTotalMs = o.optLong("rebufferTotalMs", 0L),
                result = o.optString("result", "Pending")
            )
        } catch (e: Exception) {
            EMPTY
        }
    }
}