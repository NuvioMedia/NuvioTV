package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbEpisodeGroupSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbEpisodeGroupSelectorTest {

    private val reZeroGroups = listOf(
        TmdbEpisodeGroupSummary(
            id = "69ec46e3d51f75eca8935b83",
            name = "Seasons + Specials",
            description = "Absolute ordering places all episodes and specials in a single ordered season.",
            episodeCount = 138,
            groupCount = 6,
            type = 2
        ),
        TmdbEpisodeGroupSummary(
            id = "6a4034711b890044938b373e",
            name = "Director's Cut",
            description = "Director's Cut Episode Ordering",
            episodeCount = 0,
            groupCount = 1,
            type = 4
        ),
        TmdbEpisodeGroupSummary(
            id = "6a7e07c2680ebc1e071c2efd",
            name = "Orden en Crunchyroll",
            description = "Orden y grupos de episodios, como sale ordenado en Crunchyroll España",
            episodeCount = 0,
            groupCount = 5,
            type = 4
        ),
        TmdbEpisodeGroupSummary(
            id = "69ec385eb5dd01912d028925",
            name = "Chapter Arc",
            description = "These chapter arcs are based on the Re:ZERO’s Director’s Cut!",
            episodeCount = 41,
            groupCount = 6,
            type = 5
        ),
        TmdbEpisodeGroupSummary(
            id = "69ec40bc75c2e8fbcd17cb5f",
            name = "Story Arc",
            description = "Re:Zero story arc ordering",
            episodeCount = 66,
            groupCount = 5,
            type = 5
        ),
        TmdbEpisodeGroupSummary(
            id = "641eb9d6b234b9007ac67063",
            name = "Seasons",
            description = "There are 4 seasons of the show.",
            episodeCount = 162,
            groupCount = 5,
            type = 6
        ),
        TmdbEpisodeGroupSummary(
            id = "696e21a5491fd52f47aab23c",
            name = "S0",
            description = "修正Season0播放顺序",
            episodeCount = 160,
            groupCount = 5,
            type = 6
        ),
        TmdbEpisodeGroupSummary(
            id = "69f9e2ef4f2ec25370c95c84",
            name = "Separate Seasons",
            description = "Separate the epidodes in 4 seasons with specials in time order",
            episodeCount = 164,
            groupCount = 5,
            type = 6
        )
    )

    @Test
    fun `selectBestTmdbEpisodeGroup selects production seasons with highest episode count`() {
        val selected = selectBestTmdbEpisodeGroup(reZeroGroups)
        assertNotNull(selected)
        assertEquals("69f9e2ef4f2ec25370c95c84", selected?.id)
        assertEquals("Separate Seasons", selected?.name)
        assertEquals(6, selected?.type)
        assertEquals(164, selected?.episodeCount)
    }

    @Test
    fun `selectBestTmdbEpisodeGroup filters out zero-episode groups`() {
        val zeroEpisodeGroups = listOf(
            TmdbEpisodeGroupSummary(id = "1", name = "Zero Ep 1", episodeCount = 0, groupCount = 1, type = 1),
            TmdbEpisodeGroupSummary(id = "2", name = "Zero Ep 2", episodeCount = 0, groupCount = 2, type = 6)
        )
        val selected = selectBestTmdbEpisodeGroup(zeroEpisodeGroups)
        assertNull(selected)
    }

    @Test
    fun `selectBestTmdbEpisodeGroup handles empty list gracefully`() {
        val selected = selectBestTmdbEpisodeGroup(emptyList())
        assertNull(selected)
    }

    @Test
    fun `selectBestTmdbEpisodeGroup prioritizes Original Air Date and Production over DVD, Digital, and Story Arc`() {
        val mixedGroups = listOf(
            TmdbEpisodeGroupSummary(id = "dvd", name = "DVD Order", episodeCount = 50, groupCount = 3, type = 3),
            TmdbEpisodeGroupSummary(id = "digital", name = "Digital Order", episodeCount = 50, groupCount = 3, type = 4),
            TmdbEpisodeGroupSummary(id = "story", name = "Story Arc", episodeCount = 50, groupCount = 5, type = 5),
            TmdbEpisodeGroupSummary(id = "tv_synd", name = "TV Syndication", episodeCount = 50, groupCount = 2, type = 7),
            TmdbEpisodeGroupSummary(id = "air", name = "Original Air Date", episodeCount = 50, groupCount = 3, type = 1)
        )
        val selected = selectBestTmdbEpisodeGroup(mixedGroups)
        assertNotNull(selected)
        assertEquals("air", selected?.id)
        assertEquals(1, selected?.type)
    }

    @Test
    fun `selectBestTmdbEpisodeGroup uses groupCount as tie-breaker when episodeCount matches`() {
        val tiedGroups = listOf(
            TmdbEpisodeGroupSummary(id = "g3", name = "Group 3", episodeCount = 24, groupCount = 3, type = 6),
            TmdbEpisodeGroupSummary(id = "g5", name = "Group 5", episodeCount = 24, groupCount = 5, type = 6)
        )
        val selected = selectBestTmdbEpisodeGroup(tiedGroups)
        assertNotNull(selected)
        assertEquals("g5", selected?.id)
        assertEquals(5, selected?.groupCount)
    }

    @Test
    fun `selectBestTmdbEpisodeGroup prefers Type 1 Original Air Date over Type 6 Production when both present`() {
        val groups = listOf(
            TmdbEpisodeGroupSummary(id = "prod", name = "Production Order", episodeCount = 100, groupCount = 4, type = 6),
            TmdbEpisodeGroupSummary(id = "aired", name = "Aired Order", episodeCount = 100, groupCount = 4, type = 1)
        )
        val selected = selectBestTmdbEpisodeGroup(groups)
        assertNotNull(selected)
        assertEquals("aired", selected?.id)
        assertEquals(1, selected?.type)
    }
}
