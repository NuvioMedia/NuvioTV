package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.core.debrid.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.core.debrid.StreamBadgeRules
import com.nuvio.tv.core.debrid.StreamBadgeRulesParser
import com.nuvio.tv.domain.model.DebridStreamPreferences
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class DebridFormatterConfigServer(
    private val currentSettingsProvider: () -> DebridFormatterSettings,
    private val onSettingsChanged: (DebridFormatterSettings) -> Unit,
    private val context: Context? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8090
) : NanoHTTPD(port) {
    private val gson = Gson()
    private val settingsMapType = object : TypeToken<Map<String, Any?>>() {}.type
    @OptIn(ExperimentalSerializationApi::class)
    private val badgeJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
            session.method == Method.GET && session.uri == "/api/settings" -> serveSettings()
            session.method == Method.POST && session.uri == "/api/settings" -> handleSettingsUpdate(session)
            session.method == Method.POST && session.uri == "/api/badges/import" -> handleBadgeImport(session)
            session.method == Method.POST && session.uri == "/api/badges/active" -> handleBadgeActive(session)
            session.method == Method.POST && session.uri == "/api/badges/delete" -> handleBadgeDelete(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            DebridFormatterWebPage.html(context)
        )
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider?.invoke()
        return if (bytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveSettings(): Response {
        val response = DebridFormatterSettingsResponse(
            settings = currentSettingsProvider(),
            defaults = DebridFormatterSettings(
                nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
                descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE,
                streamPreferences = DebridStreamPreferences(),
                streamBadgeRules = StreamBadgeRules()
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun handleSettingsUpdate(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val parsed = runCatching {
            gson.fromJson<Map<String, Any?>>(body, settingsMapType)
        }.getOrNull()
        val nameTemplate = (parsed?.get("nameTemplate") as? String)?.takeIf { it.isNotBlank() }
        val descriptionTemplate = (parsed?.get("descriptionTemplate") as? String)?.takeIf { it.isNotBlank() }
        val currentSettings = currentSettingsProvider()
        val streamPreferences = runCatching {
            gson.fromJson(gson.toJson(parsed?.get("streamPreferences")), DebridStreamPreferences::class.java)
        }.getOrNull() ?: currentSettings.streamPreferences
        val streamBadgeRules = parseStreamBadgeRules(parsed?.get("streamBadgeRules")) ?: currentSettings.streamBadgeRules
        if (nameTemplate == null || descriptionTemplate == null) {
            return errorResponse("Both templates are required")
        }

        onSettingsChanged(
            DebridFormatterSettings(
                nameTemplate = nameTemplate,
                descriptionTemplate = descriptionTemplate,
                streamPreferences = streamPreferences,
                streamBadgeRules = streamBadgeRules
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(mapOf("status" to "saved")))
    }

    private fun handleBadgeImport(session: IHTTPSession): Response {
        val parsed = parseBodyMap(session)
        val rawSourceUrl = (parsed?.get("sourceUrl") as? String).orEmpty().trim()
        val pastedPayload = (parsed?.get("payload") as? String).orEmpty()
        if (rawSourceUrl.isBlank() && pastedPayload.isBlank()) {
            return errorResponse("Enter a badge JSON URL.")
        }
        if (pastedPayload.isBlank() &&
            !rawSourceUrl.startsWith("https://", ignoreCase = true) &&
            !rawSourceUrl.startsWith("http://", ignoreCase = true)
        ) {
            return errorResponse("Badge URL must start with http:// or https://.")
        }

        val currentSettings = currentSettingsProvider()
        val currentRules = currentSettings.streamBadgeRules.normalized()
        val sourceUrl = rawSourceUrl.ifBlank { "Pasted badge rules" }
        val isExistingImport = currentRules.imports.any { import ->
            import.sourceUrl.equals(sourceUrl, ignoreCase = true)
        }
        if (!isExistingImport && currentRules.imports.size >= STREAM_BADGE_IMPORT_LIMIT) {
            return errorResponse("You can import up to $STREAM_BADGE_IMPORT_LIMIT badge URLs.")
        }

        return try {
            val payload = pastedPayload.ifBlank { fetchText(sourceUrl) }
            val parsedImport = StreamBadgeRulesParser.parse(
                sourceUrl = sourceUrl,
                payload = payload
            )
            val rules = currentRules.upsert(parsedImport, activate = true)
            onSettingsChanged(currentSettings.copy(streamBadgeRules = rules))
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                gson.toJson(mapOf("status" to "imported", "streamBadgeRules" to rules))
            )
        } catch (error: Exception) {
            errorResponse(error.message ?: "Badge import failed.")
        }
    }

    private fun handleBadgeActive(session: IHTTPSession): Response {
        val sourceUrl = (parseBodyMap(session)?.get("sourceUrl") as? String).orEmpty()
        val currentSettings = currentSettingsProvider()
        val rules = currentSettings.streamBadgeRules.normalized().setActiveSource(sourceUrl)
        onSettingsChanged(currentSettings.copy(streamBadgeRules = rules))
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("status" to "saved", "streamBadgeRules" to rules))
        )
    }

    private fun handleBadgeDelete(session: IHTTPSession): Response {
        val sourceUrl = (parseBodyMap(session)?.get("sourceUrl") as? String).orEmpty()
        val currentSettings = currentSettingsProvider()
        val rules = currentSettings.streamBadgeRules.normalized().removeSource(sourceUrl)
        onSettingsChanged(currentSettings.copy(streamBadgeRules = rules))
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("status" to "deleted", "streamBadgeRules" to rules))
        )
    }

    private fun parseBodyMap(session: IHTTPSession): Map<String, Any?>? {
        val body = readUtf8Body(session)
        return runCatching {
            gson.fromJson<Map<String, Any?>>(body, settingsMapType)
        }.getOrNull()
    }

    private fun parseStreamBadgeRules(value: Any?): StreamBadgeRules? {
        if (value == null) return null
        return try {
            badgeJson.decodeFromString<StreamBadgeRules>(gson.toJson(value)).normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalArgumentException("Badge import failed.")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun errorResponse(message: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("error" to message))
        )

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    companion object {
        fun startOnAvailablePort(
            currentSettingsProvider: () -> DebridFormatterSettings,
            onSettingsChanged: (DebridFormatterSettings) -> Unit,
            context: Context? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8090,
            maxAttempts: Int = 10
        ): DebridFormatterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = DebridFormatterConfigServer(
                        currentSettingsProvider = currentSettingsProvider,
                        onSettingsChanged = onSettingsChanged,
                        context = context,
                        logoProvider = logoProvider,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                }
            }
            return null
        }
    }
}

data class DebridFormatterSettings(
    val nameTemplate: String,
    val descriptionTemplate: String,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences(),
    val streamBadgeRules: StreamBadgeRules = StreamBadgeRules()
)

private data class DebridFormatterSettingsResponse(
    val settings: DebridFormatterSettings,
    val defaults: DebridFormatterSettings
)
