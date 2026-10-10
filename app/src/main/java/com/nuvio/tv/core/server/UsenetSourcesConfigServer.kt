package com.nuvio.tv.core.server

import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.ProviderTestResult
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.messageRes
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What the phone page may do. Implemented by the Usenet settings while their QR code is shown. */
interface UsenetPhoneSetup {
    fun configuration(): UsenetSourceConfiguration?
    /** Applies [change] to the configuration saved on the TV; false when it could not be saved. [name] is shown on the TV. */
    fun save(name: String, change: (UsenetSourceConfiguration) -> UsenetSourceConfiguration): Boolean
    /** Null when the settings are invalid. */
    fun testProvider(provider: UsenetProvider): ProviderTestResult?
    fun testIndexer(indexer: UsenetIndexer): Boolean
}

/**
 * Lets a phone on the same network add or edit built-in Usenet sources. Every request needs the
 * random token from the QR code, so only someone who can see the TV screen gets in. Passwords and
 * API keys are never sent back to the phone; leaving one blank keeps the saved value.
 */
class UsenetSourcesConfigServer(
    private val setup: UsenetPhoneSetup,
    private val page: () -> String,
    private val text: (Int) -> String,
    private val logo: () -> ByteArray? = { null },
    port: Int = 8100
) : NanoHTTPD(port) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        // The page and logo hold no data; the page reads the token from the QR code's link.
        if (session.method == Method.GET && session.uri == "/") {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
        }
        if (session.method == Method.GET && session.uri == "/logo.png") return serveLogo()
        // Read the whole body before any answer: an unread body would corrupt the next request on this connection.
        val body = read(session) ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Request too large")
            .apply { closeConnection(true) }
        val given = session.headers[TOKEN_HEADER]
        if (given == null || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            return error(Response.Status.FORBIDDEN, R.string.usenet_phone_link_expired)
        }
        return when {
            session.method == Method.GET && session.uri == "/api/sources" -> serveSources()
            session.method == Method.POST && session.uri == "/api/providers" -> saveProvider(body)
            session.method == Method.POST && session.uri == "/api/indexers" -> saveIndexer(body)
            session.method == Method.POST && session.uri == "/api/providers/test" -> testProvider(body)
            session.method == Method.POST && session.uri == "/api/indexers/test" -> testIndexer(body)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveLogo(): Response {
        val bytes = logo() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun serveSources(): Response {
        val configuration = setup.configuration() ?: return error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
        return ok(json.encodeToString(PhoneSources(
            providers = configuration.providers.map { PhoneProvider.of(it) },
            indexers = configuration.indexers.map { PhoneIndexer.of(it) }
        )))
    }

    private fun saveProvider(body: String): Response {
        val input = parse<PhoneProvider>(body) ?: return error(Response.Status.BAD_REQUEST, R.string.usenet_provider_invalid)
        val configuration = setup.configuration() ?: return error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
        val provider = input.merge(configuration.providers.find { it.id == input.id })
        if (runCatching { provider.validate() }.isFailure) return error(Response.Status.BAD_REQUEST, R.string.usenet_provider_invalid)
        val saved = setup.save(provider.name) { it.copy(providers = it.providers.replaceOrAdd(provider) { p -> p.id }) }
        return if (saved) ok(json.encodeToString(Saved(provider.id, text(R.string.usenet_phone_saved))))
        else error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
    }

    private fun saveIndexer(body: String): Response {
        val input = parse<PhoneIndexer>(body) ?: return error(Response.Status.BAD_REQUEST, R.string.usenet_indexer_invalid)
        val configuration = setup.configuration() ?: return error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
        val indexer = input.merge(configuration.indexers.find { it.id == input.id })
        if (runCatching { indexer.validate() }.isFailure) return error(Response.Status.BAD_REQUEST, R.string.usenet_indexer_invalid)
        val saved = setup.save(indexer.name) { it.copy(indexers = it.indexers.replaceOrAdd(indexer) { i -> i.id }) }
        return if (saved) ok(json.encodeToString(Saved(indexer.id, text(R.string.usenet_phone_saved))))
        else error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
    }

    private fun testProvider(body: String): Response {
        val input = parse<PhoneProvider>(body) ?: return error(Response.Status.BAD_REQUEST, R.string.usenet_provider_invalid)
        val configuration = setup.configuration() ?: return error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
        val provider = input.merge(configuration.providers.find { it.id == input.id })
        val result = setup.testProvider(provider) ?: return error(Response.Status.BAD_REQUEST, R.string.usenet_provider_invalid)
        val anonymous = provider.username.isEmpty() && provider.password.isEmpty()
        return ok(json.encodeToString(Tested(result == ProviderTestResult.SUCCESS, text(result.messageRes(anonymous)))))
    }

    private fun testIndexer(body: String): Response {
        val input = parse<PhoneIndexer>(body) ?: return error(Response.Status.BAD_REQUEST, R.string.usenet_indexer_invalid)
        val configuration = setup.configuration() ?: return error(Response.Status.CONFLICT, R.string.usenet_phone_unavailable)
        val indexer = input.merge(configuration.indexers.find { it.id == input.id })
        if (runCatching { indexer.validate() }.isFailure) return error(Response.Status.BAD_REQUEST, R.string.usenet_indexer_invalid)
        val success = setup.testIndexer(indexer)
        return ok(json.encodeToString(Tested(success, text(if (success) R.string.usenet_test_success else R.string.usenet_test_failed))))
    }

    private inline fun <reified T> parse(body: String): T? = runCatching { json.decodeFromString<T>(body) }.getOrNull()

    /** Null when the body is too large to read. */
    private fun read(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length !in 0..MAX_BODY) return null
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, JSON, body)

    private fun error(status: Response.Status, message: Int) =
        newFixedLengthResponse(status, JSON, json.encodeToString(Failure(text(message))))

    @Serializable private data class Saved(val id: String, val message: String)
    @Serializable private data class Tested(val ok: Boolean, val message: String)
    @Serializable private data class Failure(val error: String)

    companion object {
        const val TOKEN_HEADER = "x-setup-token"
        private const val JSON = "application/json; charset=utf-8"
        private const val MAX_BODY = 16 * 1024

        fun startOnAvailablePort(
            setup: UsenetPhoneSetup,
            page: () -> String,
            text: (Int) -> String,
            logo: () -> ByteArray? = { null },
            startPort: Int = 8100,
            maxAttempts: Int = 10
        ): UsenetSourcesConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    return UsenetSourcesConfigServer(setup, page, text, logo, port).apply { start(SOCKET_READ_TIMEOUT, false) }
                } catch (_: Exception) {
                    // Port in use, try the next one.
                }
            }
            return null
        }
    }
}

