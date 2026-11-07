package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.domain.model.DebridStreamPreferences
import fi.iki.elonen.NanoHTTPD

class DebridFormatterConfigServer(
    private val currentSettingsProvider: () -> DebridFormatterSettings,
    private val onSettingsChanged: (DebridFormatterSettings) -> Unit,
    private val context: Context? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8090
) : NanoHTTPD(port) {
    private val gson = Gson()
    private val settingsMapType = object : TypeToken<Map<String, Any?>>() {}.type

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
            session.method == Method.GET && session.uri == "/api/settings" -> serveSettings()
            session.method == Method.POST && session.uri == "/api/settings" -> handleSettingsUpdate(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response = htmlResponse(DebridFormatterWebPage.html(context))

    private fun serveLogo(): Response = pngLogoResponse(logoProvider?.invoke())

    private fun serveSettings(): Response {
        val settings = currentSettingsProvider()
        val defaults = DebridFormatterSettings(
            nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
            descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE,
            streamPreferences = DebridStreamPreferences()
        )
        val settingsMap = mapOf(
            "nameTemplate" to settings.nameTemplate,
            "descriptionTemplate" to settings.descriptionTemplate,
            "streamPreferences" to settings.streamPreferences
        )
        val defaultsMap = mapOf(
            "nameTemplate" to defaults.nameTemplate,
            "descriptionTemplate" to defaults.descriptionTemplate,
            "streamPreferences" to defaults.streamPreferences
        )
        val settingsJson = gson.toJson(settingsMap)
        val defaultsJson = gson.toJson(defaultsMap)
        val responseJson = """{"settings":$settingsJson,"defaults":$defaultsJson}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", responseJson)
    }

    private fun handleSettingsUpdate(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val parsed = runCatching {
            gson.fromJson<Map<String, Any?>>(body, settingsMapType)
        }.getOrNull()
        val nameTemplate = parsed?.get("nameTemplate") as? String
        val descriptionTemplate = parsed?.get("descriptionTemplate") as? String
        val currentSettings = currentSettingsProvider()
        val streamPreferences = runCatching {
            gson.fromJson(gson.toJson(parsed?.get("streamPreferences")), DebridStreamPreferences::class.java)
        }.getOrNull() ?: currentSettings.streamPreferences
        if (nameTemplate == null || descriptionTemplate == null) {
            return errorResponse(context?.getString(com.nuvio.tv.R.string.web_debrid_error_templates_required) ?: "Both templates are required")
        }

        onSettingsChanged(
            DebridFormatterSettings(
                nameTemplate = nameTemplate,
                descriptionTemplate = descriptionTemplate,
                streamPreferences = streamPreferences
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(mapOf("status" to "saved")))
    }

    private fun errorResponse(message: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("error" to message))
        )

    companion object {
        fun startOnAvailablePort(
            currentSettingsProvider: () -> DebridFormatterSettings,
            onSettingsChanged: (DebridFormatterSettings) -> Unit,
            context: Context? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8090,
            maxAttempts: Int = 10
        ): DebridFormatterConfigServer? = startServerOnAvailablePort(startPort, maxAttempts) { port ->
            DebridFormatterConfigServer(
                currentSettingsProvider = currentSettingsProvider,
                onSettingsChanged = onSettingsChanged,
                context = context,
                logoProvider = logoProvider,
                port = port
            )
        }
    }
}

data class DebridFormatterSettings(
    val nameTemplate: String,
    val descriptionTemplate: String,
    val streamPreferences: DebridStreamPreferences = DebridStreamPreferences()
)
