package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for anime watched status resolution across different content ID scenarios:
 * - Direct MAL entry (single MAL = single season)
 * - Multiple MAL entries sharing the same IMDB (different seasons of the same franchise)
 * - Split season (one TVDB season mapped to multiple MAL entries)
 * - Franchise parent content ID that doesn't exist in Simkl
 */
class SimklAnimeWatchedResolutionTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 1: Multiple MAL entries represent different seasons of the same IMDB.
    // canonicalContentId() for both returns "tt2560140" (IMDB wins).
    // Watched items end up under the same contentId with different tvdb seasons.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple MAL entries with same IMDB produce watched items for all seasons`() {
        val snapshot = snapshotWithMultiSeasonAnime()
        val projection = snapshot.toSimklWatchedProjection()

        // Both entries resolve to tt2560140 via canonicalContentId()
        val s1Items = projection.items.filter { it.contentId == "tt2560140" && it.season == 1 }
        val s2Items = projection.items.filter { it.contentId == "tt2560140" && it.season == 2 }

        assertEquals(3, s1Items.size)
        assertEquals(2, s2Items.size)
        assertTrue(s1Items.any { it.episode == 1 })
        assertTrue(s1Items.any { it.episode == 3 })
        assertTrue(s2Items.any { it.episode == 1 })
        assertTrue(s2Items.any { it.episode == 2 })
    }

    @Test
    fun `querying watched status via IMDB finds episodes from both MAL entries`() {
        val snapshot = snapshotWithMultiSeasonAnime()
        val items = snapshot.toSimklWatchedProjection().items

        // Direct match on canonical contentId "tt2560140"
        assertTrue(items.any { it.contentId == "tt2560140" && it.season == 1 && it.episode == 1 })
        assertTrue(items.any { it.contentId == "tt2560140" && it.season == 2 && it.episode == 2 })
        assertFalse(items.any { it.contentId == "tt2560140" && it.season == 2 && it.episode == 99 })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 2: User enters via MAL contentId (mal:123).
    // matchesSimklContentId should resolve it to the correct entry.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `MAL contentId resolves to matching entry via matchesSimklContentId`() {
        val snapshot = snapshotWithMultiSeasonAnime()

        // mal:123 → finds entry with mal=123 → canonical = tt2560140
        val entry = snapshot.entries.firstOrNull { it.matchesSimklContentId("mal:123") }
        assertTrue(entry != null)
        assertEquals("tt2560140", entry!!.media?.canonicalContentId())
    }

    @Test
    fun `resolveCanonicalContentId maps MAL ID to IMDB canonical`() {
        val snapshot = snapshotWithMultiSeasonAnime()

        // Both MAL IDs resolve to the same canonical
        val resolved123 = snapshot.entries
            .firstOrNull { it.matchesSimklContentId("mal:123") }
            ?.media?.canonicalContentId()
        val resolved4372 = snapshot.entries
            .firstOrNull { it.matchesSimklContentId("mal:4372") }
            ?.media?.canonicalContentId()

        assertEquals("tt2560140", resolved123)
        assertEquals("tt2560140", resolved4372)
    }

    @Test
    fun `episodeProgress via MAL contentId finds episodes using canonical resolution`() {
        val snapshot = snapshotWithMultiSeasonAnime()
        val items = snapshot.toSimklWatchedProjection().items

        // Resolve mal:123 → tt2560140 → find all episodes under that canonical
        val resolvedId = snapshot.entries
            .firstOrNull { it.matchesSimklContentId("mal:123") }
            ?.media?.canonicalContentId() ?: "mal:123"

        val episodesForResolved = items.filter {
            it.contentId.equals(resolvedId, ignoreCase = true) &&
                it.season != null && it.episode != null
        }

        // Should find episodes from BOTH entries (season 1 and season 2)
        assertEquals(5, episodesForResolved.size)
        assertTrue(episodesForResolved.any { it.season == 1 && it.episode == 1 })
        assertTrue(episodesForResolved.any { it.season == 2 && it.episode == 2 })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 3: Anime video ID resolution for franchise parent.
    // contentId = "mal:42423" doesn't exist in Simkl, but videoId "mal:123:3"
    // points to entry mal:123, episode 3.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `franchise parent not in snapshot cannot be resolved by matchesSimklContentId`() {
        val snapshot = snapshotWithMultiSeasonAnime()

        // mal:42423 doesn't exist in any entry
        val entry = snapshot.entries.firstOrNull { it.matchesSimklContentId("mal:42423") }
        assertTrue(entry == null)
    }

    @Test
    fun `anime videoId resolves watched for franchise parent via entry lookup`() {
        val snapshot = snapshotWithMultiSeasonAnime()

        // Parse "mal:123:3" → find entry with mal=123, check episode 3
        val entry = snapshot.entries.firstOrNull { entry ->
            entry.mediaType == SimklMediaType.ANIME &&
                entry.media?.ids?.idValue("mal") == "123"
        }
        assertTrue(entry != null)
        val isEp3Watched = entry!!.seasons.any { season ->
            season.episodes.any { ep -> ep.number == 3 && ep.watchedAt != null }
        }
        assertTrue(isEp3Watched)

        // "mal:4372:1" → entry with mal=4372, episode 1
        val entry2 = snapshot.entries.firstOrNull { entry ->
            entry.mediaType == SimklMediaType.ANIME &&
                entry.media?.ids?.idValue("mal") == "4372"
        }
        assertTrue(entry2 != null)
        val isS2Ep1Watched = entry2!!.seasons.any { season ->
            season.episodes.any { ep -> ep.number == 1 && ep.watchedAt != null }
        }
        assertTrue(isS2Ep1Watched)

        // Episode 99 not watched in mal:4372
        val isEp99Watched = entry2.seasons.any { season ->
            season.episodes.any { ep -> ep.number == 99 && ep.watchedAt != null }
        }
        assertFalse(isEp99Watched)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 4: Split season — one TVDB season, two MAL entries.
    // SAO season 1: mal:11757 (ep 1-14) and mal:11759 (ep 15-25 in absolute).
    // Video IDs: "mal:11757:14" and "mal:11759:1" (each relative to its MAL entry).
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `split season entries produce watched items with correct tvdb mapping`() {
        val snapshot = snapshotWithSplitSeason()
        val projection = snapshot.toSimklWatchedProjection()

        // mal:11757 episodes map to TVDB S1E1-S1E14
        assertTrue(projection.items.any { it.contentId == "tt2250192" && it.season == 1 && it.episode == 1 })
        assertTrue(projection.items.any { it.contentId == "tt2250192" && it.season == 1 && it.episode == 14 })

        // mal:11759 episodes map to TVDB S1E15-S1E19 (only 5 watched)
        assertTrue(projection.items.any { it.contentId == "tt2250192" && it.season == 1 && it.episode == 15 })
        assertTrue(projection.items.any { it.contentId == "tt2250192" && it.season == 1 && it.episode == 19 })

        // Episode 20+ not watched (mal:11759 ep 6+ has no watched_at)
        assertFalse(projection.items.any { it.contentId == "tt2250192" && it.season == 1 && it.episode == 20 })
    }

    @Test
    fun `split season resolves watched via entry lookup by MAL ID and episode number`() {
        val snapshot = snapshotWithSplitSeason()

        // "mal:11757:14" → entry with mal=11757, episode 14 (watched)
        val firstHalfEntry = snapshot.entries.first { entry ->
            entry.media?.ids?.idValue("mal") == "11757"
        }
        assertTrue(firstHalfEntry.seasons.any { s ->
            s.episodes.any { ep -> ep.number == 14 && ep.watchedAt != null }
        })

        // "mal:11759:1" → entry with mal=11759, episode 1 (watched)
        val secondHalfEntry = snapshot.entries.first { entry ->
            entry.media?.ids?.idValue("mal") == "11759"
        }
        assertTrue(secondHalfEntry.seasons.any { s ->
            s.episodes.any { ep -> ep.number == 1 && ep.watchedAt != null }
        })

        // "mal:11759:6" → entry with mal=11759, episode 6 (NOT watched)
        assertFalse(secondHalfEntry.seasons.any { s ->
            s.episodes.any { ep -> ep.number == 6 && ep.watchedAt != null }
        })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 5: Simple single MAL, single season.
    // contentId = mal:16498, everything is 1:1.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `single MAL entry with tvdb mapping shows correct watched episodes`() {
        val entry = animeEntry(
            simklId = 39687,
            imdb = "tt2560140",
            mal = 16498,
            seasons = listOf(
                SimklSeason(1, listOf(
                    SimklEpisode(1, "2023-11-14T23:00:00Z", SimklEpisodeMapping(1, 1)),
                    SimklEpisode(2, "2023-11-14T23:30:00Z", SimklEpisodeMapping(1, 2)),
                    SimklEpisode(3, null, SimklEpisodeMapping(1, 3)),
                    SimklEpisode(5, "2023-11-15T00:00:00Z", SimklEpisodeMapping(1, 5))
                ))
            )
        )
        val snapshot = SimklSyncSnapshot(entries = listOf(entry))
        val projection = snapshot.toSimklWatchedProjection()

        // Ep 1, 2, 5 watched; ep 3 not (null watchedAt)
        assertTrue(projection.items.any { it.season == 1 && it.episode == 1 })
        assertTrue(projection.items.any { it.season == 1 && it.episode == 2 })
        assertTrue(projection.items.any { it.season == 1 && it.episode == 5 })
        assertFalse(projection.items.any { it.season == 1 && it.episode == 3 })
    }

    @Test
    fun `single MAL entry resolves via MAL contentId`() {
        val entry = animeEntry(simklId = 39687, imdb = "tt2560140", mal = 16498)
        val snapshot = SimklSyncSnapshot(entries = listOf(entry))

        // Both "mal:16498" and "tt2560140" should match the same entry
        assertTrue(snapshot.entries.any { it.matchesSimklContentId("mal:16498") })
        assertTrue(snapshot.entries.any { it.matchesSimklContentId("tt2560140") })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 6: showIdSiblings correctly maps all IDs for anime entries
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `showIdSiblings maps MAL ID to IMDB and Simkl for anime entries`() {
        val snapshot = snapshotWithMultiSeasonAnime()

        // Build siblings map the same way the provider does
        val siblings = mutableMapOf<String, MutableSet<String>>()
        snapshot.entries.forEach { entry ->
            val ids = entry.media?.let { media ->
                buildSet {
                    media.canonicalContentId()?.let(::add)
                    media.ids.idValue("imdb")?.let(::add)
                    media.ids.idValue("tmdb")?.let { add("tmdb:$it") }
                    media.ids.idValue("mal")?.let { add("mal:$it") }
                    media.ids.simklIdValue()?.let { add("simkl:$it") }
                }
            }.orEmpty()
            ids.forEach { id -> siblings.getOrPut(id) { linkedSetOf() }.addAll(ids - id) }
        }

        // "mal:123" should have "tt2560140" and "simkl:39687" as siblings
        val mal123Siblings = siblings["mal:123"]
        assertTrue(mal123Siblings != null)
        assertTrue("tt2560140" in mal123Siblings!!)
        assertTrue("simkl:39687" in mal123Siblings)

        // "mal:4372" should have "tt2560140" and "simkl:39688" as siblings
        val mal4372Siblings = siblings["mal:4372"]
        assertTrue(mal4372Siblings != null)
        assertTrue("tt2560140" in mal4372Siblings!!)
        assertTrue("simkl:39688" in mal4372Siblings)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 7: IMDB contentId with seasons — Simkl returns multiple MAL entries.
    // User has anime under IMDB "tt2560140" with season 1 and season 2 in their addon.
    // Simkl has separate entries: mal:123 (tvdb S1) and mal:4372 (tvdb S2).
    // Both entries share the same IMDB, so watched items merge under tt2560140.
    // Verifies that querying episodeProgress("tt2560140") returns correct S1 and S2 episodes.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `IMDB contentId with seasons maps correctly to multiple MAL entries via tvdb`() {
        val snapshot = snapshotWithMultiSeasonAnime()
        val items = snapshot.toSimklWatchedProjection().items

        // User enters via IMDB — their addon shows S1 and S2
        val imdbItems = items.filter { it.contentId == "tt2560140" }

        // Season 1 episodes from mal:123 entry (tvdb mapped to S1E1, S1E2, S1E3)
        assertTrue(imdbItems.any { it.season == 1 && it.episode == 1 })
        assertTrue(imdbItems.any { it.season == 1 && it.episode == 2 })
        assertTrue(imdbItems.any { it.season == 1 && it.episode == 3 })

        // Season 2 episodes from mal:4372 entry (tvdb mapped to S2E1, S2E2)
        assertTrue(imdbItems.any { it.season == 2 && it.episode == 1 })
        assertTrue(imdbItems.any { it.season == 2 && it.episode == 2 })

        // No phantom episodes exist
        assertFalse(imdbItems.any { it.season == 1 && it.episode == 4 })
        assertFalse(imdbItems.any { it.season == 2 && it.episode == 3 })
        assertFalse(imdbItems.any { it.season == 3 })
    }

    @Test
    fun `IMDB contentId episodeProgress contains episodes from all related MAL entries`() {
        val snapshot = snapshotWithMultiSeasonAnime()
        val items = snapshot.toSimklWatchedProjection().items

        // Simulate what episodeProgress does: filter by contentId and collect (season, episode) pairs
        val episodeMap = items
            .filter { it.contentId.equals("tt2560140", ignoreCase = true) && it.season != null && it.episode != null }
            .associate { (requireNotNull(it.season) to requireNotNull(it.episode)) to it.watchedAt }

        // Season 1 from mal:123
        assertTrue((1 to 1) in episodeMap)
        assertTrue((1 to 2) in episodeMap)
        assertTrue((1 to 3) in episodeMap)

        // Season 2 from mal:4372
        assertTrue((2 to 1) in episodeMap)
        assertTrue((2 to 2) in episodeMap)

        // Total: 5 episodes
        assertEquals(5, episodeMap.size)
    }

    @Test
    fun `IMDB contentId with 3 seasons from 3 different MAL entries all mapped correctly`() {
        // Attack on Titan style: 3 MAL entries, all sharing tt2560140
        val s1 = animeEntry(simklId = 100, imdb = "tt2560140", mal = 16498, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2023-01-01T00:00:00Z", SimklEpisodeMapping(1, 1)),
                SimklEpisode(25, "2023-01-25T00:00:00Z", SimklEpisodeMapping(1, 25))
            ))
        ))
        val s2 = animeEntry(simklId = 101, imdb = "tt2560140", mal = 25777, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2023-04-01T00:00:00Z", SimklEpisodeMapping(2, 1)),
                SimklEpisode(12, "2023-04-12T00:00:00Z", SimklEpisodeMapping(2, 12))
            ))
        ))
        val s3 = animeEntry(simklId = 102, imdb = "tt2560140", mal = 36456, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2023-07-01T00:00:00Z", SimklEpisodeMapping(3, 1)),
                SimklEpisode(22, "2023-07-22T00:00:00Z", SimklEpisodeMapping(3, 22))
            ))
        ))
        val snapshot = SimklSyncSnapshot(entries = listOf(s1, s2, s3))
        val items = snapshot.toSimklWatchedProjection().items

        // All under tt2560140
        val allEps = items.filter { it.contentId == "tt2560140" }
        assertEquals(6, allEps.size)

        // Season 1 from mal:16498
        assertTrue(allEps.any { it.season == 1 && it.episode == 1 })
        assertTrue(allEps.any { it.season == 1 && it.episode == 25 })

        // Season 2 from mal:25777
        assertTrue(allEps.any { it.season == 2 && it.episode == 1 })
        assertTrue(allEps.any { it.season == 2 && it.episode == 12 })

        // Season 3 from mal:36456
        assertTrue(allEps.any { it.season == 3 && it.episode == 1 })
        assertTrue(allEps.any { it.season == 3 && it.episode == 22 })
    }

    @Test
    fun `IMDB contentId with partial watch across MAL entries shows correct state`() {
        // User watched all of season 1 (mal:123) but only 1 ep of season 2 (mal:4372)
        val s1Complete = animeEntry(simklId = 39687, imdb = "tt2560140", mal = 123, seasons = listOf(
            SimklSeason(1, (1..12).map { ep ->
                SimklEpisode(ep, "2023-11-${ep.toString().padStart(2, '0')}T20:00:00Z", SimklEpisodeMapping(1, ep))
            })
        )).copy(status = SimklListStatus.COMPLETED)

        val s2Partial = animeEntry(simklId = 39688, imdb = "tt2560140", mal = 4372, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2023-12-01T20:00:00Z", SimklEpisodeMapping(2, 1)),
                SimklEpisode(2, null, SimklEpisodeMapping(2, 2)),
                SimklEpisode(3, null, SimklEpisodeMapping(2, 3))
            ))
        ))

        val snapshot = SimklSyncSnapshot(entries = listOf(s1Complete, s2Partial))
        val items = snapshot.toSimklWatchedProjection().items

        // All 12 season 1 episodes watched
        val s1Items = items.filter { it.contentId == "tt2560140" && it.season == 1 }
        assertEquals(12, s1Items.size)

        // Only S2E1 watched, S2E2 and S2E3 not
        val s2Items = items.filter { it.contentId == "tt2560140" && it.season == 2 }
        assertEquals(1, s2Items.size)
        assertTrue(s2Items.any { it.episode == 1 })
        assertFalse(items.any { it.contentId == "tt2560140" && it.season == 2 && it.episode == 2 })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 8: Kitsu content IDs — same behavior as MAL.
    // Addon uses kitsu: prefix for content and video IDs.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `kitsu contentId resolves to canonical IMDB via matchesSimklContentId`() {
        val entry = animeEntry(simklId = 60001, imdb = "tt5311514", kitsu = 12268, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-01-01T10:00:00Z", SimklEpisodeMapping(1, 1)),
                SimklEpisode(2, "2024-01-01T10:30:00Z", SimklEpisodeMapping(1, 2))
            ))
        ))
        val snapshot = SimklSyncSnapshot(entries = listOf(entry))

        assertTrue(snapshot.entries.any { it.matchesSimklContentId("kitsu:12268") })
        assertTrue(snapshot.entries.any { it.matchesSimklContentId("tt5311514") })
        assertEquals("tt5311514", entry.media?.canonicalContentId())
    }

    @Test
    fun `kitsu videoId resolves watched episode in Simkl entry`() {
        val entry = animeEntry(simklId = 60001, imdb = "tt5311514", kitsu = 12268, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-01-01T10:00:00Z", SimklEpisodeMapping(1, 1)),
                SimklEpisode(2, "2024-01-01T10:30:00Z", SimklEpisodeMapping(1, 2)),
                SimklEpisode(3, null, SimklEpisodeMapping(1, 3))
            ))
        ))
        val snapshot = SimklSyncSnapshot(entries = listOf(entry))

        // Simulate isWatchedByVideoId: parse "kitsu:12268:2" → kitsu=12268, ep=2
        val kitsuEntry = snapshot.entries.firstOrNull { e ->
            e.mediaType == SimklMediaType.ANIME &&
                e.media?.ids?.idValue("kitsu") == "12268"
        }
        assertTrue(kitsuEntry != null)
        assertTrue(kitsuEntry!!.seasons.any { s -> s.episodes.any { ep -> ep.number == 2 && ep.watchedAt != null } })
        assertFalse(kitsuEntry.seasons.any { s -> s.episodes.any { ep -> ep.number == 3 && ep.watchedAt != null } })
    }

    @Test
    fun `kitsu multi-season with same IMDB merges correctly`() {
        // Two kitsu entries for the same show (different seasons)
        val s1 = animeEntry(simklId = 60001, imdb = "tt5311514", kitsu = 12268, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-01-01T10:00:00Z", SimklEpisodeMapping(1, 1)),
                SimklEpisode(12, "2024-01-12T10:00:00Z", SimklEpisodeMapping(1, 12))
            ))
        ))
        val s2 = animeEntry(simklId = 60002, imdb = "tt5311514", kitsu = 42422, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-04-01T10:00:00Z", SimklEpisodeMapping(2, 1)),
                SimklEpisode(13, "2024-04-13T10:00:00Z", SimklEpisodeMapping(2, 13))
            ))
        ))
        val snapshot = SimklSyncSnapshot(entries = listOf(s1, s2))
        val items = snapshot.toSimklWatchedProjection().items

        // Both merge under tt5311514
        val allEps = items.filter { it.contentId == "tt5311514" }
        assertEquals(4, allEps.size)
        assertTrue(allEps.any { it.season == 1 && it.episode == 1 })
        assertTrue(allEps.any { it.season == 1 && it.episode == 12 })
        assertTrue(allEps.any { it.season == 2 && it.episode == 1 })
        assertTrue(allEps.any { it.season == 2 && it.episode == 13 })
    }

    @Test
    fun `kitsu franchise parent resolves watched via kitsu videoId`() {
        // Franchise parent kitsu:99999 doesn't exist in Simkl
        val s1 = animeEntry(simklId = 60001, imdb = "tt5311514", kitsu = 12268, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-01-01T10:00:00Z", SimklEpisodeMapping(1, 1)),
                SimklEpisode(5, "2024-01-05T10:00:00Z", SimklEpisodeMapping(1, 5))
            ))
        ))
        val s2 = animeEntry(simklId = 60002, imdb = "tt5311514", kitsu = 42422, seasons = listOf(
            SimklSeason(1, listOf(
                SimklEpisode(1, "2024-04-01T10:00:00Z", SimklEpisodeMapping(2, 1)),
                SimklEpisode(3, "2024-04-03T10:00:00Z", SimklEpisodeMapping(2, 3))
            ))
        ))
        val snapshot = SimklSyncSnapshot(entries = listOf(s1, s2))

        // kitsu:99999 doesn't exist
        assertFalse(snapshot.entries.any { it.matchesSimklContentId("kitsu:99999") })

        // But we can resolve via videoId "kitsu:12268:5" → entry with kitsu=12268, ep 5
        val entry1 = snapshot.entries.first { it.media?.ids?.idValue("kitsu") == "12268" }
        assertTrue(entry1.seasons.any { s -> s.episodes.any { ep -> ep.number == 5 && ep.watchedAt != null } })

        // "kitsu:42422:3" → entry with kitsu=42422, ep 3
        val entry2 = snapshot.entries.first { it.media?.ids?.idValue("kitsu") == "42422" }
        assertTrue(entry2.seasons.any { s -> s.episodes.any { ep -> ep.number == 3 && ep.watchedAt != null } })

        // Unwatched: "kitsu:42422:10" → ep 10 doesn't exist
        assertFalse(entry2.seasons.any { s -> s.episodes.any { ep -> ep.number == 10 && ep.watchedAt != null } })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Two MAL entries sharing the same IMDB, representing two seasons:
     * - mal:123 → TVDB season 1 (ep 1-3 watched)
     * - mal:4372 → TVDB season 2 (ep 1-2 watched)
     */
    private fun snapshotWithMultiSeasonAnime(): SimklSyncSnapshot {
        val season1Entry = animeEntry(
            simklId = 39687,
            imdb = "tt2560140",
            mal = 123,
            seasons = listOf(
                SimklSeason(1, listOf(
                    SimklEpisode(1, "2023-11-14T23:00:00Z", SimklEpisodeMapping(1, 1)),
                    SimklEpisode(2, "2023-11-14T23:10:00Z", SimklEpisodeMapping(1, 2)),
                    SimklEpisode(3, "2023-11-14T23:20:00Z", SimklEpisodeMapping(1, 3))
                ))
            )
        )
        val season2Entry = animeEntry(
            simklId = 39688,
            imdb = "tt2560140",
            mal = 4372,
            seasons = listOf(
                SimklSeason(1, listOf(
                    SimklEpisode(1, "2023-12-01T20:00:00Z", SimklEpisodeMapping(2, 1)),
                    SimklEpisode(2, "2023-12-01T20:30:00Z", SimklEpisodeMapping(2, 2))
                ))
            )
        )
        return SimklSyncSnapshot(entries = listOf(season1Entry, season2Entry))
    }

    /**
     * Split season: one TVDB season spread across two MAL entries.
     * - mal:11757 → TVDB S1E1-S1E14 (all 14 episodes watched)
     * - mal:11759 → TVDB S1E15-S1E25 (episodes 1-5 watched, 6-11 not watched)
     */
    private fun snapshotWithSplitSeason(): SimklSyncSnapshot {
        val firstHalf = animeEntry(
            simklId = 50001,
            imdb = "tt2250192",
            mal = 11757,
            seasons = listOf(
                SimklSeason(1, (1..14).map { ep ->
                    SimklEpisode(ep, "2023-11-14T23:${ep.toString().padStart(2, '0')}:00Z", SimklEpisodeMapping(1, ep))
                })
            )
        )
        val secondHalf = animeEntry(
            simklId = 50002,
            imdb = "tt2250192",
            mal = 11759,
            seasons = listOf(
                SimklSeason(1, (1..11).map { ep ->
                    SimklEpisode(
                        ep,
                        if (ep <= 5) "2023-11-15T${ep.toString().padStart(2, '0')}:00:00Z" else null,
                        SimklEpisodeMapping(1, 14 + ep)
                    )
                })
            )
        )
        return SimklSyncSnapshot(entries = listOf(firstHalf, secondHalf))
    }

    private fun animeEntry(
        simklId: Long,
        imdb: String? = null,
        mal: Long? = null,
        kitsu: Long? = null,
        seasons: List<SimklSeason> = emptyList()
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        status = SimklListStatus.WATCHING,
        lastWatchedAt = "2023-12-01T20:00:00Z",
        seasons = seasons,
        show = SimklMedia(
            title = "Anime $simklId",
            poster = "poster/$simklId",
            year = 2020,
            ids = buildJsonObject {
                put("simkl", simklId)
                imdb?.let { put("imdb", it) }
                mal?.let { put("mal", it) }
                kitsu?.let { put("kitsu", it) }
            }
        )
    )
}