@Serializable
internal data class PhoneSources(val providers: List<PhoneProvider>, val indexers: List<PhoneIndexer>)

/** A provider as the phone sees it: the password is only ever sent from the phone, never to it. */
@Serializable
internal data class PhoneProvider(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String = "",
    val hasPassword: Boolean = false,
    val connections: Int = 20,
    val priority: Int = 1,
    val enabled: Boolean = true
) {
    /** Edits [existing] when the phone edited a saved provider, so its id and enabled state are kept. */
    fun merge(existing: UsenetProvider?) = (existing ?: UsenetProvider()).copy(
        name = name.trim(), host = host.trim(), port = port, tls = tls, username = username,
        password = password.ifEmpty { existing?.password.orEmpty() }, connections = connections, priority = priority
    )

    companion object {
        fun of(provider: UsenetProvider) = PhoneProvider(provider.id, provider.name, provider.host, provider.port,
            provider.tls, provider.username, hasPassword = provider.password.isNotEmpty(),
            connections = provider.connections, priority = provider.priority, enabled = provider.enabled)
    }
}

/** An indexer as the phone sees it: the API key is only ever sent from the phone, never to it. */
@Serializable
internal data class PhoneIndexer(
    val id: String? = null,
    val name: String = "",
    val apiUrl: String = "",
    val apiKey: String = "",
    val hasKey: Boolean = false,
    val priority: Int = 1,
    val enabled: Boolean = true
) {
    fun merge(existing: UsenetIndexer?) = (existing ?: UsenetIndexer()).copy(
        name = name.trim(), apiUrl = apiUrl.trim(), apiKey = apiKey.trim().ifEmpty { existing?.apiKey.orEmpty() },
        priority = priority
    )

    companion object {
        fun of(indexer: UsenetIndexer) = PhoneIndexer(indexer.id, indexer.name, indexer.apiUrl,
            hasKey = indexer.apiKey.isNotEmpty(), priority = indexer.priority, enabled = indexer.enabled)
    }
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value
