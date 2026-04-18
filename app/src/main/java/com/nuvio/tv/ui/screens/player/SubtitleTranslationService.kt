package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.SubtitleAiModel
import kotlinx.coroutines.Dispatchers
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

internal data class TranslationResult(
    val lines: List<String>,
    val success: Boolean,
    val errorMessage: String? = null
)

private const val GROQ_MODEL_ID = "llama-3.3-70b-versatile"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val GEMINI_MODEL_ID = "gemini-2.5-flash"
private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL_ID:generateContent"

internal class SubtitleTranslationService(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> SubtitleAiModel = { SubtitleAiModel.GROQ_LLAMA_70B }
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Extracts the last valid JSON array from a potentially verbose model response. */
    private fun extractJsonArray(text: String): JSONArray? {
        val codeBlocks = Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(text)
            .map { it.groupValues[1].trim() }.toList().reversed()
        val stripped = text.replace(Regex("```[^`]*```"), "").trim()
        val candidates = codeBlocks + listOf(stripped, text)

        for (candidate in candidates) {
            try { return JSONArray(candidate) } catch (_: Exception) {}
            val start = candidate.indexOf('[')
            val end = candidate.lastIndexOf(']')
            if (start < 0 || end <= start) continue
            try {
                return JSONArray(candidate.substring(start, end + 1))
            } catch (_: Exception) {}
        }
        return null
    }

    private fun buildSystemPrompt(targetLanguage: String, NL: String) =
        "You are a professional subtitle translator. Translate the following JSON array into natural $targetLanguage.\n" +
        "Rules:\n" +
        "1. Return ONLY a valid JSON array.\n" +
        "2. Keep the exact same order and element count.\n" +
        "3. Preserve the '$NL' symbol exactly where it appears as a line break.\n" +
        "4. Use informal, spoken $targetLanguage suitable for cinema."

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank — translation skipped")
            return TranslationResult(lines, false, "API key missing")
        }

        return when (modelProvider()) {
            SubtitleAiModel.GROQ_LLAMA_70B -> translateGroq(lines, targetLanguage, apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> translateGemini(lines, targetLanguage, apiKey)
        }
    }

    private suspend fun translateGroq(lines: List<String>, targetLanguage: String, apiKey: String): TranslationResult {
        val NL = "\u23CE"
        val encoded = lines.map { it.replace("\n", NL) }
        val inputArray = JSONArray(encoded)

        val systemPrompt = buildSystemPrompt(targetLanguage, NL)

        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0.1)
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
                Log.e(TAG, "translateGroq exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }

    private suspend fun translateGemini(lines: List<String>, targetLanguage: String, apiKey: String): TranslationResult {
        val NL = "\u23CE"
        val encoded = lines.map { it.replace("\n", NL) }
        val inputArray = JSONArray(encoded)

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
                    put("thinkingBudget", 0)
                })
            })
        }

        val url = "$GEMINI_BASE_URL?key=$apiKey"
        val request = Request.Builder()
            .url(url)
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
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    return@withContext TranslationResult(lines, false, errorMsg)
                }

                val json = JSONObject(responseBody)
                val rawText = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                parseTranslationResult(lines, targetLanguage, rawText, NL)
            } catch (e: Exception) {
                Log.e(TAG, "translateGemini exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }

    private fun parseTranslationResult(lines: List<String>, targetLanguage: String, rawText: String, NL: String): TranslationResult {
        val resultArray = extractJsonArray(rawText)
            ?: return TranslationResult(lines, false, "No valid JSON array in response")

        if (resultArray.length() != lines.size) {
            Log.w(TAG, "Line count mismatch: sent ${lines.size}, got ${resultArray.length()}")
            return TranslationResult(lines, false, "Line count mismatch")
        }

        val isRtl = RTL_LANGUAGES.contains(targetLanguage.lowercase())
        val translated = List(resultArray.length()) { i ->
            val line = resultArray.getString(i).replace(NL, "\n")
            if (isRtl) "\u200F$line\u200F" else line
        }

        return TranslationResult(translated, true)
    }
}
