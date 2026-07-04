package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AuthDiagnosticReportRequestDto(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "startedAtMs") val startedAtMs: Long,
    @Json(name = "endedAtMs") val endedAtMs: Long,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "app") val app: AuthDiagnosticAppDto,
    @Json(name = "device") val device: AuthDiagnosticDeviceDto,
    @Json(name = "environment") val environment: AuthDiagnosticEnvironmentDto,
    @Json(name = "flow") val flow: AuthDiagnosticFlowDto,
    @Json(name = "terminal") val terminal: AuthDiagnosticTerminalDto,
    @Json(name = "timeline") val timeline: List<AuthDiagnosticEventDto>,
    @Json(name = "exceptions") val exceptions: List<AuthDiagnosticExceptionDto>,
    @Json(name = "rawLogs") val rawLogs: List<String>
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticAppDto(
    @Json(name = "applicationId") val applicationId: String,
    @Json(name = "versionName") val versionName: String,
    @Json(name = "versionCode") val versionCode: Long,
    @Json(name = "debugBuild") val debugBuild: Boolean
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticDeviceDto(
    @Json(name = "manufacturer") val manufacturer: String,
    @Json(name = "brand") val brand: String,
    @Json(name = "model") val model: String,
    @Json(name = "product") val product: String,
    @Json(name = "androidRelease") val androidRelease: String,
    @Json(name = "sdkInt") val sdkInt: Int,
    @Json(name = "supportedAbis") val supportedAbis: List<String>
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticEnvironmentDto(
    @Json(name = "supabaseUrl") val supabaseUrl: String,
    @Json(name = "supabaseHost") val supabaseHost: String?,
    @Json(name = "tvLoginWebBaseUrl") val tvLoginWebBaseUrl: String,
    @Json(name = "tvLoginHost") val tvLoginHost: String?,
    @Json(name = "tvLoginWebHost") val tvLoginWebHost: String?,
    @Json(name = "reportsBaseUrlConfigured") val reportsBaseUrlConfigured: Boolean
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticFlowDto(
    @Json(name = "type") val type: String,
    @Json(name = "attemptId") val attemptId: String,
    @Json(name = "qrTraceId") val qrTraceId: Long?
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticTerminalDto(
    @Json(name = "status") val status: String,
    @Json(name = "reason") val reason: String,
    @Json(name = "failingEndpoint") val failingEndpoint: String?,
    @Json(name = "httpStatus") val httpStatus: Int?,
    @Json(name = "networkErrorFamily") val networkErrorFamily: String?
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticEventDto(
    @Json(name = "type") val type: String,
    @Json(name = "timeMs") val timeMs: Long,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "endpoint") val endpoint: String? = null,
    @Json(name = "method") val method: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "request") val request: AuthDiagnosticRequestDto? = null,
    @Json(name = "response") val response: AuthDiagnosticResponseDto? = null,
    @Json(name = "network") val network: AuthDiagnosticNetworkDto? = null,
    @Json(name = "exception") val exception: AuthDiagnosticExceptionDto? = null,
    @Json(name = "detail") val detail: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticRequestDto(
    @Json(name = "headers") val headers: Map<String, String>,
    @Json(name = "body") val body: String?
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticResponseDto(
    @Json(name = "statusCode") val statusCode: Int?,
    @Json(name = "isSuccessful") val isSuccessful: Boolean?,
    @Json(name = "headers") val headers: Map<String, String>,
    @Json(name = "body") val body: String?,
    @Json(name = "bodyBytes") val bodyBytes: Int?,
    @Json(name = "contentType") val contentType: String?
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticNetworkDto(
    @Json(name = "phase") val phase: String,
    @Json(name = "host") val host: String? = null,
    @Json(name = "inetAddresses") val inetAddresses: List<String> = emptyList(),
    @Json(name = "proxy") val proxy: String? = null,
    @Json(name = "protocol") val protocol: String? = null,
    @Json(name = "durationMs") val durationMs: Long? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticExceptionDto(
    @Json(name = "className") val className: String,
    @Json(name = "message") val message: String?,
    @Json(name = "causeChain") val causeChain: List<String>,
    @Json(name = "stackTrace") val stackTrace: String
)

@JsonClass(generateAdapter = true)
data class AuthDiagnosticReportResponseDto(
    @Json(name = "reportId") val reportId: String? = null,
    @Json(name = "id") val id: String? = null
)
