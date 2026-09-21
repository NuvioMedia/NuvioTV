package com.nuvio.tv.ui.screens.player.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SubtitleTranslation"

private val RTL_LANGUAGES = setOf("hebrew", "arabic", "urdu", "persian", "farsi", "yiddish")

data class TranslationResult(
    val lines: List<String>,
    val success: Boolean,
    val errorMessage: String? = null
)

// Marker error for Gemini's non-configurable content-policy rejections (finishReason
// PROHIBITED_CONTENT etc. — safetySettings BLOCK_NONE does NOT cover these). Blocked batches are
// bisected so one flagged line doesn't lose the whole window, and the caller must not toast it:
// it's not actionable and translation keeps working for everything else.
const val TRANSLATION_ERROR_CONTENT_BLOCKED = "CONTENT_BLOCKED"

private val BLOCKED_FINISH_REASONS = setOf(
    "PROHIBITED_CONTENT", "SAFETY", "RECITATION", "BLOCKLIST", "SPII"
)

// llama-3.3-70b-versatile was decommissioned by Groq (August 16, 2026). gpt-oss-120b is Groq's
// recommended replacement: ~500 tok/s (vs 280), same 30 RPM free tier, 2x the daily token budget.
// It is a reasoning model — see GROQ_REASONING_EFFORT below.
private const val GROQ_MODEL_ID = "openai/gpt-oss-120b"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
// gpt-oss cannot disable reasoning entirely (unlike Gemini's thinkingLevel=minimal or Qwen's
// reasoning_effort=none) — "low" is the floor. Anything higher spends seconds of thinking on what
// is a mechanical translation, which subtitles can't wait for. "hidden" keeps the chain-of-thought
// out of message.content so extractJsonArray() doesn't have to parse around it.
private const val GROQ_REASONING_EFFORT = "low"
private const val GROQ_REASONING_FORMAT = "hidden"
// gemini-2.5-flash was retired by Google (HTTP 404 "no longer available", July 2026).
// gemini-3.5-flash-lite: ~2x throughput (350 vs 165 tok/s) and ~3.5x cheaper than 3.5-flash,
// positioned by Google for high-volume translation. Same v1beta API + thinkingLevel field.
private const val GEMINI_MODEL_ID = "gemini-3.5-flash-lite"
private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL_ID:generateContent"

// Transient-failure retry (server overload / flaky network). 503/500/502 and IOExceptions are
// Google-side capacity blips that usually clear within ~1s; retry the same batch a couple times
// with short exponential backoff before falling back to English. Kept short so subtitles stay in
// sync with playback. NOT applied to 429 (that's our own quota — retrying fast makes it worse;
// the manager's 5s cooldown handles it) or 4xx/content blocks (retrying won't change the outcome).
private const val GEMINI_MAX_TRANSIENT_RETRIES = 2        // 3 attempts total
private const val GEMINI_RETRY_BASE_DELAY_MS = 400L       // 400ms, then 800ms (×2 each retry)
private val GEMINI_RETRYABLE_HTTP = setOf(500, 502, 503)

