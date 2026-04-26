package com.omnio.tv.domain.model

enum class AgeRatingTier(val rank: Int, val label: String) {
    G(0, "G"),
    PG(1, "PG"),
    PG_13(2, "PG-13"),
    TV_14(3, "TV-14"),
    R(4, "R"),
    NC_17(5, "NC-17");

    fun allowsUpTo(other: AgeRatingTier): Boolean = other.rank <= this.rank

    companion object {
        fun normalize(raw: String?): AgeRatingTier? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().uppercase()
                .removePrefix("RATED ")
                .removeSuffix(" (UK)")
                .removeSuffix(" (US)")

            return when (cleaned) {
                "G", "U", "TV-G", "TV-Y", "TV-Y7", "TV-Y7-FV", "0+", "ALL", "GENERAL" -> G
                "PG", "TV-PG", "PG-7", "7+", "PARENTAL GUIDANCE" -> PG
                "PG-13", "PG13", "12", "12A", "13+", "TEEN" -> PG_13
                "TV-14", "14", "14+", "14A", "15", "M" -> TV_14
                "R", "TV-MA", "MA", "16", "16+", "17+", "18", "18+", "MATURE" -> R
                "NC-17", "NC17", "X", "AO", "ADULTS ONLY" -> NC_17
                else -> {
                    val numeric = cleaned.filter { it.isDigit() }.toIntOrNull()
                    when {
                        numeric == null -> null
                        numeric <= 6 -> G
                        numeric <= 9 -> PG
                        numeric <= 12 -> PG_13
                        numeric <= 15 -> TV_14
                        numeric <= 17 -> R
                        else -> NC_17
                    }
                }
            }
        }
    }
}
