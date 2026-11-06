package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

class CustomPosterConfigServer(
    private val currentPatternProvider: () -> String,
    private val onPatternChanged: (String) -> Unit,
    private val context: Context? = null,
    port: Int = 8092
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        ConfigServerGuard.rejection(session)?.let { return it }
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/pattern" -> serveCurrentPattern()
            session.method == Method.POST && session.uri == "/api/pattern" -> handlePatternUpdate(session)
            session.method == Method.POST && session.uri == "/api/clear" -> handleClear()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            CustomPosterWebPage.html(context)
        )

    private fun serveCurrentPattern(): Response {
        val pattern = currentPatternProvider()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("pattern" to pattern))
        )
    }

    private fun handlePatternUpdate(session: IHTTPSession): Response {
        val body = ConfigServerGuard.readBody(session)
        val pattern = runCatching { gson.fromJson(body, JsonObject::class.java) }
            .getOrNull()
            ?.get("pattern")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
        if (pattern == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                gson.toJson(mapOf("error" to "Missing pattern field"))
            )
        }
        onPatternChanged(pattern.trim())
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("status" to "saved"))
        )
    }

    private fun handleClear(): Response {
        onPatternChanged("")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(mapOf("status" to "cleared"))
        )
    }

    companion object {
        fun startOnAvailablePort(
            currentPatternProvider: () -> String,
            onPatternChanged: (String) -> Unit,
            context: Context? = null,
            startPort: Int = 8092,
            maxAttempts: Int = 10
        ): CustomPosterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = CustomPosterConfigServer(
                        currentPatternProvider = currentPatternProvider,
                        onPatternChanged = onPatternChanged,
                        context = context,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