class SubtitleTranslationService(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> SubtitleAiModel = { SubtitleAiModel.GROQ_LLAMA_70B }
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun extractJsonArray(text: String): JSONArray? {
        val codeBlocks = Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(text)
            .map { it.groupValues[1].trim() }.toList().reversed()
        val stripped = text.replace(Regex("```[^`]*```"), "").trim()
        val candidates = codeBlocks + listOf(stripped, text)

        for (candidate in candidates) {
            try { return JSONArray(candidate) } catch (_: Exception) {}
            // With responseMimeType=application/json the model sometimes wraps the array in an
            // object (e.g. {"translations": [...]}) — unwrap a lone array-valued field.
            try {
                val obj = JSONObject(candidate)
                val arrays = obj.keys().asSequence()
                    .mapNotNull { key -> obj.optJSONArray(key) }
                    .toList()
                if (arrays.size == 1) return arrays[0]
            } catch (_: Exception) {}
            val start = candidate.indexOf('[')
            val end = candidate.lastIndexOf(']')
            if (start < 0 || end <= start) {
                repairTruncatedArray(candidate)?.let { return it }
                continue
            }
            try {
                return JSONArray(candidate.substring(start, end + 1))
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Gemini's JSON mode intermittently truncates output at the very tail (finishReason STOP but
     * the closing bracket — sometimes a trailing element — is missing). The translations
     * themselves are intact, so re-terminate the array instead of failing the whole batch.
     */
    private fun repairTruncatedArray(text: String): JSONArray? {
        val t = text.trim()
        if (!t.startsWith("[") || t.endsWith("]")) return null
        val lastQuote = t.lastIndexOf('"')
        if (lastQuote <= 0) return null
        // Try closing as-is, then after dropping a trailing partial element.
        for (cut in listOf(t, t.substring(0, lastQuote + 1))) {
            val trimmed = cut.trimEnd().trimEnd(',')
            try { return JSONArray("$trimmed]") } catch (_: Exception) {}
            try { return JSONArray("$trimmed\"]") } catch (_: Exception) {}
        }
        return null
    }

    private fun buildSystemPrompt(targetLanguage: String, NL: String) =
        "You are a professional subtitle translator. Translate the following JSON array into natural $targetLanguage.\n" +
        "Rules:\n" +
        "1. Return ONLY a valid JSON array of strings.\n" +
        "2. Every input element starts with a numeric prefix like '7: ' — keep the EXACT same numeric prefix on the corresponding translated element. Never merge or split elements.\n" +
        "3. Keep the exact same order and element count.\n" +
        "4. Preserve the '$NL' symbol exactly where it appears as a line break.\n" +
        "5. Use informal, spoken $targetLanguage suitable for cinema."

    // The numeric prefix each translated element must carry back ("7: text" / tolerant of
    // "7. text", "7 - text"). Alignment by index instead of array position: the model merging or
    // splitting one subtitle line then costs only that line, not the whole window (line-count
    // mismatches — 40→39, 19→20 — were the dominant translation failure on gemini-3.5-flash).
    private val indexPrefixRegex = Regex("""^\s*(\d+)\s*[:.\-]\s*""")

    private fun encodeIndexed(lines: List<String>, NL: String): JSONArray =
        JSONArray(lines.mapIndexed { i, line -> "$i: ${line.replace("\n", NL)}" })

    // ── Semantic line matching (subtitle sync) ──────────────────────────────────

    /** One confident pairing: reference line [referenceIndex] says the same thing as [candidateIndex]. */
    data class LineMatch(val referenceIndex: Int, val candidateIndex: Int)

    private fun buildMatchPrompt() =
        "You align two subtitle files for the SAME video, written in different languages.\n" +
        "You are given REFERENCE lines and CANDIDATE lines, each prefixed with its index.\n" +
        "Rules:\n" +
        "1. Return ONLY a valid JSON array of objects, each exactly {\"r\": <reference index>, \"c\": <candidate index>}.\n" +
        "2. Pair a reference line with the candidate line that expresses the SAME dialogue, across languages.\n" +
        "3. OMIT any reference line you are not confident about. A short, correct list beats a complete, guessed one.\n" +
        "4. Never translate, never invent text, never output anything except the JSON array.\n" +
        "5. Each index may appear at most once."

    /**
     * Asks the model which lines of two differently-languaged subtitle files say the same thing.
     *
     * This is a *measuring instrument*, not a decision: the caller turns the returned pairings into
     * a timing offset and then re-scores it with the ordinary timing metric, so a hallucinated
     * pairing can only fail to improve the score — never select a subtitle on its own.
     *
     * Why it exists: our overlap-maximising offset search is a proxy for alignment that degrades
     * when the two files are segmented differently (an English CC track splits lines that a
     * translation merges). Matching by meaning is segmentation-independent.
     */
    suspend fun matchSubtitleLines(
        referenceLines: List<String>,
        candidateLines: List<String>
    ): List<LineMatch>? {
        if (referenceLines.isEmpty() || candidateLines.isEmpty()) return null
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return null
        val prompt = buildMatchPrompt()
        val payload = "REFERENCE:\n${encodeIndexed(referenceLines, " ")}\n\nCANDIDATE:\n${encodeIndexed(candidateLines, " ")}"
        val raw = when (modelProvider()) {
            SubtitleAiModel.GROQ_LLAMA_70B -> matchGroq(prompt, payload, apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> matchGemini(prompt, payload, apiKey)
        } ?: return null
        val array = extractJsonArray(raw) ?: return null
        val matches = ArrayList<LineMatch>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val r = obj.optInt("r", -1)
            val c = obj.optInt("c", -1)
            if (r !in referenceLines.indices || c !in candidateLines.indices) continue
            matches.add(LineMatch(r, c))
        }
        // Each index at most once — a model that maps several references onto one candidate line is
        // guessing, and those pairs would all vote the same bogus offset.
        val seenRef = HashSet<Int>()
        val seenCand = HashSet<Int>()
        return matches.filter { seenRef.add(it.referenceIndex) && seenCand.add(it.candidateIndex) }
    }

    private suspend fun matchGroq(systemPrompt: String, payload: String, apiKey: String): String? {
        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0)
            put("reasoning_effort", GROQ_REASONING_EFFORT)
            put("reasoning_format", GROQ_REASONING_FORMAT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", payload) })
            })
        }
        val request = Request.Builder()
            .url(GROQ_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        Log.w(TAG, "matchSubtitleLines groq HTTP ${response.code}")
                        return@use null
                    }
                    JSONObject(responseBody).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "matchSubtitleLines groq failed: ${it.message}")
            }.getOrNull()
        }
    }

    private suspend fun matchGemini(systemPrompt: String, payload: String, apiKey: String): String? {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", payload) }) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().apply { put("thinkingLevel", "minimal") })
            })
            // Same reason as translation: this is the film's own dialogue, and default safety
            // filters block such prompts outright (HTTP 200 with no candidates).
            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply { put("category", category); put("threshold", "BLOCK_NONE") })
                }
            })
        }
        val request = Request.Builder()
            .url("$GEMINI_BASE_URL?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        Log.w(TAG, "matchSubtitleLines gemini HTTP ${response.code}")
                        return@use null
                    }
                    JSONObject(responseBody).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "matchSubtitleLines gemini failed: ${it.message}")
            }.getOrNull()
        }
    }

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult =
        translateBatchInternal(lines, targetLanguage, depth = 0)

    private suspend fun translateBatchInternal(
        lines: List<String>,
        targetLanguage: String,
        depth: Int
    ): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank — translation skipped")
            return TranslationResult(lines, false, "API key missing")
        }

        val result = when (modelProvider()) {
            SubtitleAiModel.GROQ_LLAMA_70B -> translateGroq(lines, targetLanguage, apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> translateGemini(lines, targetLanguage, apiKey)
        }

        // A content-policy block poisons the whole window because of (usually) one line.
        // Bisect so the innocuous majority still gets translated; a line that stays blocked all
        // the way down is shown in its original language. Depth cap keeps worst-case extra API
        // calls bounded (window → halves → quarters).
        if (!result.success &&
            result.errorMessage == TRANSLATION_ERROR_CONTENT_BLOCKED &&
            lines.size >= 2 && depth < 2
        ) {
            Log.w(TAG, "Content-blocked batch of ${lines.size} — bisecting (depth=$depth)")
            val mid = lines.size / 2
            val left = translateBatchInternal(lines.subList(0, mid), targetLanguage, depth + 1)
            val right = translateBatchInternal(lines.subList(mid, lines.size), targetLanguage, depth + 1)
            // Best-effort merge: failed halves already carry their original lines.
            return TranslationResult(left.lines + right.lines, true)
        }
        return result
    }

    private suspend fun translateGroq(lines: List<String>, targetLanguage: String, apiKey: String): TranslationResult {
        val NL = "⏎"
        val inputArray = encodeIndexed(lines, NL)
        val systemPrompt = buildSystemPrompt(targetLanguage, NL)

        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0.1)
            put("reasoning_effort", GROQ_REASONING_EFFORT)
            put("reasoning_format", GROQ_REASONING_FORMAT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Translate to $targetLanguage:\n$inputArray")
                })
            })
        }

        val request = Request.Builder()
            .url(GROQ_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: run {
                    Log.e(TAG, "Empty response body (HTTP ${response.code})")
                    return@withContext TranslationResult(lines, false, "Empty response (${response.code})")
                }
                if (!response.isSuccessful) {
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    return@withContext TranslationResult(lines, false, errorMsg)
                }

                val json = JSONObject(responseBody)
                val rawText = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                parseTranslationResult(lines, targetLanguage, rawText, NL)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e

                Log.e(TAG, "translateGroq exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }

    /** ±30% jittered exponential backoff so a fleet hitting the same overload spike doesn't re-sync. */
    private suspend fun geminiBackoff(transientAttempt: Int) {
        val factor = 1L shl transientAttempt          // 2^transientAttempt: 1, 2, ...
        val jitter = 0.7 + Math.random() * 0.6        // 0.7 .. 1.3
        delay((GEMINI_RETRY_BASE_DELAY_MS * factor * jitter).toLong())
    }

    private suspend fun translateGemini(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        attempt: Int = 0,
        transientAttempt: Int = 0
    ): TranslationResult {
        val NL = "⏎"
        val inputArray = encodeIndexed(lines, NL)
        val systemPrompt = buildSystemPrompt(targetLanguage, NL)

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Translate to $targetLanguage:\n$inputArray")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().apply {
                    // Subtitles need raw inference speed, not reasoning. Measured on
                    // gemini-3.5-flash (July 2026): default = ~5s + 875 thought tokens per batch;
                    // minimal = ~1.3s + 0. "thinkingLevel" is the Gemini 3.x field (the legacy
                    // 2.5 "thinkingBudget: 0" still works there today, but is deprecated).
                    put("thinkingLevel", "minimal")
                })
            })
            // Movie dialogue routinely contains violence/profanity/sexual references; default
            // safety filters block such prompts outright (HTTP 200 with NO candidates — seen as
            // "translation error" toasts at episode start on gemini-3.5-flash). We're translating
            // the film's own subtitle text, not generating new content.
            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(GEMINI_BASE_URL)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: run {
                    Log.e(TAG, "Empty Gemini response body (HTTP ${response.code})")
                    return@withContext TranslationResult(lines, false, "Empty response (${response.code})")
                }
                if (!response.isSuccessful) {
                    if (response.code in GEMINI_RETRYABLE_HTTP && transientAttempt < GEMINI_MAX_TRANSIENT_RETRIES) {
                        Log.w(TAG, "Gemini HTTP ${response.code} (transient) — retry ${transientAttempt + 1}/$GEMINI_MAX_TRANSIENT_RETRIES")
                        geminiBackoff(transientAttempt)
                        return@withContext translateGemini(lines, targetLanguage, apiKey, attempt, transientAttempt + 1)
                    }
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    return@withContext TranslationResult(lines, false, errorMsg)
                }

                val json = JSONObject(responseBody)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    // HTTP 200 without candidates = prompt-level block (safety/recitation).
                    val blockReason = json.optJSONObject("promptFeedback")
                        ?.optString("blockReason").orEmpty()
                    Log.e(TAG, "Gemini missing candidates (block=$blockReason): ${responseBody.take(400)}")
                    return@withContext TranslationResult(
                        lines, false,
                        if (blockReason.isNotBlank()) TRANSLATION_ERROR_CONTENT_BLOCKED else "No candidates in response"
                    )
                }
                val candidate = candidates.getJSONObject(0)
                // Gemini 3.x can emit thought/thoughtSignature parts before the text — take
                // every non-thought text part, not blindly parts[0].
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                val rawText = buildString {
                    for (i in 0 until (parts?.length() ?: 0)) {
                        val part = parts!!.getJSONObject(i)
                        if (part.optBoolean("thought", false)) continue
                        append(part.optString("text"))
                    }
                }.trim()
                val finishReason = candidate.optString("finishReason")
                if (rawText.isBlank()) {
                    Log.e(TAG, "Gemini candidate has no text (finish=$finishReason): ${responseBody.take(400)}")
                    return@withContext TranslationResult(
                        lines, false,
                        if (finishReason in BLOCKED_FINISH_REASONS) TRANSLATION_ERROR_CONTENT_BLOCKED
                        else "Empty candidate (finish=$finishReason)"
                    )
                }

                val parsed = parseTranslationResult(lines, targetLanguage, rawText, NL)
                if (!parsed.success && finishReason in BLOCKED_FINISH_REASONS) {
                    // The policy filter can also cut generation mid-array: partial text +
                    // blocked finishReason parses as garbage. Same treatment — bisect upstream.
                    Log.e(TAG, "Gemini output truncated by policy (finish=$finishReason)")
                    return@withContext TranslationResult(lines, false, TRANSLATION_ERROR_CONTENT_BLOCKED)
                }
                if (!parsed.success && attempt == 0) {
                    // Intermittent JSON-mode malformation (e.g. tail truncation with STOP) —
                    // one re-roll usually returns a clean array.
                    Log.w(TAG, "Parse failed (${parsed.errorMessage}, finish=$finishReason) — retrying batch once")
                    return@withContext translateGemini(lines, targetLanguage, apiKey, attempt = 1)
                }
                if (!parsed.success) {
                    Log.e(TAG, "Parse failed after retry (finish=$finishReason) body=${responseBody.take(400)}")
                }
                parsed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e

                if (e is java.io.IOException && transientAttempt < GEMINI_MAX_TRANSIENT_RETRIES) {
                    Log.w(TAG, "translateGemini network error (${e.message}) — retry ${transientAttempt + 1}/$GEMINI_MAX_TRANSIENT_RETRIES")
                    geminiBackoff(transientAttempt)
                    return@withContext translateGemini(lines, targetLanguage, apiKey, attempt, transientAttempt + 1)
                }
                Log.e(TAG, "translateGemini exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }

    private fun parseTranslationResult(lines: List<String>, targetLanguage: String, rawText: String, NL: String): TranslationResult {
        var resultArray = extractJsonArray(rawText)
        if (resultArray == null && lines.size == 1) {
            // Single-line batches: the model often returns a bare JSON string (or plain text)
            // instead of a one-element array — treat the whole payload as that one translation.
            val single = runCatching { JSONArray("[$rawText]").getString(0) }.getOrNull()
                ?: rawText.trim().trim('"')
            if (single.isNotBlank()) resultArray = JSONArray(listOf(single))
        }
        if (resultArray == null) {
            // The only failure path that carries no HTTP/finishReason context — always log the
            // offending text or the toast is undiagnosable.
            Log.e(TAG, "No JSON array in model output (sent ${lines.size} lines): ${rawText.take(300)}")
            return TranslationResult(lines, false, "No valid JSON array in response")
        }

        // Align by the numeric prefix, not array position — merges/splits then cost only the
        // affected line (it stays in the original language) instead of failing the window.
        val byIndex = HashMap<Int, String>()
        for (i in 0 until resultArray.length()) {
            val element = resultArray.optString(i) ?: continue
            val match = indexPrefixRegex.find(element)
            val key: Int
            val value: String
            if (match != null) {
                key = match.groupValues[1].toIntOrNull() ?: continue
                value = element.substring(match.range.last + 1)
            } else if (resultArray.length() == lines.size) {
                // Model dropped the prefixes but the count matches — positional fallback.
                key = i
                value = element
            } else {
                continue
            }
            if (!byIndex.containsKey(key)) byIndex[key] = value
        }
        if (byIndex.isEmpty()) {
            Log.e(TAG, "No alignable lines (sent ${lines.size}, got ${resultArray.length()}): ${rawText.take(300)}")
            return TranslationResult(lines, false, "No valid JSON array in response")
        }
        if (byIndex.size < lines.size) {
            Log.w(TAG, "Partial alignment: ${byIndex.size}/${lines.size} lines translated")
        }

        val isRtl = RTL_LANGUAGES.contains(targetLanguage.lowercase())
        val translated = lines.indices.map { i ->
            val line = byIndex[i]?.replace(NL, "\n") ?: return@map lines[i]
            if (isRtl) "‏$line‏" else line
        }

        return TranslationResult(translated, true)
    }
}
