package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.MetaPreview
import java.util.Locale

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private val SEASON_SUFFIX_REGEX = Regex(
    """\s*[-–:,(]*\s*(?:season|stagione|series|volume|vol|part(?:e)?)\s*[-]?\s*\d{1,3}\s*\)?$""",
    RegexOption.IGNORE_CASE
)
private val YEAR_SUFFIX_REGEX = Regex("""\s*[(\[【]?\s*(?:19|20)\d{2}\s*[)\]】]?\s*$""")
private val NON_ALNUM_REGEX = Regex("""[^0-9a-zà-öø-ÿ]+""")

/** 0 = exact title match, lower is closer to the typed query. */
internal fun searchMatchQuality(query: String, title: String): Int {
    val q = searchNormalizeText(query)
    val t = searchNormalizeText(title)
    if (q.isEmpty() || t.isEmpty()) return Int.MAX_VALUE
    val qt = searchNormalizeTitle(query)
    val tt = searchNormalizeTitle(title)
    // "the mentalist (2008)" typed against "The Mentalist" is an exact hit, so
    // decorations are stripped from both sides before comparing. A decorated
    // *title* only reaches bucket 0 when it carries no season number, otherwise
    // a single season entry would tie with — and then outrank — the bare show.
    val decoratedExact = qt.isNotEmpty() && tt == qt && SEASON_NUMBER_REGEX.find(title) == null
    return when {
        t == q -> 0
        decoratedExact -> 0
        t.startsWith(q) -> 1
        t.split(' ').any { it.startsWith(q) } -> 2
        // Contiguous phrase implies every token is present, so it ranks above
        // the looser "all tokens somewhere in the title" match.
        t.contains(q) -> 3
        q.split(' ').all { token -> token.isNotEmpty() && t.contains(token) } -> 4
        else -> 5
    }
}

/**
 * Franchise root: shorter titles that are a strict prefix of longer ones
 * (e.g. "dexter" owns "dexter new blood") share one family so sequels can sit
 * in release order under the original.
 */
internal fun searchTitleFamily(name: String, allNormalized: List<String>): String {
    val self = searchNormalizeTitle(name)
    if (self.isEmpty()) return self
    var family = self
    for (candidate in allNormalized) {
        if (candidate.length < family.length &&
            family.startsWith(candidate) &&
            (family.length == candidate.length || !family[candidate.length].isLetter())
        ) {
            family = candidate
        }
    }
    return family
}

internal fun searchReleaseYear(item: MetaPreview): Int? {
    listOfNotNull(item.released, item.releaseInfo).forEach { raw ->
        YEAR_REGEX.find(raw)?.value?.toIntOrNull()?.let { return it }
    }
    return null
}

private val SEASON_NUMBER_REGEX = Regex(
    """\s(?:season|stagione|series|s|volume|vol|part(?:e)?)\s*[-]?\s*(\d{1,3})(?:\s*[(\[]?(?:19|20)\d{2}[)\]]?)?\s*$""",
    RegexOption.IGNORE_CASE
)

/** Season number extracted from titles like "Show Season 10" so episodes sort 1, 2, 10. */
internal fun searchSeasonNumber(item: MetaPreview): Int? =
    SEASON_NUMBER_REGEX.find(item.name)?.groupValues?.get(1)?.toIntOrNull()

private val QUERY_SEASON_REGEX = Regex(
    """\s+(?:season|stagione|series|s|vol(?:ume)?|part(?:e)?)\s*[-#]?\s*(\d{1,3})\s*$""",
    RegexOption.IGNORE_CASE
)

/**
 * "dexter stagione 2" -> ("dexter", 2): the caller ranks against the base
 * title and [searchResultsComparator] promotes the requested season on top.
 */
internal fun searchQuerySeason(query: String): Pair<String, Int>? {
    val match = QUERY_SEASON_REGEX.find(query) ?: return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val base = query.substring(0, match.range.first).trim()
    if (base.isEmpty()) return null
    return base to season
}

internal fun searchNormalizeTitle(name: String): String {
    var base = searchNormalizeText(name)
    while (true) {
        val noSeason = base.replace(SEASON_SUFFIX_REGEX, "").trim()
        val noYear = noSeason.replace(YEAR_SUFFIX_REGEX, "").trim()
        if (noYear == base) break
        base = noYear
    }
    return base.trim()
}

