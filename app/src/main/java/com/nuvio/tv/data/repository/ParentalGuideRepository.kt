package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideCategory
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Resolved parental guide data with a single severity per category,
 * determined by the highest-voted severity level from the API.
 */
data class ParentalGuideResult(
    val nudity: String? = null,
    val violence: String? = null,
    val profanity: String? = null,
    val alcohol: String? = null,
    val frightening: String? = null
)

@Singleton
class ParentalGuideRepository @Inject constructor(
    private val api: ParentalGuideApi
) {
    private val cache = ConcurrentHashMap<String, ParentalGuideResult>()

    suspend fun getParentalGuide(imdbId: String): ParentalGuideResult? {
        if (!imdbId.startsWith("tt")) return null

        cache[imdbId]?.let { return it }

        return try {
            val response = api.getParentsGuide(imdbId)
            if (response.isSuccessful && !response.body()?.parentsGuide.isNullOrEmpty()) {
                val categories = response.body()!!.parentsGuide!!
                val result = mapToResult(categories)
                cache[imdbId] = result
                result
            } else {
                null
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.e("ParentalGuide", "Failed to fetch parental guide for $imdbId", e)
            null
        }
    }

    private fun mapToResult(categories: List<ImdbApiParentsGuideCategory>): ParentalGuideResult {
        val categoryMap = categories.associateBy { it.category.uppercase() }

        return ParentalGuideResult(
            nudity = resolveSeverity(categoryMap["SEXUAL_CONTENT"]),
            violence = resolveSeverity(categoryMap["VIOLENCE"]),
            profanity = resolveSeverity(categoryMap["PROFANITY"]),
            alcohol = resolveSeverity(categoryMap["ALCOHOL_DRUGS"]),
            frightening = resolveSeverity(categoryMap["FRIGHTENING_INTENSE_SCENES"])
        )
    }

    /**
     * Determines the dominant severity level for a category by picking
     * the level with the highest vote count (excluding "none").
     * Returns null if no meaningful votes exist.
     */
    private fun resolveSeverity(category: ImdbApiParentsGuideCategory?): String? {
        if (category == null) return null

        val breakdowns = category.severityBreakdowns ?: return null

        // Find the severity with the most votes, excluding "none"
        val dominant = breakdowns
            .filter { it.severityLevel.lowercase() != "none" }
            .maxByOrNull { it.voteCount }

        // If "none" has more votes than any other severity, treat as no concern
        val noneVotes = breakdowns
            .firstOrNull { it.severityLevel.lowercase() == "none" }
            ?.voteCount ?: 0

        if (dominant == null || dominant.voteCount <= noneVotes) return null

        return dominant.severityLevel.lowercase()
    }
}
