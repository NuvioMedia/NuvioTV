package com.nuvio.tv.ui.screens.player

import android.util.Log
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

private const val GEMINI_MODEL_ID = "gemini-2.5-flash"

internal class SubtitleTranslationService(
    private val apiKeyProvider: () -> String
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

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank — translation skipped")
            return TranslationResult(lines, false, "API key missing")
        }

        // Replace newlines within cues with a placeholder so Gemini never splits a
        // multi-line cue into separate array entries. Restored after translation.
        val NL = "\u23CE" // ⏎ — unlikely to appear in subtitle text
        val encoded = lines.map { it.replace("\n", NL) }
        val inputArray = JSONArray(encoded)
        val systemPrompt = "Translate movie subtitles naturally. Return ONLY a JSON array, same order and count. Preserve $NL as-is (line-break). No extra text."

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Translate to $targetLanguage:\n$inputArray")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingBudget", 0)
                })
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL_ID:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .header("content-type", "application/json")
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
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                val resultArray = extractJsonArray(rawText)
                    ?: return@withContext TranslationResult(lines, false, "No valid JSON array in response")

                if (resultArray.length() != lines.size) {
                    Log.w(TAG, "Line count mismatch: sent ${lines.size}, got ${resultArray.length()}")
                    return@withContext TranslationResult(lines, false, "Line count mismatch")
                }

                val isRtl = RTL_LANGUAGES.contains(targetLanguage.lowercase())
                val translated = List(resultArray.length()) { i ->
                    val line = resultArray.getString(i).replace(NL, "\n")
                    if (isRtl) "\u200F$line\u200F" else line
                }

                TranslationResult(translated, true)
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }
}