internal fun searchNormalizeText(value: String): String =
    NON_ALNUM_REGEX
        .replace(value.lowercase(Locale.ROOT), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

/** Popularity of one franchise branch: best rating in the branch + its root title. */
internal data class SearchFranchiseRank(val bestRating: Float, val franchise: String)

/**
 * Relevance first (closer title match), then more popular franchises, and
 * within one franchise oldest release first (Dexter before Dexter: New Blood).
 * [familyRankByTitle] maps normalized title -> family popularity rank and is
 * computed once per result set by [rankSearchResults] (no shared mutable state).
 * [franchiseRankByTitle] ranks the branch each title belongs to (e.g. every
 * "Tokyo Ghoul*" spin-off under the "Tokyo Ghoul" branch) by its best rating,
 * so popular spin-off groups stay together and rank by popularity while deep
 * cuts of the same root word fall behind even when they are older releases.
 * When [requiredSeason] is set (query ended with "season N"), items matching
 * that season win among equally relevant title matches.
 */
internal fun searchResultsComparator(
    query: String,
    familyRankByTitle: Map<String, Int> = emptyMap(),
    requiredSeason: Int? = null,
    franchiseRankByTitle: Map<String, SearchFranchiseRank> = emptyMap()
): Comparator<MetaPreview> {
    return Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { searchMatchQuality(query, it.name) },
            { if (requiredSeason == null || searchSeasonNumber(it) == requiredSeason) 0 else 1 },
            { familyRankByTitle[searchNormalizeTitle(it.name)] ?: 0 },
            { -(franchiseRankByTitle[searchNormalizeTitle(it.name)]?.bestRating ?: -1f) },
            { franchiseRankByTitle[searchNormalizeTitle(it.name)]?.franchise ?: searchNormalizeTitle(it.name) },
            { searchReleaseYear(it) ?: Int.MAX_VALUE },
            { searchSeasonNumber(it) ?: Int.MAX_VALUE },
            { it.imdbRating ?: -1f },
            { it.name.lowercase(Locale.ROOT) }
        ).let { base ->
            if (base != 0) base else left.id.compareTo(right.id)
        }
    }
}

/**
 * Sorts a merged search grid: best title matches first, popular franchises
 * ahead of deep cuts, and sequels after their original in release order.
 */
internal fun rankSearchResults(query: String, items: List<MetaPreview>): List<MetaPreview> {
    if (items.size <= 1) return items

    val (baseQuery, requiredSeason) = searchQuerySeason(query) ?: (query to null)

    val normalizedTitles = items
        .map { searchNormalizeTitle(it.name) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedBy { it.length }

    val families = LinkedHashMap<String, String>()
    normalizedTitles.forEach { normalized ->
        families[normalized] = searchTitleFamily(
            name = normalized,
            allNormalized = normalizedTitles
        )
    }

    val bestRatingByFamily = HashMap<String, Float>()
    items.forEach { item ->
        val family = families[searchNormalizeTitle(item.name)] ?: searchNormalizeTitle(item.name)
        val rating = item.imdbRating ?: -1f
        if (rating > (bestRatingByFamily[family] ?: Float.NEGATIVE_INFINITY)) {
            bestRatingByFamily[family] = rating
        }
    }

    val familyRank = bestRatingByFamily.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Float>> { it.value }
                .thenBy { it.key }
        )
        .mapIndexed { index, entry -> entry.key to index }
        .toMap()

    val familyRankByTitle = families.entries
        .map { (normalized, family) -> normalized to (familyRank[family] ?: Int.MAX_VALUE) }
        .toMap()

    // Branch structure: each title's parent is the longest shorter result title
    // that prefixes it ("tokyo ghoul re" -> "tokyo ghoul" -> "tokyo").
    val parentOf = HashMap<String, String>(normalizedTitles.size)
    normalizedTitles.forEach { title ->
        var longest: String? = null
        normalizedTitles.forEach { candidate ->
            if (candidate.length < title.length &&
                title.startsWith(candidate) &&
                !title[candidate.length].isLetter() &&
                (longest == null || candidate.length > longest!!.length)
            ) {
                longest = candidate
            }
        }
        longest?.let { parentOf[title] = it }
    }
    val branchHeads = HashSet<String>()
    parentOf.values.forEach { branchHeads.add(it) }

    // Franchise branch: a title that has spin-offs heads its own branch; a
    // childless title keeps its own branch instead of collapsing into the bare
    // root word ("Tokyo Godfathers" stays its own franchise even when a result
    // titled exactly "Tokyo" exists), otherwise it climbs to the branch head.
    val franchiseOf = HashMap<String, String>(normalizedTitles.size)
    normalizedTitles.forEach { title ->
        var current = title
        var guard = 0
        while (guard++ <= normalizedTitles.size) {
            val parent = parentOf[current] ?: break
            if (current in branchHeads) break
            if (parentOf[parent] == null) break
            current = parent
        }
        franchiseOf[title] = current
    }

    val bestRatingByFranchise = HashMap<String, Float>()
    items.forEach { item ->
        val normalized = searchNormalizeTitle(item.name)
        val franchise = franchiseOf[normalized] ?: normalized
        val rating = item.imdbRating ?: -1f
        if (rating > (bestRatingByFranchise[franchise] ?: Float.NEGATIVE_INFINITY)) {
            bestRatingByFranchise[franchise] = rating
        }
    }

    val franchiseRankByTitle = normalizedTitles
        .map { normalized ->
            val franchise = franchiseOf[normalized] ?: normalized
            normalized to SearchFranchiseRank(
                bestRating = bestRatingByFranchise[franchise] ?: -1f,
                franchise = franchise
            )
        }
        .toMap()

    return items.sortedWith(
        searchResultsComparator(baseQuery, familyRankByTitle, requiredSeason, franchiseRankByTitle)
    )
}

