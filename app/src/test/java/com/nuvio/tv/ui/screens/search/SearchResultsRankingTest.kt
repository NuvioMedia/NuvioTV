package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsRankingTest {

    @Test
    fun `exact title match ranks ahead of partial match`() {
        val exact = preview(id = "exact", name = "Dexter", rating = 6f)
        val partial = preview(id = "partial", name = "Dexter Discussion", rating = 9f)
        val ranked = rankSearchResults("dexter", listOf(partial, exact))
        assertEquals(listOf("exact", "partial"), ranked.map { it.id })
    }

    @Test
    fun `franchise sequels follow original in release order`() {
        val original = preview(id = "dexter", name = "Dexter", year = 2006, rating = 8.6f)
        val sequel = preview(id = "new-blood", name = "Dexter: New Blood", year = 2021, rating = 8.3f)
        val ranked = rankSearchResults("dexter", listOf(sequel, original))
        assertEquals(listOf("dexter", "new-blood"), ranked.map { it.id })
    }

    @Test
    fun `more popular franchise ranks before deep cut with same match quality`() {
        val popular = preview(id = "popular", name = "Breaking Bad", rating = 9.5f)
        val deepCut = preview(id = "deep", name = "Breaking In", rating = 5f)
        val ranked = rankSearchResults("breaking", listOf(deepCut, popular))
        assertEquals(listOf("popular", "deep"), ranked.map { it.id })
    }

    @Test
    fun `match quality dominates popularity`() {
        val exact = preview(id = "exact", name = "Moneyball", rating = 5f)
        val popularPartial = preview(id = "popular", name = "Moneyball Extra", rating = 9.9f)
        val ranked = rankSearchResults("moneyball", listOf(popularPartial, exact))
        assertEquals(listOf("exact", "popular"), ranked.map { it.id })
    }

    @Test
    fun `missing rating sorts after rated items within same family`() {
        val rated = preview(id = "rated", name = "The Wire", rating = 9.3f)
        val unrated = preview(id = "unrated", name = "The Wire Sessions", rating = null)
        val ranked = rankSearchResults("the wire", listOf(unrated, rated))
        assertEquals(listOf("rated", "unrated"), ranked.map { it.id })
    }

    @Test
    fun `single item list is returned unchanged`() {
        val only = preview(id = "only", name = "Heat")
        assertEquals(listOf("only"), rankSearchResults("heat", listOf(only)).map { it.id })
    }

    @Test
    fun `year and season suffixes do not break family grouping`() {
        val root = preview(id = "root", name = "Berlin", year = 2019, rating = 7f)
        val season = preview(id = "season", name = "Berlin Season 2", year = 2023, rating = 8f)
        val ranked = rankSearchResults("berlin", listOf(season, root))
        assertEquals(listOf("root", "season"), ranked.map { it.id })
    }

    @Test
    fun `empty query still groups families and orders by rating`() {
        val high = preview(id = "high", name = "Alpha", rating = 9f)
        val low = preview(id = "low", name = "Beta", rating = 3f)
        val ranked = rankSearchResults("", listOf(low, high))
        assertTrue(ranked.map { it.id }.indexOf("high") < ranked.map { it.id }.indexOf("low"))
    }

    @Test
    fun `seasons sort numerically before alphabetical fallback`() {
        val s1 = preview(id = "s1", name = "Berlin Station Season 1")
        val s2 = preview(id = "s2", name = "Berlin Station Season 2")
        val s10 = preview(id = "s10", name = "Berlin Station Season 10")
        val ranked = rankSearchResults("berlin station", listOf(s10, s1, s2))
        assertEquals(listOf("s1", "s2", "s10"), ranked.map { it.id })
    }

    @Test
    fun `contiguous phrase match ranks above scattered tokens`() {
        val phrase = searchMatchQuality("dark water", "The Dark Water Falls")
        val scattered = searchMatchQuality("dark water", "Water In The Dark")
        assertTrue(
            "expected phrase match ($phrase) before scattered match ($scattered)",
            phrase < scattered
        )
    }

    @Test
    fun `query ending with a season number promotes that season on top`() {
        val s1 = preview(id = "s1", name = "Dark Matter Season 1")
        val s2 = preview(id = "s2", name = "Dark Matter Season 2")
        val s3 = preview(id = "s3", name = "Dark Matter Season 3")
        val ranked = rankSearchResults("dark matter stagione 2", listOf(s3, s1, s2))
        assertEquals(listOf("s2", "s1", "s3"), ranked.map { it.id })
    }

    @Test
    fun `irrelevant season match does not beat relevant title matches`() {
        val s2 = preview(id = "s2", name = "Dark Matter Season 2")
        val s3 = preview(id = "s3", name = "Dark Matter Season 3")
        val other = preview(id = "other", name = "Bright City Season 2")
        val ranked = rankSearchResults("dark matter s2", listOf(other, s3, s2))
        assertEquals(listOf("s2", "s3"), ranked.map { it.id }.take(2))
        assertEquals("other", ranked.last().id)
    }

    @Test
    fun `season number survives a trailing year`() {
        assertEquals(2, searchSeasonNumber(preview(id = "x", name = "Berlin Station Season 2 (2020)")))
        assertEquals(4, searchSeasonNumber(preview(id = "y", name = "Some Show S04")))
    }

    @Test
    fun `popular spin-off group outranks older deep cuts under the same root word`() {
        // A result titled exactly "Tokyo" flattens every "Tokyo *" title into one
        // family: popularity of the branch must still decide, not the release year.
        val ranked = rankSearchResults(
            "tokyo",
            listOf(
                preview(id = "tokyo", name = "Tokyo", year = 2023, rating = 6.9f),
                preview(id = "mewmew", name = "Tokyo Mew Mew", year = 2002, rating = 6.4f),
                preview(id = "godfathers", name = "Tokyo Godfathers", year = 2003, rating = 8.0f),
                preview(id = "ghoul", name = "Tokyo Ghoul", year = 2014, rating = 8.6f),
                preview(id = "ghoul-re", name = "Tokyo Ghoul:re", year = 2018, rating = 7.4f),
                preview(id = "tr", name = "Tokyo Revengers", year = 2021, rating = 7.9f),
                preview(id = "tr-xmas", name = "Tokyo Revengers: Christmas Showdown", year = 2022, rating = 7.9f),
                preview(id = "tr-tenjiku", name = "Tokyo Revengers: Tenjiku-hen", year = 2023, rating = 8.0f),
                preview(id = "hotel", name = "Tokyo Hotel", year = 2020, rating = 4.8f),
                preview(id = "paradise", name = "Tokyo Paradise", year = 2019, rating = 5.2f)
            )
        ).map { it.id }
        val trPosition = ranked.indexOf("tr")
        assertTrue("Tokyo Revengers should rank near the top but was at $trPosition: $ranked", trPosition in 0..4)
        assertTrue("Tokyo Ghoul branch should lead: $ranked", ranked.indexOf("ghoul") < trPosition)
        assertTrue("deep cuts stay behind Tokyo Revengers: $ranked", ranked.indexOf("hotel") > trPosition)
        assertTrue("deep cuts stay behind Tokyo Revengers: $ranked", ranked.indexOf("paradise") > trPosition)
    }

    @Test
    fun `spin-offs keep release order inside their own franchise`() {
        // The bare root word "Tokyo" anchors the branch: every Tokyo Revengers
        // spin-off then groups under it and follows release order.
        val ranked = rankSearchResults(
            "tokyo revengers",
            listOf(
                preview(id = "tokyo", name = "Tokyo", year = 2023, rating = 6.9f),
                preview(id = "tr-tenjiku", name = "Tokyo Revengers: Tenjiku-hen", year = 2023, rating = 8.4f),
                preview(id = "tr-xmas", name = "Tokyo Revengers: Christmas Showdown", year = 2022, rating = 7.9f),
                preview(id = "tr", name = "Tokyo Revengers", year = 2021, rating = 7.9f)
            )
        ).map { it.id }
        assertEquals(listOf("tr", "tr-xmas", "tr-tenjiku"), ranked.take(3))
    }

    @Test
    fun `popular franchise outranks an older unrelated title with a worse rating`() {
        val ranked = rankSearchResults(
            "breaking",
            listOf(
                preview(id = "classic", name = "Breaking Classic", year = 1990, rating = 6.2f),
                preview(id = "bad", name = "Breaking Bad", year = 2008, rating = 9.5f),
                preview(id = "spinoff", name = "Better Call Saul", year = 2015, rating = 9.0f)
            )
        ).map { it.id }
        assertEquals("bad", ranked.first())
        assertTrue("deep cut stays behind: $ranked", ranked.indexOf("classic") > ranked.indexOf("bad"))
    }

    @Test
    fun `release stamp parses iso day-first and year-only dates`() {
        assertEquals(20210907L, searchReleaseStamp(preview(id = "iso", name = "Iso", released = "2021-09-07")))
        assertEquals(20210709L, searchReleaseStamp(preview(id = "df", name = "DayFirst", releaseInfo = "09/07/2021")))
        assertEquals(20190101L, searchReleaseStamp(preview(id = "y", name = "YearOnly", releaseInfo = "2019")))
        assertEquals(null, searchReleaseStamp(preview(id = "n", name = "None")))
    }

    @Test
    fun `release sort puts newest first and unknown dates last`() {
        val items = listOf(
            preview(id = "old", name = "Old", year = 1999),
            preview(id = "new", name = "New", released = "2024-12-25"),
            preview(id = "mid", name = "Mid", year = 2010),
            preview(id = "none", name = "None")
        )
        val ordered = orderSearchResults(SearchSortMode.RELEASE, items).map { it.id }
        assertEquals(listOf("new", "mid", "old", "none"), ordered)
    }

    @Test
    fun `rating sort puts highest rating first and unrated last`() {
        val items = listOf(
            preview(id = "low", name = "Low", rating = 5f),
            preview(id = "high", name = "High", rating = 8.5f),
            preview(id = "none", name = "None"),
            preview(id = "mid", name = "Mid", rating = 7f)
        )
        val ordered = orderSearchResults(SearchSortMode.RATING, items).map { it.id }
        assertEquals(listOf("high", "mid", "low", "none"), ordered)
    }

    @Test
    fun `popularity sort orders by votes then rating and keeps relevance for ties`() {
        val mostVoted = preview(id = "most-voted", name = "Dexter Discussion", rating = 6f, votes = 900)
        val lessVoted = preview(id = "less-voted", name = "Dexter", rating = 9f, votes = 100)
        val unratedButVoted = preview(id = "voted-only", name = "Dexter Spinoff", rating = null, votes = 50)
        val tieA = preview(id = "tie-a", name = "Dexter B", rating = 8f, votes = null)
        val tieB = preview(id = "tie-b", name = "Dexter A", rating = 8f, votes = null)
        val ranked = rankSearchResults(
            "dexter",
            listOf(tieA, tieB, unratedButVoted, lessVoted, mostVoted)
        )
        val ordered = orderSearchResults(SearchSortMode.POPULARITY, ranked).map { it.id }
        // Votes beat rating, rated beats unrated...
        assertEquals(listOf("most-voted", "less-voted", "voted-only"), ordered.take(3))
        // ...and equally popular items keep the relevance order the ranking
        // already computed (stable sort).
        assertEquals(
            ranked.map { it.id }.filter { it == "tie-a" || it == "tie-b" },
            ordered.drop(3)
        )
    }

    @Test
    fun `popularity sort keeps an exact title match ahead of a famous partial match`() {
        // L'utente che cerca "berlino" deve vedere "Berlino" in cima: la fama
        // del titolo parziale non deve seppellire la corrispondenza esatta.
        val exactButObscure = preview(id = "exact", name = "Berlino", rating = 5.5f, votes = 3)
        val famousPartial = preview(
            id = "famous",
            name = "La casa di carta: De Tokio a Berlin",
            rating = 8.3f,
            votes = 9000
        )
        val ranked = rankSearchResults("berlino", listOf(exactButObscure, famousPartial))
        assertEquals(
            listOf("exact", "famous"),
            orderSearchResults(SearchSortMode.POPULARITY, ranked, "berlino").map { it.id }
        )
    }

    @Test
    fun `popularity sort prefers a word prefix match over fame`() {
        // "gioielli" è prefisso della parola "gioielli" in "I gioielli di
        // Parigi": deve superare un titolo famoso con solo contains.
        val wordPrefix = preview(id = "jewels", name = "I gioielli di Parigi", rating = 7f, votes = 50)
        val famousContains = preview(id = "famous", name = "Il gioiello mancante", rating = 9f, votes = 5000)
        val ranked = rankSearchResults("gioielli", listOf(famousContains, wordPrefix))
        assertEquals(
            listOf("jewels", "famous"),
            orderSearchResults(SearchSortMode.POPULARITY, ranked, "gioielli").map { it.id }
        )
    }

    private fun preview(
        id: String,
        name: String,
        rating: Float? = null,
        year: Int? = null,
        released: String? = null,
        releaseInfo: String? = null,
        votes: Int? = null
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        name = name,
        poster = "https://img.test/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo ?: year?.toString(),
        imdbRating = rating,
        genres = emptyList(),
        released = released ?: year?.let { "$it-01-01" },
        voteCount = votes
    )
}
