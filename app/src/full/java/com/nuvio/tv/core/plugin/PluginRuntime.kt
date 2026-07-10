package com.nuvio.tv.core.plugin

import android.util.Log
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.LocalScraperResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginRuntime"
private const val PLUGIN_TIMEOUT_MS = 60_000L
private const val MAX_FETCH_RESPONSE_BYTES = 1024 * 1024
private const val MAX_FETCH_BODY_CHARS = 1024 * 1024
private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

@Singleton
class PluginRuntime @Inject constructor() {

    private val gson: Gson = GsonBuilder().create()

    private val httpClient = OkHttpClient.Builder()
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .proxy(java.net.Proxy.NO_PROXY)
        .dispatcher(okhttp3.Dispatcher(
            java.util.concurrent.Executors.newCachedThreadPool { runnable ->
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, "okhttp-plugin-worker").apply {
                    isDaemon = true
                }
            }
        ))
        .build()

    // Pre-compiled regex for :contains() selector conversion
    private val containsRegex = Regex(""":contains\(["']([^"']+)["']\)""")

    @Volatile
    private var compiledPolyfillBytecode: ByteArray? = null

    @Volatile
    private var compiledCallBytecode: ByteArray? = null

    private fun getCompiledPolyfillBytecode(qjs: com.dokar.quickjs.QuickJs): ByteArray {
        compiledPolyfillBytecode?.let { return it }
        synchronized(this) {
            compiledPolyfillBytecode?.let { return it }
            try {
                val bytecode = qjs.compile(getStaticPolyfillCode(), "polyfill.js", false)
                compiledPolyfillBytecode = bytecode
                return bytecode
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compile polyfill to bytecode: ${e.message}", e)
                throw e
            }
        }
    }

    private fun getCompiledCallBytecode(qjs: com.dokar.quickjs.QuickJs): ByteArray {
        compiledCallBytecode?.let { return it }
        synchronized(this) {
            compiledCallBytecode?.let { return it }
            try {
                val bytecode = qjs.compile(getStaticCallCode(), "call.js", false)
                compiledCallBytecode = bytecode
                return bytecode
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compile call code to bytecode: ${e.message}", e)
                throw e
            }
        }
    }

    private fun getStaticCallCode(): String {
        return """
            (async function() {
                try {
                    var getStreams = module.exports.getStreams || globalThis.getStreams;
                    if (!getStreams) {
                        console.error("getStreams function not found on module.exports or globalThis");
                        __capture_result(JSON.stringify([]));
                        return;
                    }
                    var args = JSON.parse(__get_call_args());
                    console.log("Calling getStreams with tmdbId=" + args.tmdbId + " type=" + args.mediaType + " s=" + args.season + " e=" + args.episode);
                    var result = await getStreams(args.tmdbId, args.mediaType, args.season, args.episode);
                    console.log("getStreams returned: " + (result ? result.length : 0) + " streams");
                    __capture_result(JSON.stringify(result || []));
                } catch (e) {
                    console.error("getStreams error:", e.message || e, e.stack || "");
                    __capture_result(JSON.stringify([]));
                }
            })();
        """.trimIndent()
    }

    suspend fun getPluginSettingsLayout(
        code: String,
        scraperId: String,
    ): String? = withContext(Dispatchers.Default) {
        val parentDispatcher: CoroutineDispatcher = Dispatchers.Default
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()

        try {
            quickJs(parentDispatcher) {
                define("console") {
                    function("log") { args ->
                        Log.d("PluginSettings:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                        null
                    }
                    function("error") { args ->
                        Log.e("PluginSettings:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                        null
                    }
                }

                val polyfillCode = """
                    globalThis.SCRAPER_ID = ${gson.toJson(scraperId)};
                    globalThis.SCRAPER_SETTINGS = {};
                    if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
                    if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
                    if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;
                """.trimIndent()
                evaluate<Any?>(polyfillCode)

                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedCode)

                val callCode = """
                    (async function() {
                        try {
                            var onSettings = (typeof module !== 'undefined' && module.exports && module.exports.onSettings) || globalThis.onSettings;
                            if (typeof onSettings === 'function') {
                                var layout = await onSettings();
                                __capture_settings_result(JSON.stringify(layout || []));
                            } else {
                                __capture_settings_result("[]");
                            }
                        } catch (e) {
                            console.error("onSettings error:", e);
                            __capture_settings_result("[]");
                        }
                    })();
                """.trimIndent()

                function("__capture_settings_result") { args: Array<Any?> ->
                    deferred.complete(args.getOrNull(0)?.toString())
                    null
                }

                evaluate<Any?>(callCode)
                deferred.await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get plugin settings layout", e)
            null
        }
    }

    private fun base64Decode(input: String): ByteArray {
        return Base64.getDecoder().decode(normalizeBase64(input))
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun normalizeBase64(input: String): String {
        var s = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        s = s.replace('-', '+').replace('_', '/')
        val mod = s.length % 4
        if (mod != 0) {
            s += "=".repeat(4 - mod)
        }
        return s
    }

    private val secureRandom = SecureRandom()

    private fun pluginGetRandomValues(length: Int): ByteArray {
        require(length >= 0) { "Random byte length must be non-negative" }
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    private fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
        return MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm)).digest(data)
    }

    private fun pluginPbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keySizeBits: Int,
        algorithm: String,
    ): ByteArray {
        require(iterations > 0) { "PBKDF2 iterations must be positive" }
        require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

        val prfAlgo = normalizeHmacAlgorithm(algorithm)
        val mac = Mac.getInstance(prfAlgo)
        mac.init(SecretKeySpec(password, prfAlgo))
        
        val hLen = mac.macLength
        val dkLen = keySizeBits / 8
        val dk = ByteArray(dkLen)
        
        val blocks = (dkLen + hLen - 1) / hLen
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        
        val blockIndexBytes = ByteArray(4)
        
        for (i in 1..blocks) {
            mac.reset()
            mac.update(salt)
            blockIndexBytes[0] = (i ushr 24).toByte()
            blockIndexBytes[1] = (i ushr 16).toByte()
            blockIndexBytes[2] = (i ushr 8).toByte()
            blockIndexBytes[3] = i.toByte()
            mac.update(blockIndexBytes)
            
            val u1 = mac.doFinal()
            u1.copyInto(t)
            u1.copyInto(u)
            
            for (j in 2..iterations) {
                mac.reset()
                val uj = mac.doFinal(u)
                uj.copyInto(u)
                for (k in 0 until hLen) {
                    t[k] = (t[k].toInt() xor uj[k].toInt()).toByte()
                }
            }
            
            val offset = (i - 1) * hLen
            val len = minOf(hLen, dkLen - offset)
            t.copyInto(dk, destinationOffset = offset, startIndex = 0, endIndex = len)
        }
        
        return dk
    }

    private fun pluginAesEncrypt(
        mode: String,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val normalizedMode = normalizeAesTransformation(mode)
        requireValidAesKey(key)
        if (!normalizedMode.contains("ECB")) {
            require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
        }
        val cipher = Cipher.getInstance(normalizedMode)
        val keySpec = SecretKeySpec(key, "AES")
        
        if (normalizedMode.contains("ECB")) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        } else if (normalizedMode.contains("GCM")) {
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        } else {
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        }

        return cipher.doFinal(data)
    }

    private fun pluginAesDecrypt(
        mode: String,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val normalizedMode = normalizeAesTransformation(mode)
        requireValidAesKey(key)
        if (!normalizedMode.contains("ECB")) {
            require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
        }
        val cipher = Cipher.getInstance(normalizedMode)
        val keySpec = SecretKeySpec(key, "AES")
        
        if (normalizedMode.contains("ECB")) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
        } else if (normalizedMode.contains("GCM")) {
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        } else {
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        }

        return cipher.doFinal(data)
    }

    private fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
        val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
            "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
            "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
            else -> "RSA" to "SHA256withRSA"
        }
        val factory = KeyFactory.getInstance(keyAlgo)
        val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val sig = Signature.getInstance(sigAlgo)
        sig.initSign(privKey)
        sig.update(data)
        return sig.sign()
    }

    private fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        val (keyAlgo, sigAlgo) = when (algorithm.uppercase()) {
            "RSASSA-PKCS1-V1_5-SHA256", "RSASSA-PKCS1-V1_5" -> "RSA" to "SHA256withRSA"
            "ECDSA-SHA256", "ECDSA" -> "EC" to "SHA256withECDSA"
            else -> "RSA" to "SHA256withRSA"
        }
        val factory = KeyFactory.getInstance(keyAlgo)
        val pubKey = factory.generatePublic(X509EncodedKeySpec(publicKey))
        val sig = Signature.getInstance(sigAlgo)
        sig.initVerify(pubKey)
        sig.update(data)
        return sig.verify(signature)
    }

    private fun pluginDigestHex(algorithm: String, data: String): String {
        val digest = pluginDigest(algorithm, data.encodeToByteArray())
        return digest.joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    private fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val normalized = normalizeHmacAlgorithm(algorithm)
        val mac = Mac.getInstance(normalized)
        mac.init(SecretKeySpec(key, normalized))
        return mac.doFinal(data)
    }

    private fun pluginHmacHex(algorithm: String, key: String, data: String): String {
        val digest = pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray())
        return digest.joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    private fun normalizeDigestAlgorithm(algorithm: String): String {
        return when (algorithm.normalizedAlgorithmToken()) {
            "MD5" -> "MD5"
            "SHA1" -> "SHA-1"
            "SHA256" -> "SHA-256"
            "SHA384" -> "SHA-384"
            "SHA512" -> "SHA-512"
            else -> error("Unsupported digest algorithm: $algorithm")
        }
    }

    private fun normalizeHmacAlgorithm(algorithm: String): String {
        return when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
            "MD5" -> "HmacMD5"
            "SHA1" -> "HmacSHA1"
            "SHA256" -> "HmacSHA256"
            "SHA384" -> "HmacSHA384"
            "SHA512" -> "HmacSHA512"
            else -> error("Unsupported HMAC algorithm: $algorithm")
        }
    }

    private fun normalizeAesTransformation(mode: String): String {
        val normalized = mode.normalizedAlgorithmToken()
        val noPadding = normalized.contains("NOPADDING")
        val padding = if (noPadding) "NoPadding" else "PKCS5Padding"
        return when {
            normalized.contains("GCM") -> "AES/GCM/NoPadding"
            normalized.contains("ECB") -> "AES/ECB/$padding"
            normalized.contains("CBC") -> "AES/CBC/$padding"
            else -> "AES/CBC/$padding"
        }
    }

    private fun requireValidAesKey(key: ByteArray) {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24, or 32 bytes"
        }
    }

    private fun String.normalizedAlgorithmToken(): String =
        uppercase()
            .replace("-", "")
            .replace("_", "")
            .replace("/", "")
            .replace(" ", "")

    private fun pluginUtf8ToHex(value: String): String =
        value.encodeToByteArray().joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }

    private fun pluginHexToByteArray(hex: String): ByteArray {
        val normalized = hex.trim().lowercase()
            .replace(" ", "")
            .removePrefix("0x")
        if (normalized.isEmpty()) return ByteArray(0)

        val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
        val out = ByteArray(evenHex.length / 2)
        for (index in out.indices) {
            val part = evenHex.substring(index * 2, index * 2 + 2)
            out[index] = part.toInt(16).toByte()
        }
        return out
    }

    private fun pluginHexToUtf8(hex: String): String {
        return pluginHexToByteArray(hex).decodeToString()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(((b.toInt() shr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }

    /**
     * Execute a plugin and return streams.
     *
     * Note: this function intentionally does **not** wrap with
     * `withContext(Dispatchers.IO)`. The caller (`PluginManager`) supplies a
     * dedicated low-priority dispatcher (`pluginDispatcher`) so plugin CPU
     * work can't preempt ExoPlayer / UI threads. Forcing `Dispatchers.IO`
     * here would undo that isolation.
     */
    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any> = emptyMap()
    ): List<LocalScraperResult> = withTimeout(PLUGIN_TIMEOUT_MS) {
        executePluginInternal(code, tmdbId, mediaType, season, episode, scraperId, scraperSettings)
    }

    private suspend fun executePluginInternal(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>
    ): List<LocalScraperResult> {
        val documentCache = ConcurrentHashMap<String, Document>()
        val loadedDocIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val elementCache = ConcurrentHashMap<String, Element>()
        val inFlightCalls = ConcurrentHashMap.newKeySet<Call>()

        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancellationRegistration = job?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Scraper $scraperId coroutine cancelled! Cancelling ${inFlightCalls.size} in-flight HTTP calls.")
                inFlightCalls.forEach { call -> call.cancel() }
            }
        }

        var resultJson = "[]"
        var qjsInstance: Any? = null

        // Inherit the caller's dispatcher (the low-priority
        // pluginDispatcher set up by PluginManager) instead of hard-coding
        // Dispatchers.IO, so QuickJS interpretation runs at MIN_PRIORITY too.
        // ContinuationInterceptor is the context key kotlinx-coroutines uses
        // to store the active CoroutineDispatcher.
        val parentDispatcher: CoroutineDispatcher =
            (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.IO

        try {
            quickJs(parentDispatcher) {
                qjsInstance = this
                // Define console object - must return null to avoid quickjs conversion issues
                define("console") {
                        function("log") { args ->
                            Log.d("Plugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                        function("error") { args ->
                            Log.e("Plugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                        function("warn") { args ->
                            Log.w("Plugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                        function("info") { args ->
                            Log.i("Plugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                        function("debug") { args ->
                            Log.d("Plugin:$scraperId", args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                    }

                    function("__native_fetch") { args ->
                        val url = args.getOrNull(0)?.toString() ?: ""
                        val method = args.getOrNull(1)?.toString() ?: "GET"
                        val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                        val body = args.getOrNull(3)?.toString() ?: ""
                        try {
                            performNativeFetch(url, method, headersJson, body, inFlightCalls)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Async fetch bridge error for $method $url: ${t.message}")
                            gson.toJson(
                                mapOf(
                                    "ok" to false,
                                    "status" to 0,
                                    "statusText" to (t.message ?: "Fetch failed"),
                                    "url" to url,
                                    "body" to "",
                                    "headers" to emptyMap<String, String>()
                                )
                            )
                        }
                    }

                    // Define URL parser
                    function("__parse_url") { args ->
                        val urlString = args.getOrNull(0)?.toString() ?: ""
                        parseUrl(urlString)
                    }

                    // Define cheerio load function
                    function("__cheerio_load") { args ->
                        val html = args.getOrNull(0)?.toString() ?: ""
                        val docId = UUID.randomUUID().toString()
                        val doc = Jsoup.parse(html)
                        documentCache[docId] = doc
                        loadedDocIds.add(docId)
                        
                        // Limit size to 8 active documents to reduce memory footprint while safely supporting parallel scraper requests
                        if (loadedDocIds.size > 8) {
                            val evictedId = try { loadedDocIds.removeAt(0) } catch (_: Exception) { null }
                            if (evictedId != null) {
                                documentCache.remove(evictedId)
                                // Evict associated elements
                                elementCache.keys.filter { it.startsWith("$evictedId:") }.forEach { key ->
                                    elementCache.remove(key)
                                }
                            }
                        }
                        docId
                    }

                    // Define cheerio select function
                    function("__cheerio_select") { args ->
                        val docId = args.getOrNull(0)?.toString() ?: ""
                        var selector = args.getOrNull(1)?.toString() ?: ""
                        val doc = documentCache[docId] ?: return@function "[]"
                        try {
                            // Convert cheerio :contains("text") to jsoup :contains(text)
                            selector = selector.replace(containsRegex, ":contains($1)")
                            val elements = if (selector.isEmpty()) {
                                Elements()
                            } else {
                                doc.select(selector)
                            }
                            val ids = elements.mapIndexed { index, el ->
                                val elId = "$docId:$index:${el.hashCode()}"
                                elementCache[elId] = el
                                elId
                            }
                            // Use simple JSON array construction to avoid Gson issues
                            "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
                        } catch (e: Exception) {
                            "[]"
                        }
                    }

                // Define cheerio find function
                function("__cheerio_find") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    var selector = args.getOrNull(2)?.toString() ?: ""
                    val element = elementCache[elementId] ?: return@function "[]"
                    try {
                        // Convert cheerio :contains("text") to jsoup :contains(text)
                        selector = selector.replace(containsRegex, ":contains($1)")
                        val elements = element.select(selector)
                        val ids = elements.mapIndexed { index, el ->
                            val elId = "$docId:find:$index:${el.hashCode()}"
                            elementCache[elId] = el
                            elId
                        }
                        // Use simple JSON array construction to avoid Gson issues
                        "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
                    } catch (e: Exception) {
                        "[]"
                    }
                }

                // Define cheerio text function
                function("__cheerio_text") { args ->
                    val elementIds = args.getOrNull(1)?.toString() ?: ""
                    val ids = elementIds.split(",").filter { it.isNotEmpty() }
                    val texts = ids.mapNotNull { id ->
                        elementCache[id]?.text()
                    }
                    texts.joinToString(" ")
                }

                // Define cheerio html function
                function("__cheerio_html") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    if (elementId.isEmpty()) {
                        documentCache[docId]?.html() ?: ""
                    } else {
                        elementCache[elementId]?.html() ?: ""
                    }
                }

                // Define cheerio inner html function
                function("__cheerio_inner_html") { args ->
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    elementCache[elementId]?.html() ?: ""
                }

                // Define cheerio attr function
                function("__cheerio_attr") { args ->
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val attrName = args.getOrNull(2)?.toString() ?: ""
                    val value = elementCache[elementId]?.attr(attrName)
                    if (value.isNullOrEmpty()) "__UNDEFINED__" else value
                }

                // Define cheerio next function
                function("__cheerio_next") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val el = elementCache[elementId] ?: return@function "__NONE__"
                    val next = el.nextElementSibling() ?: return@function "__NONE__"
                    val nextId = "$docId:next:${next.hashCode()}"
                    elementCache[nextId] = next
                    nextId
                }

                // Define cheerio prev function
                function("__cheerio_prev") { args ->
                    val docId = args.getOrNull(0)?.toString() ?: ""
                    val elementId = args.getOrNull(1)?.toString() ?: ""
                    val el = elementCache[elementId] ?: return@function "__NONE__"
                    val prev = el.previousElementSibling() ?: return@function "__NONE__"
                    val prevId = "$docId:prev:${prev.hashCode()}"
                    elementCache[prevId] = prev
                    prevId
                }

                // Define crypto bridge functions
                function("__crypto_get_random_values_hex") { args ->
                    val length = (args.getOrNull(0) as? Number)?.toInt() ?: 0
                    bytesToHex(pluginGetRandomValues(length))
                }

                function("__crypto_digest_hex_raw") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
                    val data = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    bytesToHex(pluginDigest(algorithm, data))
                }

                function("__crypto_hmac_hex_raw") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
                    val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val data = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
                    bytesToHex(pluginHmac(algorithm, key, data))
                }

                function("__crypto_pbkdf2_hex") { args ->
                    val password = pluginHexToByteArray(args.getOrNull(0)?.toString() ?: "")
                    val salt = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val iterations = (args.getOrNull(2) as? Number)?.toInt() ?: 1000
                    val keySizeBits = (args.getOrNull(3) as? Number)?.toInt() ?: 256
                    val algorithm = args.getOrNull(4)?.toString() ?: "SHA256"
                    bytesToHex(pluginPbkdf2(password, salt, iterations, keySizeBits, algorithm))
                }

                function("__crypto_aes_encrypt_hex") { args ->
                    val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
                    val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val iv = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
                    val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
                    bytesToHex(pluginAesEncrypt(mode, key, iv, data))
                }

                function("__crypto_aes_decrypt_hex") { args ->
                    val mode = args.getOrNull(0)?.toString() ?: "AES-CBC"
                    val key = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val iv = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
                    val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
                    bytesToHex(pluginAesDecrypt(mode, key, iv, data))
                }

                function("__crypto_sign_hex") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: ""
                    val privateKey = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val data = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
                    bytesToHex(pluginSign(algorithm, privateKey, data))
                }

                function("__crypto_verify_hex") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: ""
                    val publicKey = pluginHexToByteArray(args.getOrNull(1)?.toString() ?: "")
                    val signature = pluginHexToByteArray(args.getOrNull(2)?.toString() ?: "")
                    val data = pluginHexToByteArray(args.getOrNull(3)?.toString() ?: "")
                    pluginVerify(algorithm, publicKey, signature, data)
                }

                // --- Legacy Hex/String Bridges (Backward Compatibility) ---

                function("__crypto_digest_hex") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
                    val data = args.getOrNull(1)?.toString() ?: ""
                    runCatching {
                        pluginDigestHex(algorithm, data)
                    }.getOrDefault("")
                }

                function("__crypto_hmac_hex") { args ->
                    val algorithm = args.getOrNull(0)?.toString() ?: "SHA256"
                    val key = args.getOrNull(1)?.toString() ?: ""
                    val data = args.getOrNull(2)?.toString() ?: ""
                    runCatching {
                        pluginHmacHex(algorithm, key, data)
                    }.getOrDefault("")
                }

                function("__crypto_base64_encode") { args ->
                    val data = args.getOrNull(0)?.toString() ?: ""
                    runCatching {
                        pluginBase64Encode(data)
                    }.getOrDefault("")
                }

                function("__crypto_base64_decode") { args ->
                    val data = args.getOrNull(0)?.toString() ?: ""
                    runCatching {
                        pluginBase64Decode(data)
                    }.getOrDefault("")
                }

                function("__crypto_utf8_to_hex") { args ->
                    val data = args.getOrNull(0)?.toString() ?: ""
                    runCatching {
                        pluginUtf8ToHex(data)
                    }.getOrDefault("")
                }

                function("__crypto_hex_to_utf8") { args ->
                    val data = args.getOrNull(0)?.toString() ?: ""
                    runCatching {
                        pluginHexToUtf8(data)
                    }.getOrDefault("")
                }

                // Function to capture results - must return null to avoid quickjs conversion issues
                function("__capture_result") { args ->
                    resultJson = args.getOrNull(0)?.toString() ?: "[]"
                    null
                }

                // Inject JavaScript polyfills
                val settingsJson = gson.toJson(scraperSettings)
                function("__get_scraper_id") { scraperId }
                function("__get_scraper_settings") { settingsJson }
                function("__get_tmdb_api_key") { BuildConfig.TMDB_API_KEY }

                val polyfillBytecode = getCompiledPolyfillBytecode(this)
                evaluate<Any?>(polyfillBytecode)

                // Execute plugin code with module wrapper - wrapped in IIFE to avoid
                // redeclaration conflicts with polyfill vars (e.g. cheerio, URL, fetch).
                val wrappedCode = """
                    var module = { exports: {} };
                    var exports = module.exports;
                    (function() {
                        $code
                    })();
                """.trimIndent()
                evaluate<Any?>(wrappedCode)

                // Call getStreams and capture result
                function("__get_call_args") {
                    gson.toJson(
                        mapOf(
                            "tmdbId" to tmdbId,
                            "mediaType" to mediaType,
                            "season" to season,
                            "episode" to episode
                        )
                    )
                }

                val callBytecode = getCompiledCallBytecode(this)
                evaluate<Any?>(callBytecode)
            }

            return parseJsonResults(resultJson)

        } catch (e: Exception) {
            Log.e(TAG, "Plugin execution failed: ${e.message}", e)
            throw e
        } finally {
            cancellationRegistration?.dispose()
            // Clean up caches
            documentCache.clear()
            elementCache.clear()
            // Cancel any network calls still in progress when plugin execution exits.
            inFlightCalls.forEach { call -> call.cancel() }
            inFlightCalls.clear()
            // qjsInstance is cleared automatically when block finishes
        }
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        inFlightCalls: MutableSet<Call>
    ): String {
        Log.d(TAG, "Fetch: $method $url body=${body.take(200)}")
        return try {
            val headers = mutableMapOf<String, String>()
            try {
                val headersMap = gson.fromJson(headersJson, Map::class.java)
                headersMap?.forEach { (k, v) ->
                    if (k != null && v != null) {
                        val key = k.toString()
                        // If callers set Accept-Encoding manually, OkHttp will not transparently decompress.
                        // Strip it so OkHttp can negotiate and decode automatically.
                        if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                            headers[key] = v.toString()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore header parsing errors
            }

            // Default User-Agent
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))

            when (method.uppercase()) {
                "POST" -> {
                    val contentType = headers["Content-Type"] ?: "application/x-www-form-urlencoded"
                    // Use ByteArray.toRequestBody to prevent OkHttp from appending '; charset=utf-8'
                    // to Content-Type, which would break HMAC signature verification on servers
                    // that include Content-Type in their canonical string (e.g. MovieBox).
                    requestBuilder.post(body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
                }
                "PUT" -> {
                    val contentType = headers["Content-Type"] ?: "application/json"
                    requestBuilder.put(body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
                }
                "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            val request = requestBuilder.build()
            val call = httpClient.newCall(request)
            inFlightCalls.add(call)

            try {
                val response = call.execute()

                response.use { httpResponse ->
                    val bodyContentType = httpResponse.body?.contentType()
                    val contentEncoding = httpResponse.header("Content-Encoding")?.lowercase()?.trim()
                    val decodedRead = try {
                        val stream = httpResponse.body?.byteStream()
                        if (stream == null) {
                            BoundedReadResult(ByteArray(0), false)
                        } else {
                            val decodeStream: InputStream = when (contentEncoding) {
                                "gzip" -> GZIPInputStream(stream)
                                "deflate" -> InflaterInputStream(stream)
                                else -> stream
                            }
                            decodeStream.use {
                                readAtMostBytes(it, MAX_FETCH_RESPONSE_BYTES)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read/decode response body for $url: ${e.message}")
                        BoundedReadResult(ByteArray(0), false)
                    }

                    val charset = bodyContentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val responseBody = decodeBodyToSafeString(decodedRead.bytes, charset)
                    val responseHeaders = mutableMapOf<String, String>()
                    httpResponse.headers.forEach { (name, value) ->
                        responseHeaders[name.lowercase()] = truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS)
                    }

                    val result = mapOf(
                        "ok" to httpResponse.isSuccessful,
                        "status" to httpResponse.code,
                        "statusText" to httpResponse.message,
                        "url" to httpResponse.request.url.toString(),
                        "body" to responseBody,
                        "headers" to responseHeaders,
                        "truncated" to decodedRead.truncated
                    )

                    Log.d(TAG, "Fetch result: ${httpResponse.code} ${httpResponse.message} url=$url bodyLen=${responseBody.length} bodyPreview=${responseBody.take(300)}")
                    gson.toJson(result)
                }
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}")
            gson.toJson(mapOf(
                "ok" to false,
                "status" to 0,
                "statusText" to (e.message ?: "Fetch failed"),
                "url" to url,
                "body" to "",
                "headers" to emptyMap<String, String>()
            ))
        }
    }

    private data class BoundedReadResult(
        val bytes: ByteArray,
        val truncated: Boolean
    )

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    private fun decodeBodyToSafeString(bytes: ByteArray, charset: java.nio.charset.Charset): String {
        val decoded = try {
            String(bytes, charset)
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
        return truncateString(decoded, MAX_FETCH_BODY_CHARS)
    }

    private fun readAtMostBytes(stream: InputStream, maxBytes: Int): BoundedReadResult {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        var truncated = false

        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        if (remaining == 0) {
            truncated = stream.read() != -1
        }
        return BoundedReadResult(out.toByteArray(), truncated)
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            gson.toJson(mapOf(
                "protocol" to "${url.protocol}:",
                "host" to if (url.port > 0) "${url.host}:${url.port}" else url.host,
                "hostname" to url.host,
                "port" to if (url.port > 0) url.port.toString() else "",
                "pathname" to (url.path ?: "/"),
                "search" to if (url.query != null) "?${url.query}" else "",
                "hash" to if (url.ref != null) "#${url.ref}" else ""
            ))
        } catch (e: Exception) {
            gson.toJson(mapOf(
                "protocol" to "",
                "host" to "",
                "hostname" to "",
                "port" to "",
                "pathname" to "/",
                "search" to "",
                "hash" to ""
            ))
        }
    }

    private fun getStaticPolyfillCode(): String {
        return """
            // Global constants (using globalThis to avoid redeclaration errors)
            globalThis.SCRAPER_ID = __get_scraper_id();
            globalThis.SCRAPER_SETTINGS = JSON.parse(__get_scraper_settings());
            if (typeof TMDB_API_KEY === 'undefined') {
                globalThis.TMDB_API_KEY = __get_tmdb_api_key();
            }
            if (typeof globalThis.global === 'undefined') {
                globalThis.global = globalThis;
            }
            if (typeof globalThis.window === 'undefined') {
                globalThis.window = globalThis;
            }
            if (typeof globalThis.self === 'undefined') {
                globalThis.self = globalThis;
            }

            // Fetch implementation (async)
            var fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body || '';
                var signal = options.signal || null;

                if (signal && signal.aborted) {
                    var preErr = new Error('The operation was aborted.');
                    preErr.name = 'AbortError';
                    throw preErr;
                }

                // Add default User-Agent
                if (!headers['User-Agent']) {
                    headers['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';
                }

                var result = __native_fetch(url, method, JSON.stringify(headers), body);
                var parsed = JSON.parse(result);

                if (signal && signal.aborted) {
                    var postErr = new Error('The operation was aborted.');
                    postErr.name = 'AbortError';
                    throw postErr;
                }

                return {
                    ok: parsed.ok,
                    status: parsed.status,
                    statusText: parsed.statusText,
                    url: parsed.url,
                    headers: {
                        get: function(name) {
                            return parsed.headers[name.toLowerCase()] || null;
                        }
                    },
                    text: function() {
                        return Promise.resolve(parsed.body);
                    },
                    json: function() {
                        
                        try {
                            if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                                return Promise.resolve(null);
                            }
                            return Promise.resolve(JSON.parse(parsed.body));
                        } catch (e) {
                            console.error('fetch.json parse error:', e && e.message ? e.message : e);
                            return Promise.resolve(null);
                        }
                    }
                };
            };

            // AbortController/AbortSignal minimal polyfill
            if (typeof AbortSignal === 'undefined') {
                var AbortSignal = function() {
                    this.aborted = false;
                    this.reason = undefined;
                    this._listeners = [];
                };
                AbortSignal.prototype.addEventListener = function(type, listener) {
                    if (type !== 'abort' || typeof listener !== 'function') return;
                    this._listeners.push(listener);
                };
                AbortSignal.prototype.removeEventListener = function(type, listener) {
                    if (type !== 'abort') return;
                    this._listeners = this._listeners.filter(function(l) { return l !== listener; });
                };
                AbortSignal.prototype.dispatchEvent = function(event) {
                    if (!event || event.type !== 'abort') return true;
                    for (var i = 0; i < this._listeners.length; i++) {
                        try { this._listeners[i].call(this, event); } catch (e) {}
                    }
                    return true;
                };
                globalThis.AbortSignal = AbortSignal;
            }
            if (typeof AbortController === 'undefined') {
                var AbortController = function() {
                    this.signal = new AbortSignal();
                };
                AbortController.prototype.abort = function(reason) {
                    if (this.signal.aborted) return;
                    this.signal.aborted = true;
                    this.signal.reason = reason;
                    this.signal.dispatchEvent({ type: 'abort' });
                };
                globalThis.AbortController = AbortController;
            }

            // atob/btoa polyfills
            if (typeof atob === 'undefined') {
                globalThis.atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input).replace(/=+$/, '');
                    if (str.length % 4 === 1) {
                        throw new Error('InvalidCharacterError');
                    }
                    var output = '';
                    var bc = 0, bs, buffer, idx = 0;
                    while ((buffer = str.charAt(idx++))) {
                        buffer = chars.indexOf(buffer);
                        if (buffer === -1) continue;
                        bs = bc % 4 ? bs * 64 + buffer : buffer;
                        if (bc++ % 4) {
                            output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                        }
                    }
                    return output;
                };
            }
            if (typeof btoa === 'undefined') {
                globalThis.btoa = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input);
                    var output = '';
                    for (
                        var block, charCode, idx = 0, map = chars;
                        str.charAt(idx | 0) || (map = '=', idx % 1);
                        output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))
                    ) {
                        charCode = str.charCodeAt(idx += 3 / 4);
                        if (charCode > 0xFF) {
                            throw new Error('InvalidCharacterError');
                        }
                        block = (block << 8) | charCode;
                    }
                    return output;
                };
            }

            // URL class
            var URL = function(urlString, base) {
                var fullUrl = urlString;
                if (base && !/^https?:\/\//i.test(urlString)) {
                    // Resolve relative URL against base
                    var b = typeof base === 'string' ? base : base.href;
                    if (urlString.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + urlString : urlString;
                    } else {
                        fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                    }
                }
                var parsed = __parse_url(fullUrl);
                var data = JSON.parse(parsed);
                this.href = fullUrl;
                this.protocol = data.protocol;
                this.host = data.host;
                this.hostname = data.hostname;
                this.port = data.port;
                this.pathname = data.pathname;
                this.search = data.search;
                this.hash = data.hash;
                this.origin = data.protocol + '//' + data.host;
                // Build searchParams from search string
                this.searchParams = new URLSearchParams(data.search || '');
            };
            URL.prototype.toString = function() { return this.href; };

            // URLSearchParams class
            var URLSearchParams = function(init) {
                this._params = {};
                var self = this;
                if (init && typeof init === 'object' && !Array.isArray(init)) {
                    Object.keys(init).forEach(function(key) {
                        self._params[key] = String(init[key]);
                    });
                } else if (typeof init === 'string') {
                    init.replace(/^\?/, '').split('&').forEach(function(pair) {
                        var parts = pair.split('=');
                        if (parts[0]) {
                            self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                        }
                    });
                }
            };
            URLSearchParams.prototype.toString = function() {
                var self = this;
                return Object.keys(this._params).map(function(key) {
                    return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
                }).join('&');
            };
            URLSearchParams.prototype.get = function(key) {
                return this._params.hasOwnProperty(key) ? this._params[key] : null;
            };
            URLSearchParams.prototype.set = function(key, value) {
                this._params[key] = String(value);
            };
            URLSearchParams.prototype.append = function(key, value) {
                this._params[key] = String(value);
            };
            URLSearchParams.prototype.has = function(key) {
                return this._params.hasOwnProperty(key);
            };
            URLSearchParams.prototype.delete = function(key) {
                delete this._params[key];
            };
            URLSearchParams.prototype.keys = function() {
                return Object.keys(this._params);
            };
            URLSearchParams.prototype.values = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return self._params[k]; });
            };
            URLSearchParams.prototype.entries = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
            };
            URLSearchParams.prototype.forEach = function(callback) {
                var self = this;
                Object.keys(this._params).forEach(function(key) {
                    callback(self._params[key], key, self);
                });
            };
            URLSearchParams.prototype.getAll = function(key) {
                return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
            };
            URLSearchParams.prototype.sort = function() {
                var sorted = {};
                var self = this;
                Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
                this._params = sorted;
            };

            // Cheerio implementation
            var cheerio = {
                load: function(html) {
                    var docId = __cheerio_load(html);

                    var $ = function(selector, context) {
                        // Handle $(wrapper) - return wrapper as-is
                        if (selector && selector._elementIds) {
                            return selector;
                        }
                        // Handle $(selector, context) pattern
                        if (context && context._elementIds && context._elementIds.length > 0) {
                            // Search within context element
                            var allIds = [];
                            for (var i = 0; i < context._elementIds.length; i++) {
                                var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                                var childIds = JSON.parse(childIdsJson);
                                allIds = allIds.concat(childIds);
                            }
                            return createCheerioWrapperFromIds(docId, allIds);
                        }
                        // Standard $(selector) call
                        return createCheerioWrapper(docId, selector);
                    };

                    $.html = function(el) {
                        if (el && el._elementIds && el._elementIds.length > 0) {
                            return __cheerio_html(docId, el._elementIds[0]);
                        }
                        return __cheerio_html(docId, '');
                    };

                    return $;
                }
            };

            function createCheerioWrapper(docId, selector) {
                var elementIds;
                if (typeof selector === 'string') {
                    var idsJson = __cheerio_select(docId, selector);
                    elementIds = JSON.parse(idsJson);
                } else {
                    elementIds = [];
                }

                var wrapper = {
                    _docId: docId,
                    _elementIds: elementIds,
                    length: elementIds.length,

                    each: function(callback) {
                        for (var i = 0; i < elementIds.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },

                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var childIdsJson = __cheerio_find(docId, elementIds[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },

                    text: function() {
                        if (elementIds.length === 0) return '';
                        return __cheerio_text(docId, elementIds.join(','));
                    },

                    html: function() {
                        if (elementIds.length === 0) return '';
                        return __cheerio_inner_html(docId, elementIds[0]);
                    },

                    attr: function(name) {
                        if (elementIds.length === 0) return undefined;
                        var val = __cheerio_attr(docId, elementIds[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },

                    first: function() {
                        return createCheerioWrapperFromIds(docId, elementIds.length > 0 ? [elementIds[0]] : []);
                    },

                    last: function() {
                        return createCheerioWrapperFromIds(docId, elementIds.length > 0 ? [elementIds[elementIds.length - 1]] : []);
                    },

                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var nextId = __cheerio_next(docId, elementIds[i]);
                            if (nextId && nextId !== '__NONE__') {
                                nextIds.push(nextId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },

                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var prevId = __cheerio_prev(docId, elementIds[i]);
                            if (prevId && prevId !== '__NONE__') {
                                prevIds.push(prevId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },

                    eq: function(index) {
                        if (index >= 0 && index < elementIds.length) {
                            return createCheerioWrapperFromIds(docId, [elementIds[index]]);
                        }
                        return createCheerioWrapperFromIds(docId, []);
                    },

                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < elementIds.length) {
                                return createCheerioWrapperFromIds(docId, [elementIds[index]]);
                            }
                            return undefined;
                        }
                        return elementIds.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    },

                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) {
                                results.push(result);
                            }
                        }
                        // Return object with get() for cheerio compatibility
                        return {
                            length: results.length,
                            get: function(index) {
                                if (typeof index === 'number') {
                                    return results[index];
                                }
                                return results;
                            },
                            toArray: function() {
                                return results;
                            }
                        };
                    },

                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < elementIds.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) {
                                    filteredIds.push(elementIds[i]);
                                }
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },

                    children: function(sel) {
                        return this.find(sel || '*');
                    },

                    parent: function() {
                        return createCheerioWrapperFromIds(docId, []);
                    },

                    toArray: function() {
                        return elementIds.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    }
                };

                return wrapper;
            }

            function createCheerioWrapperFromIds(docId, ids) {
                var wrapper = {
                    _docId: docId,
                    _elementIds: ids,
                    length: ids.length,

                    each: function(callback) {
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },

                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var childIdsJson = __cheerio_find(docId, ids[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },

                    text: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_text(docId, ids.join(','));
                    },

                    html: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_inner_html(docId, ids[0]);
                    },

                    attr: function(name) {
                        if (ids.length === 0) return undefined;
                        var val = __cheerio_attr(docId, ids[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },

                    first: function() {
                        return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []);
                    },

                    last: function() {
                        return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []);
                    },

                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var nextId = __cheerio_next(docId, ids[i]);
                            if (nextId && nextId !== '__NONE__') {
                                nextIds.push(nextId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },

                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var prevId = __cheerio_prev(docId, ids[i]);
                            if (prevId && prevId !== '__NONE__') {
                                prevIds.push(prevId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },

                    eq: function(index) {
                        if (index >= 0 && index < ids.length) {
                            return createCheerioWrapperFromIds(docId, [ids[index]]);
                        }
                        return createCheerioWrapperFromIds(docId, []);
                    },

                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < ids.length) {
                                return createCheerioWrapperFromIds(docId, [ids[index]]);
                            }
                            return undefined;
                        }
                        return ids.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    },

                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) {
                                results.push(result);
                            }
                        }
                        // Return object with get() for cheerio compatibility
                        return {
                            length: results.length,
                            get: function(index) {
                                if (typeof index === 'number') {
                                    return results[index];
                                }
                                return results;
                            },
                            toArray: function() {
                                return results;
                            }
                        };
                    },

                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < ids.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) {
                                    filteredIds.push(ids[i]);
                                }
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },

                    children: function(sel) {
                        return this.find(sel || '*');
                    },

                    parent: function() {
                        return createCheerioWrapperFromIds(docId, []);
                    },

                    toArray: function() {
                        return ids.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    }
                };

                return wrapper;
            }

            // Require function for CommonJS modules
            var require = function(moduleName) {
                if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                    return cheerio;
                }
                if (moduleName === 'crypto-js') {
                    if (globalThis.CryptoJS) return globalThis.CryptoJS;
                    throw new Error("Module 'crypto-js' failed to load");
                }
                throw new Error("Module '" + moduleName + "' is not available");
            };

            // Array.prototype.flat polyfill
            if (!Array.prototype.flat) {
                Array.prototype.flat = function(depth) {
                    depth = depth === undefined ? 1 : Math.floor(depth);
                    if (depth < 1) return Array.prototype.slice.call(this);
                    return (function flatten(arr, d) {
                        return d > 0
                            ? arr.reduce(function(acc, val) {
                                return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val);
                            }, [])
                            : arr.slice();
                    })(this, depth);
                };
            }

            // Array.prototype.flatMap polyfill
            if (!Array.prototype.flatMap) {
                Array.prototype.flatMap = function(callback, thisArg) {
                    return this.map(callback, thisArg).flat();
                };
            }

            // Object.entries polyfill
            if (!Object.entries) {
                Object.entries = function(obj) {
                    var result = [];
                    for (var key in obj) {
                        if (obj.hasOwnProperty(key)) {
                            result.push([key, obj[key]]);
                        }
                    }
                    return result;
                };
            }

            // Object.fromEntries polyfill
            if (!Object.fromEntries) {
                Object.fromEntries = function(entries) {
                    var result = {};
                    for (var i = 0; i < entries.length; i++) {
                        result[entries[i][0]] = entries[i][1];
                    }
                    return result;
                };
            }

            // String.prototype.replaceAll polyfill
            if (!String.prototype.replaceAll) {
                String.prototype.replaceAll = function(search, replace) {
                    if (search instanceof RegExp) {
                        if (!search.global) {
                            throw new TypeError('replaceAll must be called with a global RegExp');
                        }
                        return this.replace(search, replace);
                    }
                    return this.split(search).join(replace);
                };
            }

            ${getCryptoPolyfillCode()}
            ${getTextEncoderPolyfillCode()}
        """.trimIndent()
    }

    private fun getCryptoPolyfillCode(): String {
        val d = '$'
        return """
            var WordArray = {
                init: function(words, sigBytes) {
                    this.words = words || [];
                    this.sigBytes = sigBytes != undefined ? sigBytes : this.words.length * 4;
                },
                toString: function(encoder) {
                    return (encoder || CryptoJS.enc.Hex).stringify(this);
                },
                concat: function(wordArray) {
                    var thisWords = this.words;
                    var thatWords = wordArray.words;
                    var thisSigBytes = this.sigBytes;
                    var thatSigBytes = wordArray.sigBytes;

                    this.clamp();

                    for (var i = 0; i < thatSigBytes; i++) {
                        var thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                        thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8);
                    }
                    this.sigBytes += thatSigBytes;
                    return this;
                },
                clamp: function() {
                    var words = this.words;
                    var sigBytes = this.sigBytes;
                    if (sigBytes % 4) {
                        words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8);
                    }
                    words.length = Math.ceil(sigBytes / 4);
                    return this;
                },
                clone: function() {
                    return __wordArrayCreate(this.words.slice(0), this.sigBytes);
                }
            };

            function __wordArrayCreate(words, sigBytes) {
                var wa = Object.create(WordArray);
                wa.init(words, sigBytes);
                return wa;
            }

            function __isWordArray(value) {
                return value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number';
            }

            function __copyUint8Array(bytes) {
                bytes = __toUint8Array(bytes);
                var copy = new Uint8Array(bytes.length);
                copy.set(bytes);
                return copy;
            }

            function __toUint8Array(data) {
                if (!data) return new Uint8Array(0);
                if (data instanceof Uint8Array) return data;
                if (data instanceof ArrayBuffer) return new Uint8Array(data);
                if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(data)) {
                    return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
                }
                if (Array.isArray(data)) return new Uint8Array(data);
                if (typeof data.length === 'number') return new Uint8Array(Array.prototype.slice.call(data));
                return new Uint8Array(0);
            }

            function __bytesToArrayBuffer(bytes) {
                return __copyUint8Array(bytes).buffer;
            }

            function __wordArrayToBytes(wordArray) {
                if (!__isWordArray(wordArray)) return typeof wordArray === 'string' ? new TextEncoder().encode(wordArray) : __toUint8Array(wordArray);
                var bytes = new Uint8Array(wordArray.sigBytes);
                for (var i = 0; i < wordArray.sigBytes; i++) {
                    bytes[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                }
                return bytes;
            }

            function __bytesToWordArray(bytes) {
                bytes = __toUint8Array(bytes);
                var words = [];
                for (var i = 0; i < bytes.length; i++) {
                    words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
                }
                return __wordArrayCreate(words, bytes.length);
            }

            function __normalizeWordArrayInput(value) {
                if (__isWordArray(value)) return __wordArrayToBytes(value);
                if (typeof value === 'string') return new TextEncoder().encode(value);
                return __toUint8Array(value);
            }

            function __bytesToHex(bytes) {
                bytes = __toUint8Array(bytes);
                var out = [];
                for (var i = 0; i < bytes.length; i++) {
                    var hex = bytes[i].toString(16);
                    out.push(hex.length < 2 ? '0' + hex : hex);
                }
                return out.join('');
            }

            function __hexToBytes(hex) {
                hex = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
                if (hex.length % 2) hex = '0' + hex;
                var bytes = new Uint8Array(hex.length / 2);
                for (var i = 0; i < hex.length; i += 2) {
                    bytes[i / 2] = parseInt(hex.substr(i, 2), 16) & 0xff;
                }
                return bytes;
            }

            function __concatBytes() {
                var total = 0;
                var parts = [];
                for (var i = 0; i < arguments.length; i++) {
                    var part = __toUint8Array(arguments[i]);
                    parts.push(part);
                    total += part.length;
                }
                var out = new Uint8Array(total);
                var offset = 0;
                for (var j = 0; j < parts.length; j++) {
                    out.set(parts[j], offset);
                    offset += parts[j].length;
                }
                return out;
            }

            function __normalizeHashName(hash) {
                var name = hash && hash.name ? hash.name : hash;
                name = String(name || 'SHA-256').toUpperCase().replace(/[^A-Z0-9]/g, '');
                if (name === 'SHA1' || name === 'SHA256' || name === 'SHA384' || name === 'SHA512' || name === 'MD5') return name;
                throw new Error('Unsupported hash algorithm: ' + name);
            }

            function __normalizeAlgorithmName(algo) {
                var name = algo && algo.name ? algo.name : algo;
                name = String(name || '').toUpperCase();
                if (name.indexOf('AES-GCM') >= 0) return 'AES-GCM';
                if (name.indexOf('AES-CBC') >= 0) return 'AES-CBC';
                if (name.indexOf('AES-ECB') >= 0 || name === 'ECB') return 'AES-ECB';
                if (name.indexOf('PBKDF2') >= 0) return 'PBKDF2';
                if (name.indexOf('HMAC') >= 0) return 'HMAC';
                if (name.indexOf('RSASSA-PKCS1') >= 0) return 'RSASSA-PKCS1-V1_5';
                if (name.indexOf('ECDSA') >= 0) return 'ECDSA';
                return name;
            }

            function __aesModeName(mode, padding) {
                var normalized = __normalizeAlgorithmName(mode || 'AES-CBC');
                if (padding === CryptoJS.pad.NoPadding || padding === 'NoPadding') normalized += '-NoPadding';
                return normalized;
            }

            function __nativeDigestBytes(hash, dataBytes) {
                if (typeof __crypto_digest_hex_raw === 'undefined') throw new Error('Native digest bridge is unavailable');
                return __hexToBytes(__crypto_digest_hex_raw(__normalizeHashName(hash), __bytesToHex(dataBytes)));
            }

            function __nativeHmacBytes(hash, keyBytes, dataBytes) {
                if (typeof __crypto_hmac_hex_raw === 'undefined') throw new Error('Native HMAC bridge is unavailable');
                return __hexToBytes(__crypto_hmac_hex_raw(__normalizeHashName(hash), __bytesToHex(keyBytes), __bytesToHex(dataBytes)));
            }

            function __nativePbkdf2Bytes(passwordBytes, saltBytes, iterations, keySizeBits, hash) {
                if (typeof __crypto_pbkdf2_hex === 'undefined') throw new Error('Native PBKDF2 bridge is unavailable');
                return __hexToBytes(__crypto_pbkdf2_hex(__bytesToHex(passwordBytes), __bytesToHex(saltBytes), iterations, keySizeBits, __normalizeHashName(hash)));
            }

            function __nativeAesBytes(encrypt, mode, keyBytes, ivBytes, dataBytes) {
                var fn = encrypt ? __crypto_aes_encrypt_hex : __crypto_aes_decrypt_hex;
                if (typeof fn === 'undefined') throw new Error('Native AES bridge is unavailable');
                return __hexToBytes(fn(mode, __bytesToHex(keyBytes), __bytesToHex(ivBytes), __bytesToHex(dataBytes)));
            }

            function __evpKdf(passwordBytes, saltBytes, keySizeBytes, ivSizeBytes) {
                var targetSize = keySizeBytes + ivSizeBytes;
                var derived = new Uint8Array(targetSize);
                var block = new Uint8Array(0);
                var offset = 0;
                while (offset < targetSize) {
                    block = __nativeDigestBytes('MD5', __concatBytes(block, passwordBytes, saltBytes || new Uint8Array(0)));
                    var take = Math.min(block.length, targetSize - offset);
                    derived.set(block.subarray(0, take), offset);
                    offset += take;
                }
                return {
                    key: derived.subarray(0, keySizeBytes),
                    iv: derived.subarray(keySizeBytes, keySizeBytes + ivSizeBytes)
                };
            }

            function __opensslSaltHeader() {
                return new Uint8Array([83, 97, 108, 116, 101, 100, 95, 95]);
            }

            function __hasOpenSslSaltHeader(bytes) {
                var header = __opensslSaltHeader();
                if (!bytes || bytes.length < 16) return false;
                for (var i = 0; i < header.length; i++) {
                    if (bytes[i] !== header[i]) return false;
                }
                return true;
            }

            function __makeCipherParams(ciphertext, key, iv, salt, mode) {
                return {
                    ciphertext: __bytesToWordArray(ciphertext),
                    key: key ? __bytesToWordArray(key) : undefined,
                    iv: iv ? __bytesToWordArray(iv) : undefined,
                    salt: salt ? __bytesToWordArray(salt) : undefined,
                    mode: mode,
                    toString: function(formatter) {
                        return (formatter || CryptoJS.format.OpenSSL).stringify(this);
                    }
                };
            }

            var CryptoJS = {
                enc: {
                    Hex: {
                        stringify: function(wordArray) {
                            return __bytesToHex(__wordArrayToBytes(wordArray));
                        },
                        parse: function(hexStr) {
                            return __bytesToWordArray(__hexToBytes(hexStr));
                        }
                    },
                    Utf8: {
                        stringify: function(wordArray) {
                            return new TextDecoder('utf-8').decode(__wordArrayToBytes(wordArray));
                        },
                        parse: function(utf8Str) {
                            return __bytesToWordArray(new TextEncoder().encode(String(utf8Str)));
                        }
                    },
                    Latin1: {
                        stringify: function(wordArray) {
                            var bytes = __wordArrayToBytes(wordArray);
                            var out = '';
                            for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                            return out;
                        },
                        parse: function(str) {
                            str = String(str || '');
                            var bytes = new Uint8Array(str.length);
                            for (var i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
                            return __bytesToWordArray(bytes);
                        }
                    },
                    Base64: {
                        stringify: function(wordArray) {
                            var bytes = __wordArrayToBytes(wordArray);
                            var binaryStr = '';
                            for (var j = 0; j < bytes.length; j++) binaryStr += String.fromCharCode(bytes[j]);
                            return btoa(binaryStr);
                        },
                        parse: function(base64Str) {
                            var binaryStr = atob(String(base64Str || ''));
                            var bytes = new Uint8Array(binaryStr.length);
                            for (var i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i) & 0xff;
                            return __bytesToWordArray(bytes);
                        }
                    },
                    Base64url: {
                        stringify: function(wordArray) {
                            return CryptoJS.enc.Base64.stringify(wordArray).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${d}/g, '');
                        },
                        parse: function(str) {
                            str = String(str || '').replace(/-/g, '+').replace(/_/g, '/');
                            while (str.length % 4) str += '=';
                            return CryptoJS.enc.Base64.parse(str);
                        }
                    }
                },
                lib: {
                    WordArray: {
                        create: function(words, sigBytes) {
                            if (words == null) return __wordArrayCreate([], sigBytes || 0);
                            if (__isWordArray(words)) return words.clone();
                            if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                            if (words instanceof ArrayBuffer || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(words))) {
                                var bytes = __toUint8Array(words);
                                return __bytesToWordArray(sigBytes != undefined ? bytes.subarray(0, sigBytes) : bytes);
                            }
                            return __wordArrayCreate(words, sigBytes);
                        },
                        random: function(nBytes) {
                            var bytes = new Uint8Array(nBytes || 0);
                            globalThis.crypto.getRandomValues(bytes);
                            return __bytesToWordArray(bytes);
                        }
                    },
                    CipherParams: {
                        create: function(params) {
                            params = params || {};
                            params.toString = params.toString || function(formatter) {
                                return (formatter || CryptoJS.format.OpenSSL).stringify(this);
                            };
                            return params;
                        }
                    }
                },
                format: {
                    OpenSSL: {
                        stringify: function(cipherParams) {
                            var cipherBytes = __wordArrayToBytes(cipherParams.ciphertext);
                            var out = cipherParams.salt
                                ? __concatBytes(__opensslSaltHeader(), __wordArrayToBytes(cipherParams.salt), cipherBytes)
                                : cipherBytes;
                            return CryptoJS.enc.Base64.stringify(__bytesToWordArray(out));
                        },
                        parse: function(str) {
                            var bytes = __wordArrayToBytes(CryptoJS.enc.Base64.parse(str));
                            if (__hasOpenSslSaltHeader(bytes)) {
                                return CryptoJS.lib.CipherParams.create({
                                    salt: __bytesToWordArray(bytes.subarray(8, 16)),
                                    ciphertext: __bytesToWordArray(bytes.subarray(16))
                                });
                            }
                            return CryptoJS.lib.CipherParams.create({ ciphertext: __bytesToWordArray(bytes) });
                        }
                    }
                },
                mode: { CBC: 'AES-CBC', GCM: 'AES-GCM', ECB: 'AES-ECB' },
                pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding' },
                algo: { MD5: 'MD5', SHA1: 'SHA1', SHA256: 'SHA256', SHA384: 'SHA384', SHA512: 'SHA512', AES: 'AES' },
                MD5: function(m) { return __bytesToWordArray(__nativeDigestBytes('MD5', __normalizeWordArrayInput(m))); },
                SHA1: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA1', __normalizeWordArrayInput(m))); },
                SHA256: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA256', __normalizeWordArrayInput(m))); },
                SHA384: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA384', __normalizeWordArrayInput(m))); },
                SHA512: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA512', __normalizeWordArrayInput(m))); },
                HmacMD5: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('MD5', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
                HmacSHA1: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA1', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
                HmacSHA256: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA256', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
                HmacSHA384: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA384', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
                HmacSHA512: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA512', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
                PBKDF2: function(pass, salt, options) {
                    options = options || {};
                    var pBytes = __normalizeWordArrayInput(pass);
                    var sBytes = __normalizeWordArrayInput(salt);
                    var iter = options.iterations || 1000;
                    var kSize = options.keySize || 8;
                    var algo = options.hasher || 'SHA1';
                    return __bytesToWordArray(__nativePbkdf2Bytes(pBytes, sBytes, iter, kSize * 32, algo));
                },
                AES: {
                    encrypt: function(message, key, options) {
                        options = options || {};
                        var data = __normalizeWordArrayInput(message);
                        var kBytes;
                        var ivBytes;
                        var saltBytes;
                        var isPassphrase = typeof key === 'string';
                        if (isPassphrase) {
                            saltBytes = options.salt ? __wordArrayToBytes(options.salt) : __wordArrayToBytes(CryptoJS.lib.WordArray.random(8));
                            var derived = __evpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                            kBytes = derived.key;
                            ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
                        } else {
                            kBytes = __wordArrayToBytes(key);
                            ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
                        }
                        var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
                        var resBytes = __nativeAesBytes(true, mode, kBytes, ivBytes, data);
                        return __makeCipherParams(resBytes, kBytes, ivBytes, saltBytes, mode);
                    },
                    decrypt: function(cipher, key, options) {
                        options = options || {};
                        var cipherParams = typeof cipher === 'string' ? CryptoJS.format.OpenSSL.parse(cipher) : cipher;
                        var data = cipherParams.ciphertext ? __wordArrayToBytes(cipherParams.ciphertext) : __toUint8Array(cipherParams);
                        var kBytes;
                        var ivBytes;
                        var isPassphrase = typeof key === 'string';
                        if (isPassphrase) {
                            var saltBytes = options.salt ? __wordArrayToBytes(options.salt) : (cipherParams.salt ? __wordArrayToBytes(cipherParams.salt) : new Uint8Array(0));
                            var derived = __evpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                            kBytes = derived.key;
                            ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
                        } else {
                            kBytes = __wordArrayToBytes(key);
                            ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
                        }
                        var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
                        return __bytesToWordArray(__nativeAesBytes(false, mode, kBytes, ivBytes, data));
                    }
                }
            };
            globalThis.CryptoJS = CryptoJS;

            function __makeCryptoKey(type, algorithm, extractable, usages, rawBytes) {
                return {
                    type: type,
                    extractable: !!extractable,
                    algorithm: algorithm,
                    usages: usages || [],
                    _raw: __copyUint8Array(rawBytes)
                };
            }

            function __webCryptoAlgorithm(algo) {
                var name = __normalizeAlgorithmName(algo);
                var out = { name: name };
                if (algo && typeof algo === 'object' && algo.length) out.length = algo.length;
                if (algo && typeof algo === 'object' && algo.hash) out.hash = { name: __normalizeHashName(algo.hash) };
                return out;
            }

            function __signatureAlgorithmName(algo, key) {
                var name = __normalizeAlgorithmName(algo || (key && key.algorithm));
                var hash = algo && algo.hash ? __normalizeHashName(algo.hash) : (key && key.algorithm && key.algorithm.hash ? key.algorithm.hash.name : 'SHA256');
                if (name === 'RSASSA-PKCS1-V1_5') return 'RSASSA-PKCS1-V1_5-' + hash;
                if (name === 'ECDSA') return 'ECDSA-' + hash;
                return name;
            }

            globalThis.crypto = {
                subtle: {
                    digest: async function(algo, data) {
                        return __bytesToArrayBuffer(__nativeDigestBytes(algo, __toUint8Array(data)));
                    },
                    importKey: async function(fmt, data, algo, extractable, usages) {
                        fmt = String(fmt || 'raw').toLowerCase();
                        if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
                        var algorithm = __webCryptoAlgorithm(algo || {});
                        var type = fmt === 'spki' ? 'public' : (fmt === 'pkcs8' ? 'private' : 'secret');
                        return __makeCryptoKey(type, algorithm, extractable, usages || [], __toUint8Array(data));
                    },
                    exportKey: async function(fmt, key) {
                        fmt = String(fmt || 'raw').toLowerCase();
                        if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
                        return __bytesToArrayBuffer(key._raw);
                    },
                    generateKey: async function(algo, extractable, usages) {
                        var algorithm = __webCryptoAlgorithm(algo || {});
                        if (algorithm.name !== 'AES-CBC' && algorithm.name !== 'AES-GCM' && algorithm.name !== 'HMAC') {
                            throw new Error('Unsupported generateKey algorithm: ' + algorithm.name);
                        }
                        var length = algorithm.length || 256;
                        var bytes = new Uint8Array(length / 8);
                        globalThis.crypto.getRandomValues(bytes);
                        return __makeCryptoKey('secret', algorithm, extractable, usages || [], bytes);
                    },
                    deriveBits: async function(params, key, len) {
                        if (__normalizeAlgorithmName(params) !== 'PBKDF2') throw new Error('Only PBKDF2 deriveBits is supported');
                        var pBytes = __toUint8Array(key._raw);
                        var sBytes = __toUint8Array(params.salt);
                        var hash = params.hash || 'SHA-256';
                        return __bytesToArrayBuffer(__nativePbkdf2Bytes(pBytes, sBytes, params.iterations || 1000, len, hash));
                    },
                    deriveKey: async function(params, key, derivedKeyAlgo, extractable, usages) {
                        var algorithm = __webCryptoAlgorithm(derivedKeyAlgo || {});
                        var length = algorithm.length || 256;
                        var raw = await globalThis.crypto.subtle.deriveBits(params, key, length);
                        return __makeCryptoKey('secret', algorithm, extractable, usages || [], new Uint8Array(raw));
                    },
                    encrypt: async function(params, key, data) {
                        var mode = __normalizeAlgorithmName(params);
                        if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported encrypt algorithm: ' + mode);
                        if (mode === 'AES-GCM' && params.tagLength && params.tagLength !== 128) throw new Error('Only 128-bit AES-GCM tags are supported');
                        if (mode === 'AES-GCM' && params.additionalData) throw new Error('AES-GCM additionalData is not supported');
                        var ivBytes = __toUint8Array(params.iv || new Uint8Array(0));
                        return __bytesToArrayBuffer(__nativeAesBytes(true, mode, __toUint8Array(key._raw), ivBytes, __toUint8Array(data)));
                    },
                    decrypt: async function(params, key, data) {
                        var mode = __normalizeAlgorithmName(params);
                        if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported decrypt algorithm: ' + mode);
                        if (mode === 'AES-GCM' && params.tagLength && params.tagLength !== 128) throw new Error('Only 128-bit AES-GCM tags are supported');
                        if (mode === 'AES-GCM' && params.additionalData) throw new Error('AES-GCM additionalData is not supported');
                        var ivBytes = __toUint8Array(params.iv || new Uint8Array(0));
                        return __bytesToArrayBuffer(__nativeAesBytes(false, mode, __toUint8Array(key._raw), ivBytes, __toUint8Array(data)));
                    },
                    sign: async function(algo, key, data) {
                        if (__normalizeAlgorithmName(algo || key.algorithm) === 'HMAC' || key.algorithm.name === 'HMAC') {
                            var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
                            return __bytesToArrayBuffer(__nativeHmacBytes(hash, __toUint8Array(key._raw), __toUint8Array(data)));
                        }
                        if (typeof __crypto_sign_hex === 'undefined') throw new Error('Native signature bridge is unavailable');
                        var sigHex = __crypto_sign_hex(__signatureAlgorithmName(algo, key), __bytesToHex(key._raw), __bytesToHex(__toUint8Array(data)));
                        return __bytesToArrayBuffer(__hexToBytes(sigHex));
                    },
                    verify: async function(algo, key, sig, data) {
                        if (__normalizeAlgorithmName(algo || key.algorithm) === 'HMAC' || key.algorithm.name === 'HMAC') {
                            var expected = __nativeHmacBytes((algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256', __toUint8Array(key._raw), __toUint8Array(data));
                            var actual = __toUint8Array(sig);
                            if (expected.length !== actual.length) return false;
                            var diff = 0;
                            for (var i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
                            return diff === 0;
                        }
                        if (typeof __crypto_verify_hex === 'undefined') throw new Error('Native signature bridge is unavailable');
                        return __crypto_verify_hex(__signatureAlgorithmName(algo, key), __bytesToHex(key._raw), __bytesToHex(__toUint8Array(sig)), __bytesToHex(__toUint8Array(data)));
                    }
                },
                getRandomValues: function(arr) {
                    if (!arr) return arr;
                    var byteLength = arr.byteLength != undefined ? arr.byteLength : arr.length;
                    if (!byteLength) return arr;
                    if (typeof __crypto_get_random_values_hex === 'undefined') throw new Error('Native random bridge is unavailable');
                    var random = __hexToBytes(__crypto_get_random_values_hex(byteLength));
                    if (arr.buffer && arr.byteLength != undefined) {
                        new Uint8Array(arr.buffer, arr.byteOffset || 0, arr.byteLength).set(random);
                    } else {
                        for (var i = 0; i < arr.length; i++) arr[i] = random[i] || 0;
                    }
                    return arr;
                },
                randomUUID: function() {
                    var b = new Uint8Array(16);
                    globalThis.crypto.getRandomValues(b);
                    b[6] = (b[6] & 0x0f) | 0x40;
                    b[8] = (b[8] & 0x3f) | 0x80;
                    var h = __bytesToHex(b);
                    return h.substr(0, 8) + '-' + h.substr(8, 4) + '-' + h.substr(12, 4) + '-' + h.substr(16, 4) + '-' + h.substr(20);
                }
            };
        """.trimIndent()
    }

    private fun getTextEncoderPolyfillCode(): String {
        return """
            if (typeof TextEncoder === 'undefined') {
                globalThis.TextEncoder = function() {};
                TextEncoder.prototype.encode = function(str) {
                    var hex = __crypto_utf8_to_hex(str);
                    var bytes = new Uint8Array(hex.length / 2);
                    for (var i = 0; i < hex.length; i += 2) {
                        bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
                    }
                    return bytes;
                };
            }
            if (typeof TextDecoder === 'undefined') {
                globalThis.TextDecoder = function() {};
                TextDecoder.prototype.decode = function(data) {
                    var bytes = data;
                    if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
                    var hex = '';
                    for (var i = 0; i < bytes.length; i++) {
                        hex += bytes[i].toString(16).padStart(2, '0');
                    }
                    return __crypto_hex_to_utf8(hex);
                };
            }
        """.trimIndent()
    }
    }

    private fun parseJsonResults(json: String): List<LocalScraperResult> {
        return try {
            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
            val results: List<Map<String, Any?>>? = gson.fromJson(json, listType)
            results?.mapNotNull { item ->
                // Handle URL - could be string or object with url property
                val urlValue = item["url"]
                val url = when (urlValue) {
                    is String -> urlValue.takeIf { it.isNotBlank() && !it.contains("[object") }
                    is Map<*, *> -> (urlValue["url"] as? String)?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null
                
                // Parse headers if present
                val headersValue = item["headers"]
                val headers: Map<String, String>? = when (headersValue) {
                    is Map<*, *> -> headersValue.entries
                        .filter { it.key is String && it.value is String }
                        .associate { (it.key as String) to (it.value as String) }
                        .takeIf { it.isNotEmpty() }
                    else -> null
                }
                
                // Parse subtitles if present
                val subtitlesValue = item["subtitles"]
                val subtitles: List<com.nuvio.tv.domain.model.StreamSubtitle>? = when (subtitlesValue) {
                    is List<*> -> subtitlesValue.mapNotNull { sub ->
                        val subMap = sub as? Map<*, *> ?: return@mapNotNull null
                        val subUrl = subMap["url"]?.toString() ?: return@mapNotNull null
                        val subLang = subMap["language"]?.toString() ?: subMap["lang"]?.toString() ?: "Unknown"
                        val subName = subMap["name"]?.toString()
                        val subHeaders = (subMap["headers"] as? Map<*, *>)?.entries
                            ?.filter { it.key is String && it.value is String }
                            ?.associate { (it.key as String) to (it.value as String) }
                        com.nuvio.tv.domain.model.StreamSubtitle(
                            url = subUrl,
                            language = subLang,
                            name = subName,
                            headers = subHeaders
                        )
                    }.takeIf { it.isNotEmpty() }
                    else -> null
                }
                
                LocalScraperResult(
                    title = item["title"]?.toString()?.takeIf { !it.contains("[object") } 
                        ?: item["name"]?.toString()?.takeIf { !it.contains("[object") } 
                        ?: "Unknown",
                    name = item["name"]?.toString()?.takeIf { !it.contains("[object") },
                    url = url,
                    quality = item["quality"]?.toString()?.takeIf { !it.contains("[object") },
                    size = item["size"]?.toString()?.takeIf { !it.contains("[object") },
                    language = item["language"]?.toString()?.takeIf { !it.contains("[object") },
                    provider = item["provider"]?.toString()?.takeIf { !it.contains("[object") },
                    type = item["type"]?.toString()?.takeIf { !it.contains("[object") },
                    seeders = (item["seeders"] as? Number)?.toInt(),
                    peers = (item["peers"] as? Number)?.toInt(),
                    infoHash = item["infoHash"]?.toString()?.takeIf { !it.contains("[object") },
                    headers = headers,
                    subtitles = subtitles
                )
            }?.filter { it.url.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse results: ${e.message}")
            emptyList()
        }
    }
}