/** Sort orderings offered for the merged search results grid. */
internal enum class SearchSortMode { POPULARITY, RELEASE, RATING }

private val ISO_DATE_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val DAY_FIRST_DATE_REGEX = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")

/**
 * Comparable release stamp (yyyyMMdd) for the RELEASE sort: full dates keep day
 * precision, year-only values land on Jan 1 of that year, unknown dates return
 * null so they sink to the end of a descending sort.
 */
internal fun searchReleaseStamp(item: MetaPreview): Long? {
    listOfNotNull(item.released, item.releaseInfo).forEach { raw ->
        ISO_DATE_REGEX.find(raw)?.let { match ->
            val (year, month, day) = match.destructured
            return year.toLong() * 10000L + month.toLong() * 100L + day.toLong()
        }
        DAY_FIRST_DATE_REGEX.find(raw)?.let { match ->
            val (day, month, year) = match.destructured
            return year.toLong() * 10000L + month.toLong() * 100L + day.toLong()
        }
        YEAR_REGEX.find(raw)?.value?.toIntOrNull()?.let { return it * 10000L + 101L }
    }
    return null
}

/**
 * Popularity proxy for the POPULARITY sort: how many people voted when the
 * catalog reports a vote count (the real "how famous is this?" signal), the
 * IMDb rating when it does not. Unvoted/unrated items sink to the tail.
 *
 * Strong title matches always come first though: when the query is the title,
 * a prefix of it, or a prefix of one of its words ("gioielli" → "I gioielli
 * di Parigi", "berlino" → "Berlino"), fame must not bury the exact hit the
 * user typed — the exact match wins over a famous partial one, and popularity
 * only decides inside each group.
 */
internal const val SEARCH_STRONG_MATCH_MAX_QUALITY = 2

internal fun searchPopularityComparator(query: String = ""): Comparator<MetaPreview> =
    compareByDescending<MetaPreview> {
        searchMatchQuality(query, it.name) <= SEARCH_STRONG_MATCH_MAX_QUALITY
    }
        .thenByDescending { it.voteCount ?: -1 }
        .thenByDescending { it.imdbRating ?: Float.NEGATIVE_INFINITY }

/**
 * Re-orders already-ranked results for the selected mode. POPULARITY puts
 * strong title matches (exact / prefix / word prefix) ahead of the rest and
 * sorts each group by fame (votes, then rating); RELEASE and RATING are
 * stable sorts, so items without a date/rating keep their relevance order at
 * the tail. Every sort is stable, so equally popular items stay in the
 * relevance order computed by [rankSearchResults].
 */
internal fun orderSearchResults(
    mode: SearchSortMode,
    items: List<MetaPreview>,
    query: String = ""
): List<MetaPreview> =
    when (mode) {
        SearchSortMode.POPULARITY -> items.sortedWith(searchPopularityComparator(query))
        SearchSortMode.RELEASE ->
            items.sortedByDescending { searchReleaseStamp(it) ?: Long.MIN_VALUE }
        SearchSortMode.RATING ->
            items.sortedByDescending { it.imdbRating ?: Float.NEGATIVE_INFINITY }
    }
